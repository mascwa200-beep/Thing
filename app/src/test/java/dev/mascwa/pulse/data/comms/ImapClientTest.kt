package dev.mascwa.pulse.data.comms

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * The whole client, against a mailbox that answers.
 *
 * ⚠️ **A real login cannot be tested from a build machine** — there is no account, and port 993 is
 * blocked from the container this was written in. What CAN be tested is everything else, because
 * loopback is not blocked: a fake server on a `ServerSocket` speaks the same three commands, and the
 * client is driven end to end through its real `converse` loop, its real timeouts and its real
 * teardown. Only "does Gmail accept this app password" is left for the owner.
 *
 * The seam is [ImapClient]'s `connect` parameter, which exists for exactly this and defaults to TLS
 * with hostname verification — a plain socket is a test's, never a shipping path's.
 */
class ImapClientTest {

    /**
     * A mailbox that says what it is told to say.
     *
     * Records every command it received, so a test can assert on what went over the wire rather
     * than only on what came back — the quoting of a password is not visible from the result.
     */
    private class FakeServer(private val script: (String) -> List<String>) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val received = mutableListOf<String>()
        val port: Int get() = server.localPort
        private val worker: Thread

        init {
            worker = thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                        val out = s.getOutputStream()
                        fun send(line: String) {
                            out.write((line + "\r\n").toByteArray(Charsets.UTF_8)); out.flush()
                        }
                        send("* OK [CAPABILITY IMAP4rev1] fake ready")
                        while (true) {
                            val line = reader.readLine() ?: break
                            synchronized(received) { received += line }
                            script(line).forEach(::send)
                            if (line.contains("LOGOUT")) break
                        }
                    }
                }
            }
        }

        fun commands(): List<String> = synchronized(received) { received.toList() }

        override fun close() {
            runCatching { server.close() }
            worker.interrupt()
        }
    }

    /** A server that behaves: accepts any login and reports [unseen]. */
    private fun happy(unseen: Int): (String) -> List<String> = { line ->
        val tag = line.substringBefore(' ')
        when {
            "LOGIN" in line -> listOf("$tag OK LOGIN completed")
            "STATUS" in line -> listOf("""* STATUS "INBOX" (MESSAGES 90 UNSEEN $unseen)""", "$tag OK STATUS completed")
            "LOGOUT" in line -> listOf("* BYE", "$tag OK LOGOUT completed")
            else -> listOf("$tag BAD unknown")
        }
    }

    private fun clientFor(server: FakeServer) = ImapClient { _, port, timeout ->
        Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = timeout }
    }.let { it to server }

    @Test
    fun `a mailbox that answers gives its unread count`() {
        FakeServer(happy(7)).use { server ->
            val (client, _) = clientFor(server)
            val r = runBlocking { client.unread("localhost", server.port, "me@x.test", "pw") }
            assertEquals(ImapClient.Result.Unread(7), r)
        }
    }

    @Test
    fun `an empty inbox is zero, not absence`() {
        FakeServer(happy(0)).use { server ->
            val (client, _) = clientFor(server)
            assertEquals(ImapClient.Result.Unread(0), runBlocking { client.unread("localhost", server.port, "u", "p") })
        }
    }

    @Test
    fun `the password goes over the wire quoted`() {
        // ⚠️ The result cannot show this — a server that accepts anything accepts a mangled command
        // too. Asserting on what was RECEIVED is the only way to see that a quote in a password was
        // escaped rather than left to end the argument early.
        FakeServer(happy(1)).use { server ->
            val (client, _) = clientFor(server)
            runBlocking { client.unread("localhost", server.port, "me@x.test", """pa"ss\word""") }
            val login = server.commands().first { "LOGIN" in it }
            assertEquals("""a1 LOGIN "me@x.test" "pa\"ss\\word"""", login)
        }
    }

    @Test
    fun `it says goodbye rather than leaving the connection to time out`() {
        // A server counts an abandoned connection against a per-account limit for minutes, which on
        // a half-hourly poll is most of the time.
        FakeServer(happy(2)).use { server ->
            val (client, _) = clientFor(server)
            runBlocking { client.unread("localhost", server.port, "u", "p") }
            assertTrue(server.commands().toString(), server.commands().any { "LOGOUT" in it })
        }
    }

    @Test
    fun `a refused sign-in is a refusal, not a network problem`() {
        val refusing: (String) -> List<String> = { line ->
            val tag = line.substringBefore(' ')
            if ("LOGIN" in line) listOf("$tag NO [AUTHENTICATIONFAILED] Invalid credentials")
            else listOf("$tag OK done")
        }
        FakeServer(refusing).use { server ->
            val (client, _) = clientFor(server)
            val r = runBlocking { client.unread("imap.gmail.com", server.port, "u", "p") }
            assertTrue(r.toString(), r is ImapClient.Result.Refused)
            // The host is one that wants an app password, so the message must say so — that is the
            // difference between a setting somebody can fix and one that looks broken.
            assertTrue(r.toString(), "app password" in (r as ImapClient.Result.Refused).message)
        }
    }

    @Test
    fun `nothing listening is unreachable, which is a different thing`() {
        // ⚠️ Refused and Unreachable mean opposite things to whoever set the account up: one says
        // the settings are wrong, the other says the network is. Collapsing them is the defect this
        // repository keeps finding.
        val free = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val client = ImapClient { _, port, timeout ->
            Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = timeout }
        }
        val r = runBlocking { client.unread("localhost", free, "u", "p") }
        assertTrue(r.toString(), r is ImapClient.Result.Unreachable)
    }

    @Test
    fun `a server that accepts the command and reports nothing does not become zero`() {
        val silent: (String) -> List<String> = { line ->
            val tag = line.substringBefore(' ')
            listOf("$tag OK done")   // OK to everything, and never a STATUS line
        }
        FakeServer(silent).use { server ->
            val (client, _) = clientFor(server)
            val r = runBlocking { client.unread("mail.example.org", server.port, "u", "p") }
            // Zero unread would be an invented answer. It is a refusal with a reason.
            assertTrue(r.toString(), r is ImapClient.Result.Refused)
            assertTrue(r.toString(), "unread count" in (r as ImapClient.Result.Refused).message)
        }
    }

    @Test
    fun `a greeting that refuses outright is a refusal`() {
        val busy = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            runCatching {
                busy.accept().use { s ->
                    s.getOutputStream().write("* BYE Too many connections\r\n".toByteArray())
                    s.getOutputStream().flush()
                }
            }
        }
        val client = ImapClient { _, port, timeout ->
            Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = timeout }
        }
        val r = runBlocking { client.unread("mail.example.org", busy.localPort, "u", "p") }
        busy.close()
        assertTrue(r.toString(), r is ImapClient.Result.Refused)
    }

    @Test
    fun `a username that cannot be sent is refused without opening a socket`() {
        // Nothing is listening on this port, so reaching the network at all would give Unreachable.
        // A Refused proves the command was rejected before a connection was attempted.
        val client = ImapClient { _, _, _ -> error("must not connect") }
        val r = runBlocking { client.unread("mail.example.org", 993, "us\ner", "p") }
        assertTrue(r.toString(), r is ImapClient.Result.Refused)
    }
}
