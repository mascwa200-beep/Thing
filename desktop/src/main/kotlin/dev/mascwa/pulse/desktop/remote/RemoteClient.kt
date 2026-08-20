package dev.mascwa.pulse.desktop.remote

import dev.mascwa.pulse.core.telemetry.Handshake
import dev.mascwa.pulse.core.telemetry.PROTOCOL_VERSION
import dev.mascwa.pulse.core.telemetry.PairingProof
import dev.mascwa.pulse.core.telemetry.RemoteCommand
import dev.mascwa.pulse.core.telemetry.RemoteCrypto
import dev.mascwa.pulse.core.telemetry.RemoteReply
import dev.mascwa.pulse.core.telemetry.RemoteRequest
import dev.mascwa.pulse.core.telemetry.RemoteWire
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The desktop end of the LAN link: opens a TCP connection to the phone, performs the signed-ephemeral-ECDH
 * handshake described in [RemoteProtocol], then exchanges AES-GCM records carrying [RemoteRequest]s.
 *
 * Connections are deliberately **short-lived, one per exchange** rather than a long-held session. A phone
 * sleeps, changes networks and moves in and out of range constantly; a persistent socket would spend most
 * of its life either dead or being nursed back to life with reconnect logic. Re-handshaking costs one round
 * trip on a LAN, and it means every exchange gets fresh ephemeral keys — so this is also the more
 * conservative choice cryptographically.
 *
 * Every method returns a [Result]-shaped outcome instead of throwing: the caller is UI, and a phone being
 * asleep is an ordinary condition, not an exception.
 */
class RemoteClient(private val identity: DesktopIdentity) {

    /** What a single exchange produced. [Failure.reason] is already phrased for display. */
    sealed interface Outcome {
        data class Success(val reply: RemoteReply, val peerSpki: ByteArray) : Outcome
        data class Failure(val reason: String) : Outcome
    }

    /**
     * Run one command against [host]:[port].
     *
     * [pairingCode] is supplied only while pairing; afterwards pass null and [expectedPeerSpki] instead, so
     * the client refuses to talk to a device that is not the one previously paired with — otherwise anything
     * answering on that address could impersonate the phone.
     */
    suspend fun exchange(
        host: String,
        port: Int,
        request: RemoteRequest,
        pairingCode: String? = null,
        expectedPeerSpki: ByteArray? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Outcome = withContext(Dispatchers.IO) {
        val myIdentitySpki = identity.publicKeySpki()
            ?: return@withContext Outcome.Failure("This machine has no identity key.")
        val myPrivate = identity.privateKey()
            ?: return@withContext Outcome.Failure("This machine's identity key is unusable.")

        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.soTimeout = timeoutMs
                val input = DataInputStream(socket.getInputStream().buffered())
                val output = DataOutputStream(socket.getOutputStream().buffered())

                // --- 1. offer: our ephemeral key + identity, signed so the phone knows it is ours ---
                val ephemeral = KeyPairGenerator.getInstance("EC").apply {
                    initialize(ECGenParameterSpec("secp256r1"))
                }.generateKeyPair()

                writeFrame(
                    output,
                    RemoteWire.packBlobs(
                        listOf(
                            PROTOCOL_VERSION.toByteArray(Charsets.UTF_8),
                            ephemeral.public.encoded,
                            myIdentitySpki,
                        ),
                    ),
                )

                // --- 2. answer: the phone's ephemeral key, its identity, and its transcript signature ---
                val answer = RemoteWire.unpackBlobs(readFrame(input), 3)
                    ?: return@use Outcome.Failure("The device sent a malformed handshake.")
                val (peerEphemeral, peerIdentity, peerSignature) = Triple(answer[0], answer[1], answer[2])

                if (expectedPeerSpki != null && !peerIdentity.contentEquals(expectedPeerSpki)) {
                    return@use Outcome.Failure(
                        "This is not the device you paired with — its identity key does not match. " +
                            "Pair again only if you expected the phone to be reset.",
                    )
                }

                val transcript = Handshake.transcript(
                    clientEphemeralSpki = ephemeral.public.encoded,
                    serverEphemeralSpki = peerEphemeral,
                    clientIdentitySpki = myIdentitySpki,
                    serverIdentitySpki = peerIdentity,
                )

                if (!verify(transcript, peerSignature, peerIdentity)) {
                    return@use Outcome.Failure("The device failed to prove its identity.")
                }

                // --- 3. confirm: our transcript signature, plus the pairing proof when pairing ---
                val mySignature = identity.sign(transcript)
                    ?: return@use Outcome.Failure("Could not sign the handshake.")
                val proof = pairingCode
                    ?.let { PairingProof.proof(it, transcript) }
                    ?: ByteArray(0)
                writeFrame(output, RemoteWire.packBlobs(listOf(mySignature, proof)))

                // --- 4. records, under keys bound to this exact handshake ---
                val secret = RemoteCrypto.sharedSecret(ephemeral.private, peerEphemeral)
                    ?: return@use Outcome.Failure("Key agreement failed.")
                val (c2s, s2c) = Handshake.deriveKeys(secret, transcript)

                val sealed = RemoteCrypto.seal(c2s, 0, request.toBytes())
                    ?: return@use Outcome.Failure("Could not encrypt the command.")
                writeFrame(output, sealed)

                val replyBytes = RemoteCrypto.open(s2c, 0, readFrame(input))
                    ?: return@use Outcome.Failure(
                        if (pairingCode != null) "The device rejected that pairing code."
                        else "The device rejected this machine. Pair again.",
                    )
                val reply = RemoteReply.parse(replyBytes)
                    ?: return@use Outcome.Failure("The device sent an unreadable reply.")

                Outcome.Success(reply, peerIdentity)
            }
        }.getOrElse { e -> Outcome.Failure(describe(e)) }
    }

    private fun verify(data: ByteArray, signature: ByteArray, spki: ByteArray): Boolean = runCatching {
        val key = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(spki))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    /** Turn a raw exception into something worth showing a person. */
    private fun describe(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException ->
            "The phone did not answer. Check it is awake, on the same Wi-Fi, and the link is switched on."
        is java.net.ConnectException ->
            "Could not reach the phone. Check the address and that the link is switched on."
        is java.net.UnknownHostException -> "That address does not resolve."
        else -> e.message ?: e::class.java.simpleName
    }

    private fun writeFrame(out: DataOutputStream, body: ByteArray) {
        require(body.size <= RemoteWire.MAX_FRAME_BYTES) { "frame too large" }
        out.write(RemoteWire.intToBe(body.size))
        out.write(body)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        val len = RemoteWire.beToInt(header, 0)
        // A hostile or broken peer must never be able to make us allocate an arbitrary buffer.
        if (len < 0 || len > RemoteWire.MAX_FRAME_BYTES) throw java.io.IOException("Bad frame length: $len")
        return ByteArray(len).also { input.readFully(it) }
    }

    companion object {
        /** The phone's default listening port. Chosen high and unprivileged; configurable at both ends. */
        const val DEFAULT_PORT = 8765

        private const val DEFAULT_TIMEOUT_MS = 6_000
    }
}

/** Parses the field-encoded key/value payload a [RemoteCommand.STATUS] reply carries. */
object RemoteStatus {
    fun parse(payload: String): Map<String, String> {
        val fields = RemoteWire.decode(payload.toByteArray(Charsets.UTF_8)) ?: return emptyMap()
        return fields.mapNotNull { f ->
            val i = f.indexOf('=')
            if (i <= 0) null else f.substring(0, i) to f.substring(i + 1)
        }.toMap()
    }
}
