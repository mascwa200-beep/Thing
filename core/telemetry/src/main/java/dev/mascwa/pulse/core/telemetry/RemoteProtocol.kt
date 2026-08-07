package dev.mascwa.pulse.core.telemetry

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The pure, CI-tested core of the phone-to-desktop remote-control link — everything that must be
 * byte-identical on both platforms, and everything security-critical, with **no Android types and no
 * serialization dependency** so CI gates it and the desktop can mirror it verbatim.
 *
 * ## Why this shape
 *
 * The link is a plain TCP socket on the local network. Rather than speak HTTP (Android has no built-in
 * server, and hand-rolled HTTP parsing is where bugs live) we own both ends, so frames are simply
 * length-prefixed blobs and the payload inside them is the same unambiguous `<len>:<value>|` field
 * encoding [Canonical] already uses for the audit ledger. That choice buys a real security property:
 * **we sign and encrypt exactly the bytes we transmit**, so there is no separate "canonical form" that
 * could disagree with what went over the wire.
 *
 * ## Trust model (mirrors TLS, deliberately)
 *
 * 1. **Pairing** — a one-time 6-digit code, proven by [PairingProof] (an HMAC over the handshake
 *    transcript). The code itself never crosses the wire, so watching the exchange does not reveal it.
 *    Each side then persists the peer's long-term **public** key; no shared secret is ever stored.
 * 2. **Handshake** — signed ephemeral ECDH ([Handshake]). Each side generates a throwaway P-256 key and
 *    signs it with its long-term identity key (Keystore/StrongBox on the phone), giving both mutual
 *    authentication and forward secrecy: recording today's traffic and stealing the identity key
 *    tomorrow still does not decrypt it.
 * 3. **Records** — AES-256-GCM under a per-direction, per-session key. GCM already authenticates every
 *    record, so per-command ECDSA would be redundant *and* slow (a Keystore signature costs milliseconds);
 *    this is exactly why TLS authenticates once in the handshake and uses AEAD per record thereafter.
 * 4. **Replay** — a fresh ephemeral key per session means a recorded session cannot be replayed into a
 *    new one (the keys differ), and [SequenceGuard] enforces strictly increasing sequence numbers within
 *    a session. Sequence numbers also derive the GCM nonces, so a nonce can never repeat under one key.
 *
 * The commands themselves are a closed [RemoteCommand] enum: the wire format cannot express arbitrary
 * execution — no free-form command strings, no reflection, no shell. That is the single most important
 * safety property here, and it is enforced by parsing, not by convention.
 */

/**
 * Every command the link accepts. A closed set by design — [fromWire] returns null for anything else,
 * so an unrecognized or malicious command name is rejected at parse time rather than dispatched.
 *
 * Deliberately absent, and to stay absent: anything in `DevicePolicyController` / `WifiPolicyController`
 * (arming a wipe, suspending apps, USB/Wi-Fi lockdown — all of which can strand the device or kill the
 * very link needed to undo them), the self-coding flags (they gate code changes and belong to the
 * human-approval gate), and every credential.
 */
enum class RemoteCommand(val wire: String, val mutating: Boolean) {
    /** Liveness probe. Cheap, no side effects. */
    PING("ping", false),

    /** Device readout: battery, charging, version, uptime, which services are live. Never credentials. */
    STATUS("status", false),

    /** Master notification switch (`notifications.masterEnabled`). */
    SET_NOTIFICATIONS("set.notifications", true),

    /** Quiet hours on/off (`notifications.quietHoursEnabled`). */
    SET_QUIET_HOURS("set.quietHours", true),

    /** Full-screen breaking-news takeover (`notifications.breakingInterrupt`). */
    SET_BREAKING_INTERRUPT("set.breakingInterrupt", true),

    /** Live breaking-news polling (`notifications.liveBreakingNews`). */
    SET_LIVE_NEWS("set.liveNews", true),

    /** The resident assistant foreground service (`jarvis.residentService`). */
    SET_ASSISTANT("set.assistant", true),

    /** Wake-word listening (`jarvis.wakeWord`). */
    SET_WAKE_WORD("set.wakeWord", true),

    /** Heart-rate/vitals tracking service (`jarvis.vitalsTracking`). */
    SET_VITALS("set.vitals", true),

    /** Spoken replies (`jarvis.voiceReplies`). */
    SET_VOICE_REPLIES("set.voiceReplies", true),

    /** Run the periodic refresh now (feeds, markets, weather) and republish the brief. */
    REFRESH_NOW("do.refresh", true),

    /** Republish the situation-board notification immediately. */
    SEND_BRIEF("do.brief", true),

    /** Stop radio playback. */
    RADIO_STOP("radio.stop", true),

    /** Start radio playback of the given station id (the argument). */
    RADIO_PLAY("radio.play", true),
    ;

    companion object {
        /** Parse a wire name. Returns null for anything not in the allowlist — never throws. */
        fun fromWire(s: String): RemoteCommand? = entries.firstOrNull { it.wire == s }
    }
}

/**
 * The unambiguous field codec shared by the wire format and every signed transcript.
 *
 * `<len>:<value>|` per field means no two distinct field tuples can encode to the same bytes (so
 * `["ab","c"]` cannot collide with `["a","bc"]`) — the property that makes signing the encoding sound.
 * Same construction as [Canonical], generalized to a field list.
 */
object RemoteWire {

    /** Encode fields to their canonical bytes. */
    fun encode(fields: List<String>): ByteArray {
        val sb = StringBuilder()
        for (f in fields) sb.append(f.length).append(':').append(f).append('|')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Decode canonical bytes back to fields. Returns null on any malformation — never throws. */
    fun decode(bytes: ByteArray): List<String>? = runCatching {
        val s = String(bytes, Charsets.UTF_8)
        val out = ArrayList<String>()
        var i = 0
        while (i < s.length) {
            val colon = s.indexOf(':', i)
            if (colon < 0) return null
            val len = s.substring(i, colon).toIntOrNull() ?: return null
            if (len < 0) return null
            val start = colon + 1
            val end = start + len
            // The terminator must exist, so `end` has to be a real index, not one past the buffer.
            if (end >= s.length || s[end] != '|') return null
            out.add(s.substring(start, end))
            i = end + 1
        }
        out
    }.getOrNull()

    /**
     * Frame a list of binary blobs as `[4-byte BE length][bytes]` each, concatenated. Used to carry the
     * handshake's (key, signature) pairs and a record's ciphertext without needing a parser.
     */
    fun packBlobs(blobs: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (b in blobs) {
            out.write(intToBe(b.size))
            out.write(b)
        }
        return out.toByteArray()
    }

    /** Inverse of [packBlobs]. Returns null on truncation or a length that overruns the buffer. */
    fun unpackBlobs(bytes: ByteArray, expected: Int): List<ByteArray>? {
        val out = ArrayList<ByteArray>(expected)
        var i = 0
        while (i < bytes.size) {
            if (i + 4 > bytes.size) return null
            val len = beToInt(bytes, i)
            if (len < 0 || i + 4 + len > bytes.size) return null
            out.add(bytes.copyOfRange(i + 4, i + 4 + len))
            i += 4 + len
        }
        return if (out.size == expected) out else null
    }

    /** Big-endian 4-byte length prefix — the on-socket frame header. */
    fun intToBe(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** Read a big-endian 4-byte int at [offset]. */
    fun beToInt(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)

    /** Largest frame we will ever read off the socket — a hostile peer cannot make us allocate wildly. */
    const val MAX_FRAME_BYTES = 1 shl 20
}

/** A command sent to the controlled device. [arg] carries the boolean/station/etc. as text; "" when unused. */
data class RemoteRequest(val command: RemoteCommand, val arg: String = "") {
    fun toBytes(): ByteArray = RemoteWire.encode(listOf(PROTOCOL_VERSION, command.wire, arg))

    companion object {
        /** Parse a request. Null if malformed, wrong protocol version, or the command is not allowlisted. */
        fun parse(bytes: ByteArray): RemoteRequest? {
            val f = RemoteWire.decode(bytes) ?: return null
            if (f.size != 3 || f[0] != PROTOCOL_VERSION) return null
            val cmd = RemoteCommand.fromWire(f[1]) ?: return null
            return RemoteRequest(cmd, f[2])
        }
    }
}

/** The controlled device's answer. [payload] is a field-encoded key/value list for [STATUS], else "". */
data class RemoteReply(val ok: Boolean, val payload: String = "") {
    fun toBytes(): ByteArray =
        RemoteWire.encode(listOf(PROTOCOL_VERSION, if (ok) "1" else "0", payload))

    companion object {
        fun parse(bytes: ByteArray): RemoteReply? {
            val f = RemoteWire.decode(bytes) ?: return null
            if (f.size != 3 || f[0] != PROTOCOL_VERSION) return null
            return RemoteReply(f[1] == "1", f[2])
        }
    }
}

/** Bumped only on a breaking wire change; both ends refuse to talk across a mismatch. */
const val PROTOCOL_VERSION: String = "lcars-remote-1"

/**
 * Enforces strictly increasing record sequence numbers within a session, which both rejects replayed
 * records and guarantees the GCM nonce (derived from the sequence) can never repeat under one key.
 */
class SequenceGuard {
    private var last: Long = -1

    /** True if [seq] is the next acceptable value (strictly greater than everything seen). */
    fun accept(seq: Long): Boolean {
        if (seq <= last || seq < 0) return false
        last = seq
        return true
    }

    val highest: Long get() = last
}

/**
 * The cryptographic primitives, all JCA so they behave identically on Android and the JVM.
 *
 * Every function is defensive: malformed input yields null rather than throwing, so a hostile peer can
 * never crash the listener with a malformed frame.
 */
object RemoteCrypto {

    private const val HMAC = "HmacSHA256"
    private const val GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val KEY_BYTES = 32

    /** Info label for the client→server record key. Distinct keys per direction, so nonces cannot collide. */
    const val INFO_C2S = "lcars-remote c2s"

    /** Info label for the server→client record key. */
    const val INFO_S2C = "lcars-remote s2c"

    /** Info label for the pairing-code proof. */
    const val INFO_PAIR = "lcars-remote pairing"

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key, HMAC))
            doFinal(data)
        }

    /**
     * HKDF (RFC 5869) extract-then-expand over SHA-256, producing a 32-byte key. Used to turn the raw
     * ECDH shared secret into independent per-direction record keys.
     *
     * Per RFC 5869 §2.2 an absent salt is defined as HashLen zero bytes; JCA would otherwise reject the
     * empty HMAC key outright, so callers that legitimately have no salt (the pairing proof) still work.
     */
    fun hkdf(secret: ByteArray, salt: ByteArray, info: String, length: Int = KEY_BYTES): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, secret)
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            val mac = Mac.getInstance(HMAC)
            mac.init(SecretKeySpec(prk, HMAC))
            mac.update(t)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** ECDH shared secret from our private key and the peer's X.509/SPKI public key. Null if unusable. */
    fun sharedSecret(privateKey: java.security.PrivateKey, peerSpki: ByteArray): ByteArray? = runCatching {
        val peer: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerSpki))
        KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(peer, true)
            generateSecret()
        }
    }.getOrNull()

    /** 12-byte GCM nonce derived from the record sequence number. Unique per key by construction. */
    fun nonceFor(seq: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (seq ushr (56 - 8 * i)).toByte()
        return n
    }

    /** AES-256-GCM encrypt. [seq] must never repeat for a given [key]. */
    fun seal(key: ByteArray, seq: Long, plaintext: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance(GCM).run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonceFor(seq)))
            doFinal(plaintext)
        }
    }.getOrNull()

    /** AES-256-GCM decrypt. Null on a bad tag, wrong key, or truncated input — the authenticity check. */
    fun open(key: ByteArray, seq: Long, ciphertext: ByteArray): ByteArray? = runCatching {
        Cipher.getInstance(GCM).run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonceFor(seq)))
            doFinal(ciphertext)
        }
    }.getOrNull()
}

/**
 * The handshake transcript and the keys derived from it.
 *
 * Both sides build the *same* transcript bytes from the two ephemeral public keys (client first, always,
 * so the ordering is unambiguous) plus both identity keys. Each side signs that transcript with its
 * long-term identity key, so a man-in-the-middle who swaps an ephemeral key produces a transcript whose
 * signature will not verify.
 */
object Handshake {

    /** Canonical transcript over the handshake's public values. Identical on both sides by construction. */
    fun transcript(
        clientEphemeralSpki: ByteArray,
        serverEphemeralSpki: ByteArray,
        clientIdentitySpki: ByteArray,
        serverIdentitySpki: ByteArray,
    ): ByteArray = RemoteWire.encode(
        listOf(
            PROTOCOL_VERSION,
            b64(clientEphemeralSpki),
            b64(serverEphemeralSpki),
            b64(clientIdentitySpki),
            b64(serverIdentitySpki),
        ),
    )

    /**
     * Derive the two record keys from the ECDH secret, salted by the transcript hash so the keys are
     * bound to the exact identities and ephemeral keys that were exchanged.
     */
    fun deriveKeys(sharedSecret: ByteArray, transcript: ByteArray): Pair<ByteArray, ByteArray> {
        val salt = RemoteCrypto.sha256(transcript)
        return RemoteCrypto.hkdf(sharedSecret, salt, RemoteCrypto.INFO_C2S) to
            RemoteCrypto.hkdf(sharedSecret, salt, RemoteCrypto.INFO_S2C)
    }

    /** Base64 without padding/wrapping — java.util.Base64 is present on Android API 26+ and every JVM. */
    fun b64(b: ByteArray): String = java.util.Base64.getEncoder().withoutPadding().encodeToString(b)

    /** Decode a [b64] string; null if it is not valid Base64. */
    fun unb64(s: String): ByteArray? = runCatching { java.util.Base64.getDecoder().decode(s) }.getOrNull()
}

/**
 * The one-time pairing proof. The desktop must show it knows the 6-digit code the phone is displaying,
 * without transmitting it — so an eavesdropper on the LAN learns nothing they could reuse.
 *
 * Proof = HMAC(code, transcript). Verified with a constant-time comparison so a wrong code leaks no
 * timing information about how many bytes matched.
 */
object PairingProof {

    /** Codes are 6 digits: short enough to read off a screen, and only ever usable once, briefly. */
    const val CODE_LENGTH = 6

    /** A fresh zero-padded 6-digit code. */
    fun newCode(): String {
        val n = java.nio.ByteBuffer.wrap(RemoteCrypto.randomBytes(4)).int.toLong() and 0xFFFFFFFFL
        return (n % 1_000_000L).toString().padStart(CODE_LENGTH, '0')
    }

    fun proof(code: String, transcript: ByteArray): ByteArray =
        RemoteCrypto.hmac(
            RemoteCrypto.hkdf(code.toByteArray(Charsets.UTF_8), ByteArray(0), RemoteCrypto.INFO_PAIR),
            transcript,
        )

    /** Constant-time verification — never short-circuits on the first differing byte. */
    fun verify(code: String, transcript: ByteArray, presented: ByteArray): Boolean {
        val expected = proof(code, transcript)
        if (expected.size != presented.size) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].toInt() xor presented[i].toInt())
        return diff == 0
    }
}

/**
 * Whether a peer address is on a local network. Defence in depth: even if a router were misconfigured to
 * forward the listening port, a connection from outside the LAN is refused before any handshake work.
 * Accepts RFC-1918 private ranges, link-local, and loopback.
 */
object LocalNetwork {
    fun isLocalAddress(hostAddress: String): Boolean {
        var h = hostAddress.substringBefore('%')  // strip IPv6 zone index
        // A dual-stack listening socket often reports an IPv4 peer in IPv4-mapped form
        // ("::ffff:192.168.1.5"). Unwrapping it first means an ordinary IPv4 client on an
        // IPv6-preferring network isn't refused as "outside the local network".
        if (h.startsWith("::ffff:", ignoreCase = true) && h.count { it == '.' } == 3) {
            h = h.substring(7)
        }
        if (h == "::1" || h.startsWith("127.")) return true
        if (h.startsWith("fe80:", ignoreCase = true) || h.startsWith("fc", ignoreCase = true) ||
            h.startsWith("fd", ignoreCase = true)
        ) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        if (parts[2].toIntOrNull() == null || parts[3].toIntOrNull() == null) return false
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }
}
