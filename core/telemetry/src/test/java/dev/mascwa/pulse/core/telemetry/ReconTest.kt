package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [Recon]'s pure logic — the parts of the reconnaissance dossier a test can hold still: which
 * hosts a subnet sweep should probe (and where it clamps a wide subnet), whether a MAC is a real
 * hardware address or a privacy-randomized one, the vendor behind a real one, the signal band, the
 * Wi-Fi security read, and the channel maths. The honesty rules — a randomized MAC is never resolved,
 * a clamped sweep says so — are asserted here because they are the whole point of the feature.
 */
class ReconTest {

    // ---- subnet maths -------------------------------------------------------------------------

    @Test
    fun aTwentyFourSweepsAllTwoHundredFiftyFourHostsExcludingNetworkAndBroadcast() {
        val hosts = Recon.subnetHosts("192.168.1.37", 24)
        assertEquals(254, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("network address must not be probed", hosts.contains("192.168.1.0"))
        assertFalse("broadcast must not be probed", hosts.contains("192.168.1.255"))
        assertFalse("did not clamp a /24", Recon.sweepWasClamped(24))
    }

    @Test
    fun aTwentyFiveHalvesTheHostCount() {
        val hosts = Recon.subnetHosts("192.168.1.37", 25)
        assertEquals(126, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.126", hosts.last())
    }

    @Test
    fun aWideSubnetIsClampedToThisDevicesOwnTwentyFour() {
        // A /16 is 65,534 hosts — never swept whole. Clamp to the /24 the neighbours live in.
        assertTrue(Recon.sweepWasClamped(16))
        val hosts = Recon.subnetHosts("10.0.0.5", 16)
        assertEquals(254, hosts.size)
        assertEquals("10.0.0.1", hosts.first())
        assertEquals("10.0.0.254", hosts.last())
    }

    @Test
    fun aPointToPointLinkAndIpv6AndGarbageHaveNoHosts() {
        assertTrue(Recon.subnetHosts("192.168.1.1", 31).isEmpty()) // /31 has no ordinary hosts
        assertTrue(Recon.subnetHosts("192.168.1.1", 32).isEmpty())
        assertTrue(Recon.subnetHosts("fe80::1", 64).isEmpty())     // sweep is v4 only
        assertTrue(Recon.subnetHosts("not.an.ip", 24).isEmpty())
    }

    @Test
    fun ipParsingRejectsOutOfRangeAndMalformed() {
        assertNull(Recon.parseIpv4("256.1.1.1"))
        assertNull(Recon.parseIpv4("1.2.3"))
        assertNull(Recon.parseIpv4("1.2.3.4.5"))
        assertEquals(3232235777L, Recon.parseIpv4("192.168.1.1")) // 192<<24|168<<16|1<<8|1
        assertEquals("192.168.1.1", Recon.ipv4String(3232235777L))
    }

    // ---- MAC identity -------------------------------------------------------------------------

    @Test
    fun theUlBitDistinguishesHardwareFromRandomized() {
        assertEquals(Recon.MacKind.HARDWARE, Recon.macKind("AC:DE:48:00:11:22")) // 0xAC: U/L clear
        assertEquals(Recon.MacKind.RANDOMIZED, Recon.macKind("02:00:00:00:00:00")) // 0x02: U/L set
        assertEquals(Recon.MacKind.RANDOMIZED, Recon.macKind("DA:A1:19:AB:CD:EF")) // 0xDA: U/L set
        assertEquals(Recon.MacKind.UNKNOWN, Recon.macKind("01:00:5E:00:00:00")) // 0x01: multicast
        assertEquals(Recon.MacKind.UNKNOWN, Recon.macKind("")) // nothing to read
        assertEquals(Recon.MacKind.UNKNOWN, Recon.macKind("A")) // malformed
    }

    @Test
    fun ouiKeyIsTheFirstThreeOctetsUppercaseOrNull() {
        assertEquals("ACDE48", Recon.ouiKey("AC:DE:48:00:11:22"))
        assertEquals("ACDE48", Recon.ouiKey("acde48001122"))
        assertNull(Recon.ouiKey("ZZ:DE:48:00:11:22"))
        assertNull(Recon.ouiKey("AC:DE"))
    }

    @Test
    fun aRandomizedMacIsNeverResolvedToAVendor() {
        val table = mapOf("ACDE48" to "IEEE Registration Authority")
        // The load-bearing rule: a private address is called private, not attributed to whoever owns
        // its random prefix.
        val randomized = Recon.resolveMac("02:11:22:33:44:55", table)
        assertEquals(Recon.MacKind.RANDOMIZED, randomized.kind)
        assertNull(randomized.vendor)
        assertEquals("randomized (private)", randomized.describe())

        val known = Recon.resolveMac("AC:DE:48:00:11:22", table)
        assertEquals("IEEE Registration Authority", known.describe())

        val unknownVendor = Recon.resolveMac("AC:DE:48:00:11:22", emptyMap())
        assertEquals("unknown vendor", unknownVendor.describe()) // real address, not in the table

        assertEquals("MAC unavailable", Recon.resolveMac("", table).describe())
    }

    // ---- radio signal -------------------------------------------------------------------------

    @Test
    fun signalReadsAsABandNotAFalseMetreFigure() {
        assertEquals("very close", Recon.rssiBand(-40))
        assertEquals("very close", Recon.rssiBand(-50))
        assertEquals("nearby", Recon.rssiBand(-51))
        assertEquals("nearby", Recon.rssiBand(-67))
        assertEquals("in range", Recon.rssiBand(-68))
        assertEquals("far", Recon.rssiBand(-90))
        assertEquals("signal unknown", Recon.rssiBand(0))
    }

    @Test
    fun oneMetreReferenceReadsAsOneMetreAndAPositiveRssiIsNotAReading() {
        assertEquals(1.0, Recon.rssiMeters(-59, txAt1m = -59), 0.001)
        assertTrue(Recon.rssiMeters(-80) > Recon.rssiMeters(-60)) // weaker = further
        assertEquals(-1.0, Recon.rssiMeters(5), 0.001)
    }

    // ---- Wi-Fi capability strings -------------------------------------------------------------

    @Test
    fun wifiSecurityIsReadFromTheCapabilityString() {
        assertEquals("WPA3", Recon.wifiSecurity("[WPA3-SAE-CCMP][ESS]"))
        assertEquals("WPA2", Recon.wifiSecurity("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("WPA2", Recon.wifiSecurity("[RSN-PSK-CCMP][ESS]"))
        assertEquals("WPA", Recon.wifiSecurity("[WPA-PSK-TKIP][ESS]"))
        assertEquals("WEP", Recon.wifiSecurity("[WEP][ESS]"))
        assertEquals("Open (encrypted)", Recon.wifiSecurity("[OWE][ESS]"))
        assertEquals("Open", Recon.wifiSecurity("[ESS]"))
    }

    @Test
    fun onlyAGenuinelyKeylessNetworkIsOpen() {
        assertTrue(Recon.isOpenNetwork("[ESS]"))
        assertFalse(Recon.isOpenNetwork("[WPA2-PSK-CCMP][ESS]"))
        assertFalse("OWE is encrypted despite having no passphrase", Recon.isOpenNetwork("[OWE][ESS]"))
    }

    @Test
    fun channelMathsCoversTheBandsAndRejectsGaps() {
        assertEquals(1, Recon.channelFor(2412))
        assertEquals(6, Recon.channelFor(2437))
        assertEquals(13, Recon.channelFor(2472))
        assertEquals(14, Recon.channelFor(2484))
        assertEquals(36, Recon.channelFor(5180))
        assertEquals(1, Recon.channelFor(5955)) // 6 GHz
        assertNull(Recon.channelFor(2400))
        assertNull(Recon.channelFor(5000))
    }

    // ---- the dossier as text ------------------------------------------------------------------

    @Test
    fun reportTextCarriesTitlesCountsProvenanceAndCaveats() {
        val c = Recon.ReconCollection(
            key = "net",
            title = "The network",
            summary = "You are on HOME-WIFI.",
            entries = listOf(
                Recon.ReconEntry("Your IP", "192.168.1.37", Recon.Provenance.REPORTED),
                Recon.ReconEntry("Vendor", "unknown vendor", Recon.Provenance.RESOLVED, detail = "OUI not in table"),
            ),
            caveat = "Sees devices that answer, not silent ones.",
        )
        val text = Recon.reportText(listOf(c), "TROVE — device dossier")
        assertTrue(text.contains("The network  (2)"))
        assertTrue(text.contains("Your IP: 192.168.1.37  [reported by the OS]"))
        assertTrue(text.contains("OUI not in table"))
        assertTrue(text.contains("⚠ Sees devices that answer, not silent ones."))
        assertTrue(text.contains("TROVE — device dossier"))
    }
}
