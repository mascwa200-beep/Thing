package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tiling exists twice — here and in `tools/sky/build_catalogue.py` — and this is what stops the
 * two drifting apart.
 *
 * ⚠️ **Two implementations of one algorithm is the mistake this repository has corrected seven
 * times, and here it would be the least visible instance yet.** The builder decides which tile each
 * star is written into; the reader decides which tiles to look in. If they disagree, nothing throws,
 * no record is malformed and no test of either side alone notices — every star decodes perfectly
 * and lands in the wrong part of the sky. A catalogue built under one tiling and read under another
 * is a plausible, wrong universe.
 *
 * The fixture is written by `python3 tools/sky/build_catalogue.py --parity-only`, so regenerating it
 * is one command whenever [SkyGrid] genuinely changes — and forgetting to regenerate it fails here
 * rather than shipping.
 *
 * ⚠️ It is a **test resource**, loaded from the classpath, precisely so this cannot depend on which
 * directory Gradle happens to run a test from.
 */
class SkyGridParityTest {

    private fun fixture(): List<String> {
        val stream = javaClass.getResourceAsStream(RESOURCE)
            ?: throw AssertionError(
                "$RESOURCE is missing. Write it with:\n" +
                    "  python3 tools/sky/build_catalogue.py --parity-only",
            )
        return stream.bufferedReader().readLines()
    }

    @Test
    fun `the builder and the reader agree about the geometry itself`() {
        val header = fixture().first { it.startsWith("#") }.removePrefix("#").trim()
        assertEquals(
            "the fixture was written for a different tiling — regenerate it with " +
                "`python3 tools/sky/build_catalogue.py --parity-only` and check the change was intended",
            SkyGrid.FORMAT_KEY,
            header,
        )
    }

    @Test
    fun `every position in the fixture lands in the tile the builder chose`() {
        var checked = 0
        for (line in fixture()) {
            if (line.startsWith("#") || line.isBlank()) continue
            val parts = line.split('\t')
            assertTrue("malformed fixture row: $line", parts.size >= 3)
            val ra = parts[0].toDouble()
            val dec = parts[1].toDouble()
            val expected = parts[2].toInt()
            assertEquals(
                "the two tilings disagree at ra=$ra dec=$dec — a catalogue built with one and read " +
                    "with the other would put every star in that region somewhere else",
                expected,
                SkyGrid.tileOf(ra, dec),
            )
            checked++
        }
        // ⚠️ A fixture that had silently become empty would pass every assertion above.
        assertTrue("only $checked positions were checked", checked > 5_000)
    }
}

/** Under `core/telemetry/src/test/resources`. */
private const val RESOURCE = "/sky/grid_parity.tsv"
