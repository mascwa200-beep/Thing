package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the DER navigation in [HardwareAttestation] against synthetic `KeyDescription` records we
 * encode here — including the high-tag-number `[704]` rootOfTrust field and a long-form length — since CI
 * has no real attestation certificate. If the parser drifts, these break.
 */
class HardwareAttestationTest {

    // --- tiny DER encoders (test-only) -----------------------------------------------------------

    private fun len(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), (n and 0xFF).toByte())
    }

    private fun tlv(tag: ByteArray, content: ByteArray): ByteArray = tag + len(content.size) + content

    private fun int(v: Int): ByteArray = tlv(byteArrayOf(0x02), byteArrayOf(v.toByte()))
    private fun enumerated(v: Int): ByteArray = tlv(byteArrayOf(0x0A), byteArrayOf(v.toByte()))
    private fun octet(bytes: ByteArray): ByteArray = tlv(byteArrayOf(0x04), bytes)
    private fun bool(b: Boolean): ByteArray = tlv(byteArrayOf(0x01), byteArrayOf(if (b) 0xFF.toByte() else 0x00))
    private fun seq(vararg parts: ByteArray): ByteArray = tlv(byteArrayOf(0x30), parts.fold(ByteArray(0)) { a, b -> a + b })

    /** Context-specific, constructed, EXPLICIT field with the given tag number (handles high-tag form). */
    private fun ctxExplicit(tagNumber: Int, content: ByteArray): ByteArray {
        val tag: ByteArray = if (tagNumber < 0x1F) {
            byteArrayOf((0xA0 or tagNumber).toByte())
        } else {
            // 0xBF = context(10) + constructed(1) + 11111; then base-128 with continuation bits.
            val groups = ArrayList<Int>()
            var n = tagNumber
            groups.add(0, n and 0x7F)
            n = n ushr 7
            while (n > 0) { groups.add(0, (n and 0x7F) or 0x80); n = n ushr 7 }
            byteArrayOf(0xBF.toByte()) + groups.map { it.toByte() }.toByteArray()
        }
        return tlv(tag, content)
    }

    private fun rootOfTrust(keyBytes: ByteArray, locked: Boolean, state: Int, hash: ByteArray?): ByteArray =
        if (hash == null) seq(octet(keyBytes), bool(locked), enumerated(state))
        else seq(octet(keyBytes), bool(locked), enumerated(state), octet(hash))

    private fun keyDescription(
        attLevel: Int, kmLevel: Int, rot: ByteArray?, rotInSoftware: Boolean = false,
    ): ByteArray {
        val rotField = rot?.let { ctxExplicit(704, it) } ?: ByteArray(0)
        val software = if (rotInSoftware && rot != null) seq(rotField) else seq()
        val tee = if (!rotInSoftware && rot != null) seq(rotField) else seq()
        return seq(
            int(4),                 // attestationVersion
            enumerated(attLevel),   // attestationSecurityLevel
            int(4),                 // keymasterVersion
            enumerated(kmLevel),    // keymasterSecurityLevel
            octet(byteArrayOf(1, 2, 3, 4)), // challenge
            octet(ByteArray(0)),    // uniqueId
            software,               // softwareEnforced
            tee,                    // teeEnforced
        )
    }

    // --- tests -----------------------------------------------------------------------------------

    @Test
    fun parsesStrongBoxVerifiedLockedRecord() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val record = keyDescription(
            attLevel = 2, kmLevel = 2,
            rot = rootOfTrust(key, locked = true, state = 0, hash = ByteArray(32) { 0x55 }),
        )
        val info = HardwareAttestation.parse(record)
        assertNotNull(info)
        info!!
        assertEquals(HardwareAttestation.SecurityLevel.STRONGBOX, info.attestationSecurityLevel)
        assertEquals(HardwareAttestation.BootState.VERIFIED, info.verifiedBootState)
        assertEquals(true, info.deviceLocked)
        assertEquals("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20", info.verifiedBootKeyHex)
        assertEquals(64, info.verifiedBootHashHex!!.length)

        val v = HardwareAttestation.verdict(info, emptySet())
        assertTrue(v.hardwareBacked)
        assertTrue(v.strongBox)
        assertTrue(v.bootloaderLocked)
        assertTrue(v.verifiedBoot)
        assertNull(v.grapheneKeyMatch) // no known keys on file → "unknown"
    }

    @Test
    fun verdictMatchesAKnownGrapheneKey() {
        val key = ByteArray(32) { 0x7A }
        val record = keyDescription(2, 1, rootOfTrust(key, true, 0, null))
        val info = HardwareAttestation.parse(record)!!
        val keyHex = info.verifiedBootKeyHex!!
        assertEquals(true, HardwareAttestation.verdict(info, setOf(keyHex.uppercase())).grapheneKeyMatch)
        assertEquals(false, HardwareAttestation.verdict(info, setOf("deadbeef")).grapheneKeyMatch)
    }

    @Test
    fun unlockedUnverifiedRecordIsFlagged() {
        val record = keyDescription(1, 1, rootOfTrust(ByteArray(4), locked = false, state = 2, hash = null))
        val info = HardwareAttestation.parse(record)!!
        assertEquals(HardwareAttestation.BootState.UNVERIFIED, info.verifiedBootState)
        assertEquals(false, info.deviceLocked)
        val v = HardwareAttestation.verdict(info, emptySet())
        assertFalse(v.bootloaderLocked)
        assertFalse(v.verifiedBoot)
        assertTrue(v.hardwareBacked) // TEE attestation, just not verified-boot
    }

    @Test
    fun findsRootOfTrustInSoftwareEnforcedFallback() {
        val record = keyDescription(1, 1, rootOfTrust(ByteArray(2) { 9 }, true, 0, null), rotInSoftware = true)
        val info = HardwareAttestation.parse(record)!!
        assertEquals(HardwareAttestation.BootState.VERIFIED, info.verifiedBootState)
        assertEquals("0909", info.verifiedBootKeyHex)
    }

    @Test
    fun recordWithoutRootOfTrustParsesWithUnknownBoot() {
        val record = keyDescription(2, 2, rot = null)
        val info = HardwareAttestation.parse(record)!!
        assertEquals(HardwareAttestation.SecurityLevel.STRONGBOX, info.attestationSecurityLevel)
        assertEquals(HardwareAttestation.BootState.UNKNOWN, info.verifiedBootState)
        assertNull(info.verifiedBootKeyHex)
    }

    @Test
    fun longFormLengthIsHandled() {
        // A 200-byte verified-boot key forces long-form (0x81) length encoding in the OCTET STRING.
        val key = ByteArray(200) { (it and 0x7F).toByte() }
        val record = keyDescription(2, 2, rootOfTrust(key, true, 0, null))
        val info = HardwareAttestation.parse(record)!!
        assertEquals(400, info.verifiedBootKeyHex!!.length)
    }

    @Test
    fun malformedInputReturnsNull() {
        assertNull(HardwareAttestation.parse(ByteArray(0)))
        assertNull(HardwareAttestation.parse(byteArrayOf(0x30, 0x05, 0x01))) // length runs past the buffer
        assertNull(HardwareAttestation.parse(byteArrayOf(0x02, 0x01, 0x04))) // top-level not a SEQUENCE
    }
}
