package dev.mascwa.pulse.core.telemetry

/**
 * Pure, dependency-free parser + verdict for Android Keystore **key attestation** — the hardware-rooted
 * way to prove a device's identity instead of guessing it.
 *
 * The Android side generates a key in the device's secure hardware (Titan M2 on a Pixel) with an
 * attestation challenge, then reads the leaf certificate's attestation extension (OID
 * `1.3.6.1.4.1.11129.2.1.17`). That extension is a DER-encoded `KeyDescription` that carries, in its
 * `RootOfTrust`, the **verified-boot state**, whether the **bootloader is locked**, and the
 * **verified-boot key** (a fingerprint of the OS signing key). On a Pixel running GrapheneOS with a locked
 * bootloader, verified boot is `Verified` and the verified-boot key is GrapheneOS's — so checking those
 * values cryptographically establishes "genuine Pixel, locked, real GrapheneOS", which the spoofable
 * presence-of-apps heuristic cannot.
 *
 * Kept Android-free so the (fiddly, security-load-bearing) ASN.1/DER navigation is unit-tested in CI
 * against synthetic records. Everything is defensive: any malformation returns null rather than throwing.
 */
object HardwareAttestation {

    enum class SecurityLevel { SOFTWARE, TRUSTED_ENVIRONMENT, STRONGBOX, UNKNOWN }

    enum class BootState { VERIFIED, SELF_SIGNED, UNVERIFIED, FAILED, UNKNOWN }

    /** The salient fields lifted out of a `KeyDescription` attestation record. */
    data class Info(
        val attestationSecurityLevel: SecurityLevel,
        val keymasterSecurityLevel: SecurityLevel,
        val verifiedBootState: BootState,
        val deviceLocked: Boolean?,
        /** Hex of the verified-boot key (the OS signing-key fingerprint) — what identifies GrapheneOS. */
        val verifiedBootKeyHex: String?,
        val verifiedBootHashHex: String?,
    )

    /** A human-readable judgement over an [Info], optionally checking the verified-boot key against a set
     *  of known-GrapheneOS keys. [grapheneKeyMatch] is null when there's nothing to compare against yet. */
    data class Verdict(
        val hardwareBacked: Boolean,
        val strongBox: Boolean,
        val bootloaderLocked: Boolean,
        val verifiedBoot: Boolean,
        val grapheneKeyMatch: Boolean?,
        /** The full GrapheneOS pass: hardware-backed, bootloader locked, verified-boot key matches a known
         *  GrapheneOS key, and boot state is Self-signed (the GrapheneOS norm) or Verified. */
        val grapheneVerified: Boolean,
        val summary: String,
    )

    /**
     * Known GrapheneOS verified-boot keys (hex, lowercase), per device, to match against. Read off genuine
     * hardware — never fabricated (gating on a wrong fingerprint would lock the owner out). On GrapheneOS
     * the bootloader is re-locked against GrapheneOS's own AVB key, so attestation reports verified-boot
     * state **Self-signed** (not Verified) plus this key — that combination is the real signature of
     * GrapheneOS, which the verdict treats as a pass.
     *  - Pixel 10 Pro XL ("mustang"): read off the owner's provisioned device, 2026-06.
     */
    val GRAPHENE_VERIFIED_BOOT_KEYS: Set<String> = setOf(
        "141d7fc32af7958a416f2661b37cf6f27bfb376fb5ce616aeaa27a82c7a04f74",
    )

    // The rootOfTrust field is context-tag [704] inside an AuthorizationList.
    private const val TAG_ROOT_OF_TRUST = 704

    /**
     * Parse the DER bytes of a `KeyDescription` (the inner value of the attestation extension, i.e. with
     * the outer extension OCTET STRING already stripped). Returns null on any malformation.
     */
    fun parse(keyDescription: ByteArray): Info? = try {
        val top = readTlv(keyDescription, 0)
        if (top == null || !top.constructed) {
            null
        } else {
            val children = readChildren(keyDescription, top)
            // KeyDescription ::= SEQUENCE { attestationVersion INT, attestationSecurityLevel ENUM,
            //   keymasterVersion INT, keymasterSecurityLevel ENUM, attestationChallenge OCT, uniqueId OCT,
            //   softwareEnforced AuthorizationList, teeEnforced AuthorizationList }
            if (children == null || children.size < 8) {
                null
            } else {
                val attLevel = securityLevelOf(smallInt(keyDescription, children[1]))
                val kmLevel = securityLevelOf(smallInt(keyDescription, children[3]))
                // rootOfTrust normally lives in teeEnforced; fall back to softwareEnforced just in case.
                val rot = findRootOfTrust(keyDescription, children[7])
                    ?: findRootOfTrust(keyDescription, children[6])
                if (rot == null) {
                    Info(attLevel, kmLevel, BootState.UNKNOWN, null, null, null)
                } else {
                    Info(attLevel, kmLevel, rot.state, rot.locked, rot.keyHex, rot.hashHex)
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    fun verdict(info: Info, knownGrapheneVerifiedBootKeys: Set<String> = GRAPHENE_VERIFIED_BOOT_KEYS): Verdict {
        val hardware = info.attestationSecurityLevel == SecurityLevel.TRUSTED_ENVIRONMENT ||
            info.attestationSecurityLevel == SecurityLevel.STRONGBOX
        val strongBox = info.attestationSecurityLevel == SecurityLevel.STRONGBOX ||
            info.keymasterSecurityLevel == SecurityLevel.STRONGBOX
        val locked = info.deviceLocked == true
        val verifiedBoot = info.verifiedBootState == BootState.VERIFIED
        val key = info.verifiedBootKeyHex?.lowercase()
        val match: Boolean? = if (knownGrapheneVerifiedBootKeys.isEmpty() || key == null) null
        else key in knownGrapheneVerifiedBootKeys.map { it.lowercase() }
        // GrapheneOS re-locks the bootloader against its OWN key → attestation reports Self-signed (not
        // Verified). The genuine-GrapheneOS signal is therefore: hardware + locked + key match + a boot
        // state of Self-signed or Verified. (Requiring Verified alone would wrongly reject GrapheneOS.)
        val grapheneVerified = hardware && locked && match == true &&
            (info.verifiedBootState == BootState.SELF_SIGNED || info.verifiedBootState == BootState.VERIFIED)

        val summary = buildString {
            append(if (hardware) (if (strongBox) "StrongBox (secure element)" else "Hardware (TEE)") else "Software-only")
            append(" attestation · bootloader ").append(if (locked) "locked" else "UNLOCKED")
            append(" · verified boot ").append(
                when (info.verifiedBootState) {
                    BootState.VERIFIED -> "Verified"
                    BootState.SELF_SIGNED -> "Self-signed"
                    BootState.UNVERIFIED -> "Unverified"
                    BootState.FAILED -> "Failed"
                    BootState.UNKNOWN -> "unknown"
                },
            )
            append(
                when {
                    grapheneVerified -> " · genuine GrapheneOS ✓ (re-locked with GrapheneOS's key)"
                    match == true -> " · GrapheneOS key MATCH ✓"
                    match == false -> " · GrapheneOS key mismatch ✗"
                    else -> " · GrapheneOS key not yet on file (report the key below)"
                },
            )
        }
        return Verdict(hardware, strongBox, locked, verifiedBoot, match, grapheneVerified, summary)
    }

    // --- minimal DER reader ----------------------------------------------------------------------

    private data class Tlv(
        val tagClass: Int,        // 0=universal 1=application 2=context 3=private
        val constructed: Boolean,
        val tagNumber: Int,
        val contentOff: Int,
        val contentLen: Int,
    ) {
        val end: Int get() = contentOff + contentLen
    }

    /** Read one tag-length-value triple at [off]. Handles high-tag-number and long-form lengths; returns
     *  null on truncation or unsupported (indefinite-length) encodings. */
    private fun readTlv(b: ByteArray, off: Int): Tlv? {
        if (off < 0 || off >= b.size) return null
        var p = off
        val first = b[p].toInt() and 0xFF; p++
        val tagClass = (first ushr 6) and 0x03
        val constructed = (first and 0x20) != 0
        var tagNumber = first and 0x1F
        if (tagNumber == 0x1F) {
            tagNumber = 0
            var done = false
            var guard = 0
            while (p < b.size && guard < 5) {
                val v = b[p].toInt() and 0xFF; p++; guard++
                tagNumber = (tagNumber shl 7) or (v and 0x7F)
                if (v and 0x80 == 0) { done = true; break }
            }
            if (!done) return null
        }
        if (p >= b.size) return null
        val lenFirst = b[p].toInt() and 0xFF; p++
        val length: Int
        if (lenFirst < 0x80) {
            length = lenFirst
        } else {
            val numBytes = lenFirst and 0x7F
            if (numBytes == 0 || numBytes > 4) return null // indefinite or implausibly large
            var acc = 0
            for (i in 0 until numBytes) {
                if (p >= b.size) return null
                acc = (acc shl 8) or (b[p].toInt() and 0xFF); p++
            }
            if (acc < 0) return null
            length = acc
        }
        if (length < 0 || p + length > b.size) return null
        return Tlv(tagClass, constructed, tagNumber, p, length)
    }

    private fun readChildren(b: ByteArray, parent: Tlv): List<Tlv>? {
        val out = ArrayList<Tlv>()
        var p = parent.contentOff
        var guard = 0
        while (p < parent.end && guard < 4096) {
            val t = readTlv(b, p) ?: return null
            out.add(t)
            if (t.end <= p) return null // no forward progress → malformed
            p = t.end
            guard++
        }
        return out
    }

    private data class Rot(val keyHex: String?, val locked: Boolean?, val state: BootState, val hashHex: String?)

    private fun findRootOfTrust(b: ByteArray, authList: Tlv): Rot? {
        if (!authList.constructed) return null
        val children = readChildren(b, authList) ?: return null
        val field = children.firstOrNull { it.tagClass == 2 && it.tagNumber == TAG_ROOT_OF_TRUST } ?: return null
        // The field is EXPLICIT-tagged: its content is the RootOfTrust SEQUENCE.
        val inner = readTlv(b, field.contentOff) ?: return null
        if (!inner.constructed) return null
        val rc = readChildren(b, inner) ?: return null
        if (rc.size < 3) return null
        // RootOfTrust ::= SEQUENCE { verifiedBootKey OCT, deviceLocked BOOL, verifiedBootState ENUM,
        //   verifiedBootHash OCT (KeyMint v3+) }
        val keyHex = octetHex(b, rc[0])
        val locked = boolOf(b, rc[1])
        val state = bootStateOf(smallInt(b, rc[2]))
        val hashHex = if (rc.size >= 4) octetHex(b, rc[3]) else null
        return Rot(keyHex, locked, state, hashHex)
    }

    private fun smallInt(b: ByteArray, t: Tlv): Int? {
        if (t.contentLen <= 0 || t.contentLen > 4) return null
        var v = 0
        for (i in 0 until t.contentLen) v = (v shl 8) or (b[t.contentOff + i].toInt() and 0xFF)
        return v
    }

    private fun boolOf(b: ByteArray, t: Tlv): Boolean? {
        if (t.contentLen != 1) return null
        return (b[t.contentOff].toInt() and 0xFF) != 0
    }

    private fun octetHex(b: ByteArray, t: Tlv): String? {
        if (t.contentLen <= 0) return null
        val hex = "0123456789abcdef"
        val sb = StringBuilder(t.contentLen * 2)
        for (i in 0 until t.contentLen) {
            val x = b[t.contentOff + i].toInt() and 0xFF
            sb.append(hex[x ushr 4]).append(hex[x and 0x0F])
        }
        return sb.toString()
    }

    private fun securityLevelOf(v: Int?): SecurityLevel = when (v) {
        0 -> SecurityLevel.SOFTWARE
        1 -> SecurityLevel.TRUSTED_ENVIRONMENT
        2 -> SecurityLevel.STRONGBOX
        else -> SecurityLevel.UNKNOWN
    }

    private fun bootStateOf(v: Int?): BootState = when (v) {
        0 -> BootState.VERIFIED
        1 -> BootState.SELF_SIGNED
        2 -> BootState.UNVERIFIED
        3 -> BootState.FAILED
        else -> BootState.UNKNOWN
    }
}
