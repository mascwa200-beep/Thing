package dev.mascwa.pulse.core.telemetry

import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * RFC-3161 Time-Stamp Protocol helpers — the pure, CI-tested core. A trusted timestamp is **independent
 * proof-of-time**: a TSA signs "this hash existed at this instant", which a device's own clock can't
 * establish on its own. Anchoring the audit ledger's head to a TSA means the whole chain provably existed
 * at/before the TSA's time, so a later forger can't back-date a rewritten ledger.
 *
 * [buildTimeStampQuery] produces the DER `TimeStampReq` to POST to a TSA (`application/timestamp-query`);
 * [parseResponse] defensively reads back the `TimeStampResp` — the PKI status, the raw `timeStampToken`
 * (the cryptographic proof, persisted verbatim as the anchor), and the asserted `genTime`.
 *
 * DER is built/parsed by hand (mirrors [HardwareAttestation]); no Android types, so CI gates it. The
 * Android TSA fetch + the store wiring live in the app module.
 */
object Rfc3161 {

    /** PKIStatus values (RFC-3161 §2.4.2). A token is present for granted / grantedWithMods. */
    const val STATUS_GRANTED = 0
    const val STATUS_GRANTED_WITH_MODS = 1

    /** OID 2.16.840.1.101.3.4.2.1 (sha-256), content octets only. */
    private val SHA256_OID = byteArrayOf(0x60, 0x86.toByte(), 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01)

    private const val TAG_INTEGER = 0x02
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_NULL = 0x05
    private const val TAG_OID = 0x06
    private const val TAG_BOOLEAN = 0x01
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_GENERALIZED_TIME = 0x18

    /** The DER-encoded result + a parsed view. [token] is the raw timeStampToken to persist as the anchor. */
    data class StampToken(
        val status: Int,
        val token: ByteArray?,
        val genTimeMs: Long?,
    ) {
        val granted: Boolean get() = status == STATUS_GRANTED || status == STATUS_GRANTED_WITH_MODS
    }

    // ---- request ----------------------------------------------------------------------------------

    /**
     * Build a DER `TimeStampReq` over a SHA-256 [imprint] (must be 32 bytes). [nonce] (optional, raw bytes)
     * lets the caller match the response; [certReq] asks the TSA to include its cert in the token.
     *
     *   TimeStampReq ::= SEQUENCE { version INTEGER(1), messageImprint MessageImprint,
     *                               nonce INTEGER OPTIONAL, certReq BOOLEAN DEFAULT FALSE }
     *   MessageImprint ::= SEQUENCE { hashAlgorithm AlgorithmIdentifier, hashedMessage OCTET STRING }
     */
    fun buildTimeStampQuery(imprint: ByteArray, nonce: ByteArray? = null, certReq: Boolean = true): ByteArray {
        val algId = der(TAG_SEQUENCE, der(TAG_OID, SHA256_OID) + der(TAG_NULL, ByteArray(0)))
        val messageImprint = der(TAG_SEQUENCE, algId + der(TAG_OCTET_STRING, imprint))

        var body = der(TAG_INTEGER, byteArrayOf(1)) + messageImprint // version 1
        if (nonce != null && nonce.isNotEmpty()) body += der(TAG_INTEGER, positiveInt(nonce))
        if (certReq) body += der(TAG_BOOLEAN, byteArrayOf(0xFF.toByte())) // omit when false (DER default)
        return der(TAG_SEQUENCE, body)
    }

    // ---- response ---------------------------------------------------------------------------------

    /**
     * Parse a DER `TimeStampResp`. Returns the PKI status, the raw timeStampToken (or null when the TSA
     * refused), and the genTime in epoch millis (or null if it couldn't be read). Fully defensive → null
     * on any malformation.
     *
     *   TimeStampResp ::= SEQUENCE { status PKIStatusInfo, timeStampToken ContentInfo OPTIONAL }
     *   PKIStatusInfo ::= SEQUENCE { status INTEGER, ... }
     */
    fun parseResponse(der: ByteArray): StampToken? = runCatching {
        val root = readTlv(der, 0) ?: return null
        if (root.tag != TAG_SEQUENCE) return null
        val top = readChildren(der, root) ?: return null
        val statusInfo = top.getOrNull(0)?.takeIf { it.tag == TAG_SEQUENCE } ?: return null
        val statusTlv = readChildren(der, statusInfo)?.firstOrNull { it.tag == TAG_INTEGER } ?: return null
        val status = smallInt(der, statusTlv) ?: return null

        // The timeStampToken (a CMS ContentInfo SEQUENCE) is the next top-level element, if any.
        val tokenTlv = top.getOrNull(1)?.takeIf { it.tag == TAG_SEQUENCE }
        val token = tokenTlv?.let { der.copyOfRange(it.headerOff, it.contentOff + it.contentLen) }
        // TSTInfo.genTime is the first GeneralizedTime in the token (it precedes any signed-attr time).
        val genTimeMs = token?.let { firstGeneralizedTime(it)?.let(::parseGeneralizedTime) }

        StampToken(status, token, genTimeMs)
    }.getOrNull()

    /** Parse an RFC-3161 GeneralizedTime ("YYYYMMDDHHMMSS[.f]Z", UTC) to epoch millis; null if malformed. */
    fun parseGeneralizedTime(s: String): Long? = runCatching {
        val core = s.trim().removeSuffix("Z").substringBefore('.')
        if (core.length < 14) return null
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                core.substring(0, 4).toInt(),
                core.substring(4, 6).toInt() - 1,
                core.substring(6, 8).toInt(),
                core.substring(8, 10).toInt(),
                core.substring(10, 12).toInt(),
                core.substring(12, 14).toInt(),
            )
        }
        cal.timeInMillis
    }.getOrNull()

    // ---- DER encode -------------------------------------------------------------------------------

    private fun der(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + lenBytes(content.size) + content

    private fun lenBytes(len: Int): ByteArray {
        if (len < 0x80) return byteArrayOf(len.toByte())
        val out = ArrayList<Byte>(5)
        var v = len
        while (v > 0) { out.add(0, (v and 0xFF).toByte()); v = v ushr 8 }
        return byteArrayOf((0x80 or out.size).toByte()) + out.toByteArray()
    }

    /** Ensure a raw big-endian magnitude encodes as a positive INTEGER (prepend 0x00 if the top bit is set). */
    private fun positiveInt(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size && bytes[start] == 0.toByte()) start++
        val trimmed = if (start >= bytes.size) byteArrayOf(0) else bytes.copyOfRange(start, bytes.size)
        return if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
    }

    // ---- DER decode (mirrors HardwareAttestation's TLV reader) ------------------------------------

    private data class Tlv(val tag: Int, val headerOff: Int, val contentOff: Int, val contentLen: Int) {
        val end: Int get() = contentOff + contentLen
        val constructed: Boolean get() = (tag and 0x20) != 0
    }

    private fun readTlv(b: ByteArray, off: Int): Tlv? {
        if (off >= b.size) return null
        var p = off
        val first = b[p].toInt() and 0xFF; p++
        // Multi-byte tags are unused by the structures we read; bail out defensively.
        if (first and 0x1F == 0x1F) return null
        if (p >= b.size) return null
        val lenFirst = b[p].toInt() and 0xFF; p++
        val contentLen: Int
        if (lenFirst < 0x80) {
            contentLen = lenFirst
        } else {
            val n = lenFirst and 0x7F
            if (n == 0 || n > 4 || p + n > b.size) return null
            var acc = 0
            for (i in 0 until n) { acc = (acc shl 8) or (b[p].toInt() and 0xFF); p++ }
            contentLen = acc
        }
        if (contentLen < 0 || p + contentLen > b.size) return null
        return Tlv(first, off, p, contentLen)
    }

    private fun readChildren(b: ByteArray, parent: Tlv): List<Tlv>? {
        if (!parent.constructed) return null
        val out = ArrayList<Tlv>()
        var p = parent.contentOff
        val end = parent.end
        var guard = 0
        while (p < end && guard < 4096) {
            val t = readTlv(b, p) ?: return null
            out.add(t)
            p = t.end
            guard++
        }
        return out
    }

    private fun smallInt(b: ByteArray, t: Tlv): Int? {
        if (t.contentLen == 0 || t.contentLen > 4) return null
        var v = 0
        for (i in 0 until t.contentLen) v = (v shl 8) or (b[t.contentOff + i].toInt() and 0xFF)
        return v
    }

    /** Depth-first scan for the first GeneralizedTime primitive; returns its ASCII string content. */
    private fun firstGeneralizedTime(b: ByteArray, off: Int = 0, end: Int = b.size, depth: Int = 0): String? {
        if (depth > 32) return null
        var p = off
        var guard = 0
        while (p < end && guard < 8192) {
            val t = readTlv(b, p) ?: return null
            if (t.tag == TAG_GENERALIZED_TIME && !t.constructed) {
                return String(b, t.contentOff, t.contentLen, Charsets.US_ASCII)
            }
            // Descend into constructed elements, and into an OCTET STRING that wraps a SEQUENCE — the CMS
            // eContent carries the TSTInfo (and its genTime) inside such an octet string.
            val descend = t.constructed ||
                (t.tag == TAG_OCTET_STRING && t.contentLen > 1 && (b[t.contentOff].toInt() and 0xFF) == TAG_SEQUENCE)
            if (descend) {
                firstGeneralizedTime(b, t.contentOff, t.end, depth + 1)?.let { return it }
            }
            p = t.end
            guard++
        }
        return null
    }
}
