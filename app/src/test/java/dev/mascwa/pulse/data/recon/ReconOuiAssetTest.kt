package dev.mascwa.pulse.data.recon

import dev.mascwa.pulse.core.telemetry.Recon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled OUI (MAC vendor) table, checked against itself and against the core that reads it.
 *
 * ⚠️ Every guard here is against a silent failure. The table is forty thousand rows nobody proofreads:
 * a builder that emitted a header, a sixth column, or lower-case prefixes would parse to a map that
 * quietly resolves nothing, and the "who's on the Wi-Fi" collection would show every device as
 * "unknown vendor" with nothing to say the lookup was broken rather than the network anonymous.
 *
 * ⚠️ The pure [Recon] cannot do this half — it has no way to read an asset — and the load-bearing
 * honesty rule (a randomized address is never resolved to a vendor) is asserted here against the REAL
 * table, not a fixture, because that is the map it will face on the device.
 */
class ReconOuiAssetTest {

    private val asset = File("src/main/assets/recon/oui.tsv")

    private fun table(): Map<String, String> {
        assertTrue("the OUI table is missing: ${asset.absolutePath}", asset.isFile)
        val lines = asset.readLines().filter { it.isNotBlank() }
        val map = HashMap<String, String>(lines.size * 2)
        for (line in lines) {
            val f = line.split('\t')
            assertEquals("wrong column count: $line", 2, f.size)
            assertEquals("prefix is not six hex chars: $line", 6, f[0].length)
            assertTrue("prefix is not uppercase hex: $line", f[0].all { it in "0123456789ABCDEF" })
            assertTrue("empty vendor: $line", f[1].isNotBlank())
            map[f[0]] = f[1]
        }
        return map
    }

    @Test
    fun `the table is there and every row of it is well formed`() {
        val map = table()
        // A floor, not an exact count: the IEEE registry grows daily and a rebuild that gained a few
        // hundred must not fail a build. Losing most of it is the failure worth catching.
        assertTrue("only ${map.size} OUI rows — the table looks truncated", map.size > 20_000)
    }

    @Test
    fun `prefixes are unique`() {
        val lines = asset.readLines().filter { it.isNotBlank() }
        val prefixes = lines.map { it.substringBefore('\t') }
        assertEquals("two rows share a prefix", prefixes.size, prefixes.toSet().size)
    }

    @Test
    fun `the core resolves real hardware addresses through the real table`() {
        // ⚠️ The proof the shipped asset and the shipped lookup agree. These are stable IEEE
        // assignments; a builder change that reshaped the file would break the describe() equality.
        val map = table()
        assertEquals("Apple, Inc.", Recon.resolveMac("00:03:93:AA:BB:CC", map).describe())
        assertEquals("Google, Inc.", Recon.resolveMac("00:1A:11:AA:BB:CC", map).describe())
        assertEquals("Samsung Electronics Co.,Ltd", Recon.resolveMac("00:00:F0:AA:BB:CC", map).describe())
    }

    @Test
    fun `a randomized address is never resolved, even against the real table`() {
        // The honesty rule against real data: a locally-administered (privacy) MAC must be named
        // private, not attributed to whichever registrant happens to own its random prefix.
        val map = table()
        val id = Recon.resolveMac("DA:A1:19:22:33:44", map)
        assertEquals(Recon.MacKind.RANDOMIZED, id.kind)
        assertEquals("randomized (private)", id.describe())
    }

    @Test
    fun `an unregistered hardware prefix is unknown, not blank and not invented`() {
        val map = table()
        // A globally-unique address whose /24 is not in the registry — real address, no vendor.
        assertEquals("unknown vendor", Recon.resolveMac("F8:E9:D7:00:00:00", map.minus("F8E9D7")).describe())
    }
}
