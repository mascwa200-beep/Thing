package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The packed record, checked by round trip against the precision each field claims.
 *
 * ⚠️ **A format is the one thing here that two languages must agree about**, and it fails silently:
 * a builder and a reader that disagree by one unit, or by one field, produce a sky of plausible
 * stars in slightly wrong places. Nothing throws. So every codec is exercised across its real range
 * — the magnitudes a catalogue actually holds, the proper motions Gaia actually measured — rather
 * than at a few tidy values.
 */
class StarCatalogFormatTest {

    // ---- magnitude ---------------------------------------------------------------------------

    @Test
    fun `magnitude survives the round trip to a sixteenth of a magnitude`() {
        var m = -1.5
        while (m <= 16.0) {
            val back = StarCatalogFormat.decodeMagnitude(StarCatalogFormat.encodeMagnitude(m))
            // Half a step of 1/14, and 1/14 of a magnitude is well under what an eye can tell.
            assertTrue("magnitude $m came back $back", abs(back - m) <= 0.5 / StarCatalogFormat.MAG_SCALE + 1e-9)
            m += 0.013
        }
    }

    @Test
    fun `the brightest and faintest real stars both fit`() {
        // Sirius, and the deep tier's floor.
        assertEquals(-1.46, StarCatalogFormat.decodeMagnitude(StarCatalogFormat.encodeMagnitude(-1.46)), 0.04)
        assertEquals(14.0, StarCatalogFormat.decodeMagnitude(StarCatalogFormat.encodeMagnitude(14.0)), 0.04)
        assertTrue(
            "a full byte must reach past any catalogue we ship",
            StarCatalogFormat.FAINTEST_ENCODABLE > 16.0,
        )
    }

    @Test
    fun `magnitude never goes backwards, because the file is sorted on it`() {
        // ⚠️ The reader binary-searches the magnitude byte to find where a tile's drawable stars
        // stop. That is only valid if the encoding is monotonic in the value.
        var previous = -1
        var m = -2.0
        while (m <= 16.2) {
            val here = StarCatalogFormat.encodeMagnitude(m)
            assertTrue("encoding went backwards at $m", here >= previous)
            previous = here
            m += 0.01
        }
    }

    // ---- colour ------------------------------------------------------------------------------

    @Test
    fun `colour round-trips finely enough to tell the bands apart`() {
        // The narrowest gap between the measured bp_rp band edges is 0.262 (0.228 to 0.490).
        var c = -1.0
        while (c <= 5.0) {
            val back = StarCatalogFormat.decodeColour(StarCatalogFormat.encodeColour(c))!!
            assertTrue("colour $c came back $back", abs(back - c) <= 0.5 / StarCatalogFormat.COLOUR_SCALE + 1e-9)
            c += 0.007
        }
    }

    @Test
    fun `no measured colour is a value of its own, not a guess`() {
        // ⚠️ One star in three hundred has none, and drawing those white is a decision the reading
        // side should get to make — so absence has to survive the file.
        assertEquals(StarCatalogFormat.COLOUR_ABSENT, StarCatalogFormat.encodeColour(null))
        assertNull(StarCatalogFormat.decodeColour(StarCatalogFormat.COLOUR_ABSENT))
        assertEquals(StarCatalogFormat.COLOUR_ABSENT, StarCatalogFormat.encodeColour(Double.NaN))
        // And a real measurement never collides with it.
        for (c in listOf(-3.0, -1.0, 0.0, 2.16, 5.0, 8.9)) {
            assertNotEquals("$c collided with the absent marker", StarCatalogFormat.COLOUR_ABSENT, StarCatalogFormat.encodeColour(c))
        }
    }

    // ---- proper motion ---------------------------------------------------------------------------

    /**
     * The square law, checked against the error curve its own documentation promises: the absolute
     * error is about `sqrt(PM_SCALE * pm)`, which is a fraction of an arcsecond a century for an
     * ordinary star and under ten for the fastest one known.
     */
    @Test
    fun `proper motion is accurate in proportion to how fast the star moves`() {
        val cases = listOf(0.0, 0.4, 5.0, 10.0, 50.0, 100.0, 500.0, 1000.0, 5000.0, 10328.1)
        for (pm in cases) {
            val back = StarCatalogFormat.decodeProperMotion(StarCatalogFormat.encodeProperMotion(pm))
            val allowed = sqrt(StarCatalogFormat.PM_SCALE * pm) + StarCatalogFormat.PM_SCALE
            assertTrue("$pm mas/yr came back $back (allowed ${allowed})", abs(back - pm) <= allowed)
            // And the same going the other way, sign and all.
            val negative = StarCatalogFormat.decodeProperMotion(StarCatalogFormat.encodeProperMotion(-pm))
            assertEquals(-back, negative, 1e-9)
        }
    }

    /**
     * ⚠️ Barnard's Star is the whole reason this is not a linear byte. It crosses 10,328 mas/yr in
     * declination — twenty times faster than all but two thousand of the sixteen million — and a
     * scheme that clamped it would be wrong about the single most famous moving star in the sky.
     */
    @Test
    fun `the fastest star known is inside the range with room to spare`() {
        assertTrue("the ceiling is only ${StarCatalogFormat.PM_MAX}", StarCatalogFormat.PM_MAX > 11_000.0)
        val barnard = StarCatalogFormat.decodeProperMotion(StarCatalogFormat.encodeProperMotion(10328.1))
        assertEquals(10328.1, barnard, 100.0)
        // A century of drift, which is what anybody would ever see: under ten arcseconds of error on
        // a star that has moved a thousand.
        assertTrue(abs(barnard - 10328.1) * 100.0 / 1000.0 < 10.0)
    }

    @Test
    fun `an unmeasured proper motion is zero and stays zero`() {
        assertEquals(0, StarCatalogFormat.encodeProperMotion(0.0))
        assertEquals(0.0, StarCatalogFormat.decodeProperMotion(0), 0.0)
        assertEquals(0, StarCatalogFormat.encodeProperMotion(Double.NaN))
    }

    // ---- position ---------------------------------------------------------------------------------

    /**
     * ⚠️ The claim worth pinning: precision comes out roughly uniform **on the sky**, not in right
     * ascension. A polar tile spans far more RA than an equatorial one, so its RA step is coarser —
     * and an RA degree there is a small angle, which cancels it. If the divisions ever stopped
     * scaling with the cosine this would fail, and nothing else would.
     */
    @Test
    fun `a position round-trips to a fraction of an arcsecond, at every declination`() {
        for (band in 0 until SkyGrid.BANDS) {
            val tile = SkyGrid.firstTileOfBand(band)
            val b = SkyGrid.boundsOf(tile)
            // Five places across the tile, avoiding the exact edges.
            for (i in 1..5) {
                val f = i / 6.0
                val ra = b.raLoDeg + f * b.raSpanDeg
                val dec = b.decLoDeg + f * b.decSpanDeg
                val ra2 = StarCatalogFormat.decodeRa(StarCatalogFormat.encodeRa(ra, b), b)
                val dec2 = StarCatalogFormat.decodeDec(StarCatalogFormat.encodeDec(dec, b), b)
                // Error on the sky, not in coordinates: an RA error shrinks with the cosine.
                val raErrorArcsec = abs(ra2 - ra) * 3600.0 * cos(Math.toRadians(dec))
                val decErrorArcsec = abs(dec2 - dec) * 3600.0
                assertTrue("band $band: RA out by ${raErrorArcsec}\"", raErrorArcsec < 0.4)
                assertTrue("band $band: dec out by ${decErrorArcsec}\"", decErrorArcsec < 0.2)
                // And it must still land in the tile it came from.
                assertEquals("band $band: round trip changed tile", tile, SkyGrid.tileOf(ra2, dec2))
            }
        }
    }

    // ---- the file's own arithmetic ------------------------------------------------------------------

    @Test
    fun `the offsets agree with each other`() {
        val tiles = SkyGrid.tileCount
        val stars = 16_844_156
        assertEquals(StarCatalogFormat.HEADER_BYTES.toLong(), StarCatalogFormat.tileIndexOffset(0))
        // The index has one more entry than there are tiles: the last is where the records end.
        assertEquals(StarCatalogFormat.recordsOffset(tiles), StarCatalogFormat.tileIndexOffset(tiles + 1))
        assertEquals(StarCatalogFormat.recordsOffset(tiles), StarCatalogFormat.recordOffset(tiles, 0))
        assertEquals(
            StarCatalogFormat.expectedBytes(tiles, stars),
            StarCatalogFormat.recordOffset(tiles, stars),
        )
        // The size claim this whole design rests on: eight bytes a star, so the deep catalogue is
        // 135 MB rather than the 168 a sixteen-bit proper motion would cost.
        val megabytes = StarCatalogFormat.expectedBytes(tiles, stars) / 1_000_000.0
        assertTrue("the deep catalogue came out at ${megabytes} MB", megabytes in 130.0..140.0)
    }
}
