package dev.mascwa.pulse.remote

import dev.mascwa.pulse.core.telemetry.Handshake
import dev.mascwa.pulse.core.telemetry.LocalNetwork
import dev.mascwa.pulse.core.telemetry.PROTOCOL_VERSION
import dev.mascwa.pulse.core.telemetry.PairingProof
import dev.mascwa.pulse.core.telemetry.RemoteCrypto
import dev.mascwa.pulse.core.telemetry.RemoteReply
import dev.mascwa.pulse.core.telemetry.RemoteRequest
import dev.mascwa.pulse.core.telemetry.RemoteWire
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's side of the LAN link: a TCP listener that accepts one command per connection.
 *
 * ## Layered refusals
 *
 * A listening socket is the app's only inbound surface, so every request has to clear several independent
 * gates before it can do anything, and each is cheap enough to reject a flood early:
 *
 * 1. **Address** — the peer must be on a private/link-local network ([LocalNetwork.isLocalAddress]). Even a
 *    misconfigured router forwarding this port cannot get past this.
 * 2. **Frame sanity** — every length is bounded by [RemoteWire.MAX_FRAME_BYTES], so no peer can make the
 *    phone allocate an arbitrary buffer.
 * 3. **Identity** — outside a pairing window, the desktop's identity key must already be a paired one, and
 *    it must sign the handshake transcript. An unknown or unsigned peer never reaches a command.
 * 4. **Pairing code** — during a pairing window, the peer must additionally prove the 6-digit code.
 * 5. **Allowlist** — the decrypted request must parse to a known [dev.mascwa.pulse.core.telemetry.RemoteCommand].
 *
 * Only then does [RemoteCommandExecutor] run, and every outcome — accepted or refused — is audited.
 *
 * One connection handles exactly one command and then closes. That keeps state per-connection (no session
 * table to leak or confuse) and means a half-open socket from a phone that moved networks costs nothing.
 */
class RemoteServer(
    private val identity: RemoteIdentity,
    private val executor: RemoteCommandExecutor,
    private val peers: RemotePeers,
    private val onEvent: (String) -> Unit = {},
) {

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The pairing code currently being displayed, and when it expires. Null when not pairing. */
    @Volatile private var pairing: Pairing? = null

    private val inFlight = AtomicInteger(0)

    /** Last time each refusal reason was audited, so a flood can't drive one ledger write per packet. */
    private val lastRefusalMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private data class Pairing(val code: String, val expiresAtMs: Long)

    /** Opens a pairing window. The code is single-use and short-lived — both properties matter. */
    fun beginPairing(code: String, ttlMs: Long = PAIRING_TTL_MS) {
        pairing = Pairing(code, System.currentTimeMillis() + ttlMs)
    }

    fun cancelPairing() {
        pairing = null
    }

    fun isPairing(): Boolean = pairing?.let { it.expiresAtMs > System.currentTimeMillis() } == true

    /** Starts listening. Returns the bound port, or null if the socket could not be opened. */
    fun start(port: Int = DEFAULT_PORT): Int? {
        if (serverSocket != null) return serverSocket?.localPort
        val socket = runCatching { ServerSocket(port) }.getOrNull()
            ?: runCatching { ServerSocket(0) }.getOrNull()   // fall back to any free port
            ?: return null
        serverSocket = socket
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                // Anyone on the Wi-Fi can open a socket without any credential, so the accept loop needs
                // a ceiling: each handshake costs a secure-element signature (milliseconds) before the
                // peer is authenticated, which is otherwise a ready-made amplifier against our own phone.
                if (inFlight.get() >= MAX_CONCURRENT) {
                    runCatching { client.close() }
                    continue
                }
                inFlight.incrementAndGet()
                // Handled on its own coroutine so one slow or hostile peer cannot block the accept loop.
                launch {
                    try {
                        runCatching { handle(client) }
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        pairing = null
        // Cancels in-flight handlers too — each holds an open socket, so leaving them running would
        // mean the link stays partly alive after the user switched it off.
        scope.cancel()
    }

    private suspend fun handle(client: Socket) = withContext(Dispatchers.IO) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS

            val peerAddress = socket.inetAddress?.hostAddress.orEmpty()
            if (!LocalNetwork.isLocalAddress(peerAddress)) {
                onEvent("Refused a connection from outside the local network.")
                auditRefusalThrottled("offnet", "peer=${peerAddress.take(45)}")
                return@withContext
            }

            val identitySpki = identity.publicKeySpki() ?: return@withContext
            val input = DataInputStream(socket.getInputStream().buffered())
            val output = DataOutputStream(socket.getOutputStream().buffered())

            // --- 1. the desktop's offer ---
            val offer = RemoteWire.unpackBlobs(readFrame(input), 3) ?: return@withContext
            val (versionBytes, peerEphemeral, peerIdentity) = Triple(offer[0], offer[1], offer[2])
            if (String(versionBytes, Charsets.UTF_8) != PROTOCOL_VERSION) {
                onEvent("A device tried to connect with an incompatible version.")
                return@withContext
            }

            // --- 2. our ephemeral key, signed so the desktop knows this is really the paired phone ---
            val ephemeral = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

            val transcript = Handshake.transcript(
                clientEphemeralSpki = peerEphemeral,
                serverEphemeralSpki = ephemeral.public.encoded,
                clientIdentitySpki = peerIdentity,
                serverIdentitySpki = identitySpki,
            )
            val ourSignature = identity.sign(transcript) ?: return@withContext
            writeFrame(
                output,
                RemoteWire.packBlobs(listOf(ephemeral.public.encoded, identitySpki, ourSignature)),
            )

            // --- 3. the desktop's transcript signature, plus a pairing proof if it is pairing ---
            val confirm = RemoteWire.unpackBlobs(readFrame(input), 2) ?: return@withContext
            val (peerSignature, pairingProof) = confirm[0] to confirm[1]

            if (!verify(transcript, peerSignature, peerIdentity)) {
                onEvent("A device failed to prove its identity.")
                auditRefusalThrottled("badsig", "peer=${fingerprint(peerIdentity)}")
                return@withContext
            }

            val known = peers.isPaired(peerIdentity)
            // Synchronised because verifying the code and consuming it must be one step: two
            // connections presenting the same code concurrently could otherwise both pass before
            // either cleared the window, and "good for exactly one device" would be a lie.
            val pairingOpen: Boolean
            val authorised = synchronized(this) {
                val window = pairing
                pairingOpen = window != null && window.expiresAtMs > System.currentTimeMillis()
                when {
                    known -> true
                    pairingOpen && pairingProof.isNotEmpty() &&
                        PairingProof.verify(window!!.code, transcript, pairingProof) -> {
                        peers.remember(peerIdentity)
                        // A code is good for exactly one device. Leaving the window open after a
                        // successful pair would let anyone else who saw the screen pair too.
                        pairing = null
                        onEvent("Paired with a new computer.")
                        executor.auditPairing(fingerprint(peerIdentity))
                        true
                    }
                    else -> false
                }
            }

            if (!authorised) {
                onEvent(if (pairingOpen) "A device offered the wrong pairing code." else "Refused an unpaired device.")
                auditRefusalThrottled(
                    if (pairingOpen) "badcode" else "unpaired",
                    "peer=${fingerprint(peerIdentity)}",
                )
                return@withContext
            }

            // --- 4. one encrypted command ---
            val secret = RemoteCrypto.sharedSecret(ephemeral.private, peerEphemeral) ?: return@withContext
            val (c2s, s2c) = Handshake.deriveKeys(secret, transcript)

            val plaintext = RemoteCrypto.open(c2s, 0, readFrame(input)) ?: return@withContext
            val request = RemoteRequest.parse(plaintext)
            if (request == null) {
                // Reaching here means an authenticated peer sent something not on the allowlist.
                executor.auditRefusal("badcommand", "peer=${fingerprint(peerIdentity)}")
                writeSealed(output, s2c, RemoteReply(ok = false, payload = "unsupported"))
                return@withContext
            }

            val reply = executor.execute(request, fingerprint(peerIdentity))
            writeSealed(output, s2c, reply)
        }
    }

    private fun writeSealed(output: DataOutputStream, key: ByteArray, reply: RemoteReply) {
        val sealed = RemoteCrypto.seal(key, 0, reply.toBytes()) ?: return
        writeFrame(output, sealed)
    }

    private fun verify(data: ByteArray, signature: ByteArray, spki: ByteArray): Boolean = runCatching {
        val key = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(spki))
        java.security.Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    private fun fingerprint(spki: ByteArray): String =
        RemoteCrypto.sha256(spki).take(4).joinToString(":") { "%02X".format(it) }

    /**
     * Audit a refusal at most once per reason per minute.
     *
     * Refusals happen *before* the peer is authenticated, so without this any device on the Wi-Fi could
     * drive one tamper-evident-ledger write per packet — growing the encrypted blob without bound, and
     * burying the genuine security events the ledger exists to preserve under its own noise.
     */
    private fun auditRefusalThrottled(reason: String, detail: String) {
        val now = System.currentTimeMillis()
        val last = lastRefusalMs[reason]
        if (last != null && now - last < REFUSAL_AUDIT_WINDOW_MS) return
        lastRefusalMs[reason] = now
        executor.auditRefusal(reason, detail)
    }

    private fun writeFrame(out: DataOutputStream, body: ByteArray) {
        out.write(RemoteWire.intToBe(body.size))
        out.write(body)
        out.flush()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        val header = ByteArray(4)
        input.readFully(header)
        val len = RemoteWire.beToInt(header, 0)
        if (len < 0 || len > RemoteWire.MAX_FRAME_BYTES) throw java.io.IOException("Bad frame length: $len")
        return ByteArray(len).also { input.readFully(it) }
    }

    companion object {
        /** Must match the desktop client's default. High and unprivileged. */
        const val DEFAULT_PORT = 8765

        private const val SOCKET_TIMEOUT_MS = 10_000

        /**
         * How many connections may be mid-handshake at once. Each costs a secure-element signature before
         * the peer has proven anything, so without a ceiling any device on the Wi-Fi could turn the phone's
         * own Keystore into a battery drain. One user driving one desktop never approaches this.
         */
        private const val MAX_CONCURRENT = 4

        /** At most one ledger entry per refusal reason per minute — see [auditRefusalThrottled]. */
        private const val REFUSAL_AUDIT_WINDOW_MS = 60_000L

        /** A displayed code stops working after this long, so a glance at the screen is not a lasting key. */
        const val PAIRING_TTL_MS = 2 * 60 * 1000L
    }
}
