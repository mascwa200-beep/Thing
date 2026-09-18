package dev.mascwa.pulse.data.comms

import dev.mascwa.pulse.core.telemetry.ImapProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Asks a mailbox how many messages are unread, and nothing else.
 *
 * Three commands over TLS. The parsing is in [ImapProtocol], which is pure and tested; this is the
 * socket around it — read, write, and close whatever happens.
 *
 * ## ⚠️ Hostname verification is not automatic and its absence is silent
 *
 * A layered `SSLSocket` validates the certificate CHAIN and does **not** check that the certificate
 * was issued for the host you dialled, unless the endpoint identification algorithm is set. Without
 * [HOSTNAME_VERIFICATION] anybody holding a valid certificate for any domain at all could answer for
 * this one, and the connection would succeed exactly as it does now — no error, no warning, and a
 * password handed over. `HttpsURLConnection` sets this for you and a raw socket does not, which is
 * why hand-rolled TLS clients get it wrong.
 *
 * ## Why a socket rather than a mail library
 *
 * The conversation is three lines. JavaMail is megabytes and this repository has hand-rolled
 * RFC 3161, ICY metadata and its own remote protocol for the same reason.
 */
class ImapClient(
    /**
     * How to reach a host.
     *
     * ⚠️ The default is the only one that ships, and it is TLS with hostname verification. The seam
     * exists so a test can point the client at a fake in-process server — a real login cannot be
     * tested from a build machine, and port 993 is blocked from this one — and NOT to make the
     * transport configurable. A caller that supplied a plain socket would be handing a password to
     * anything on the wire, so nothing but a test may.
     */
    private val connect: (host: String, port: Int, timeoutMs: Int) -> Socket = ::tlsSocket,
) {

    /** What the mailbox said, or why it did not say anything. */
    sealed interface Result {
        /** [count] messages are unread. */
        data class Unread(val count: Int) : Result

        /**
         * The server answered and refused, or answered something unusable.
         *
         * ⚠️ Distinct from [Unreachable] because they mean opposite things to whoever set the
         * account up: this one says the settings are wrong, the other says the network is.
         */
        data class Refused(val message: String) : Result

        /** Nothing answered — no network, wrong host, wrong port, a timeout. */
        data class Unreachable(val message: String) : Result
    }

    /**
     * The unread count in [mailbox] for one account.
     *
     * Always closes the socket, and always sends `LOGOUT` before it does when the session got that
     * far — a server that is left to time the connection out counts against a per-account
     * connection limit for minutes afterwards, which on a half-hourly poll is most of the time.
     */
    suspend fun unread(
        host: String,
        port: Int,
        user: String,
        password: String,
        mailbox: String = "INBOX",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Result = withContext(Dispatchers.IO) {
        val loginCmd = ImapProtocol.login(ImapProtocol.tag(1), user, password)
            ?: return@withContext Result.Refused("That username or password cannot be sent over IMAP.")
        val statusCmd = ImapProtocol.statusUnseen(ImapProtocol.tag(2), mailbox)
            ?: return@withContext Result.Refused("That mailbox name cannot be sent over IMAP.")

        var socket: Socket? = null
        try {
            val s = connect(host, port, timeoutMs)
            socket = s
            s.soTimeout = timeoutMs
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)

            // The greeting. A server that opens with `* BYE` is refusing before a command is sent —
            // usually a connection limit, and worth reporting as a refusal rather than as silence.
            val greeting = reader.readLine() ?: return@withContext Result.Unreachable("$host closed the connection.")
            if (greeting.trimStart().startsWith("* BYE", ignoreCase = true)) {
                return@withContext Result.Refused(greeting.take(120))
            }

            val login = converse(writer, reader, loginCmd, ImapProtocol.tag(1))
            if (login.status != ImapProtocol.Status.OK) {
                logout(writer, reader)
                return@withContext Result.Refused(ImapProtocol.explain(host, login.status, login.detail))
            }

            val status = converse(writer, reader, statusCmd, ImapProtocol.tag(2))
            logout(writer, reader)
            val count = status.unseen
            when {
                count != null -> Result.Unread(count)
                status.status != ImapProtocol.Status.OK ->
                    Result.Refused(ImapProtocol.explain(host, status.status, status.detail))
                // ⚠️ OK with no count is its own case: the server accepted the command and told us
                // nothing, which is a different fault from refusing it, and reporting zero unread
                // would be inventing an answer.
                else -> Result.Refused("$host answered without an unread count.")
            }
        } catch (e: Exception) {
            Result.Unreachable("${e::class.java.simpleName}: ${e.message.orEmpty().take(80)}".trim(':', ' '))
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** One command and everything the server said back until it answered with our tag. */
    private data class Exchange(
        val status: ImapProtocol.Status?,
        val detail: String,
        val unseen: Int?,
    )

    private fun converse(
        writer: Writer,
        reader: BufferedReader,
        command: String,
        tag: String,
    ): Exchange {
        writer.write(command)
        writer.write(CRLF)
        writer.flush()
        var unseen: Int? = null
        // ⚠️ Bounded. A server that never sends our tag back would otherwise hold this thread until
        // the read timeout on every single line, forever — the loop, not the socket, is what needs
        // the limit. Untagged lines before an answer are ordinary and there are never many.
        repeat(MAX_LINES) {
            val line = reader.readLine() ?: return Exchange(null, "the connection closed", unseen)
            ImapProtocol.unseen(line)?.let { unseen = it }
            ImapProtocol.taggedStatus(line, tag)?.let { status ->
                return Exchange(status, line.substringAfter(tag).trim(), unseen)
            }
        }
        return Exchange(null, "the server did not answer", unseen)
    }

    /** Best-effort: a failed goodbye must not turn a good reading into an error. */
    private fun logout(writer: Writer, reader: BufferedReader) {
        runCatching {
            writer.write(ImapProtocol.tag(3) + " LOGOUT")
            writer.write(CRLF)
            writer.flush()
            reader.readLine()
        }
    }

    companion object {
        const val CRLF = "\r\n"

        /**
         * Long enough for a slow mailbox on a slow connection, short enough that a hung server
         * cannot eat the widget's or the worker's budget.
         */
        const val DEFAULT_TIMEOUT_MS = 8_000

        /** How many untagged lines to read before giving up on ever seeing our own tag. */
        const val MAX_LINES = 64

        /**
         * The one string that makes a raw `SSLSocket` check the certificate belongs to the host.
         *
         * ⚠️ "HTTPS" names the verification RULES (RFC 2818 — subject alternative names, wildcards),
         * not the protocol being spoken. It is the correct value for IMAP over TLS and for every
         * other protocol; there is no "IMAPS" algorithm.
         */
        const val HOSTNAME_VERIFICATION = "HTTPS"

        /** A connected, verified TLS socket. The only transport that ships. */
        fun tlsSocket(host: String, port: Int, timeoutMs: Int): Socket {
            // Connect with a timeout first, then layer TLS over it. `createSocket(host, port)` has
            // no connect timeout at all, so an unreachable host would block until the OS gave up.
            val plain = Socket()
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            plain.soTimeout = timeoutMs
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, host, port, /* autoClose = */ true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                endpointIdentificationAlgorithm = HOSTNAME_VERIFICATION
            }
            ssl.startHandshake()
            return ssl
        }
    }
}
