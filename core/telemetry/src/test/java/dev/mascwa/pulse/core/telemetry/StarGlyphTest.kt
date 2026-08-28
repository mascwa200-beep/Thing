package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glyph law, and the colour-band index the batching renderer needs.
 *
 * ⚠️ These two are tested together because they are the two halves of one thing: a renderer groups
 * stars by (colour band, size band), and a bug in either one shows up as the same symptom — the sky
 * drawn in the wrong colours, or every star the same size.
 */
class StarGlyphTest {

    // ---- the size law -------------------------------------------------------------------------

    @Test
    fun `a star at the cut-off is the smallest thing drawn, and each magnitude up is one band`() {
        assertEquals(0, StarGlyph.sizeBand(6.0, 6.0))
        assertEquals(1, StarGlyph.sizeBand(5.0, 6.0))
        assertEquals(4, StarGlyph.sizeBand(2.0, 6.0))
        // Sirius under a naked-eye cut: 6.0 - (-1.46) = 7.46 steps, so band 7, the ceiling.
        assertEquals(StarGlyph.SIZE_BANDS - 1, StarGlyph.sizeBand(-1.46, 6.0))
    }

    @Test
    fun `the ceiling holds however bright the star or deep the cut`() {
        assertEquals(StarGlyph.SIZE_BANDS - 1, StarGlyph.sizeBand(-30.0, 6.0))
        assertEquals(StarGlyph.SIZE_BANDS - 1, StarGlyph.sizeBand(1.0, 14.0))
        for (band in -5..20) {
            val r = StarGlyph.bandRadiusDp(band)
            assertTrue(
                "band $band drew radius $r, outside the declared range",
                r >= StarGlyph.MIN_RADIUS_DP &&
                    r <= StarGlyph.MIN_RADIUS_DP +
                    StarGlyph.RADIUS_STEP_DP * (StarGlyph.SIZE_BANDS - 1),
            )
        }
    }

    @Test
    fun `a star fainter than the cut-off answers a band rather than throwing`() {
        // The renderer drops it on the magnitude test; this only has to survive a rounding edge.
        assertEquals(0, StarGlyph.sizeBand(9.0, 6.0))
        assertEquals(0, StarGlyph.sizeBand(Double.NaN, 6.0))
        assertEquals(0, StarGlyph.sizeBand(4.0, Double.NaN))
    }

    @Test
    fun `radius rises with the band and never falls`() {
        var previous = -1f
        for (band in 0 until StarGlyph.SIZE_BANDS) {
            val r = StarGlyph.bandRadiusDp(band)
            assertTrue("band $band is not larger than band ${band - 1}", r > previous)
            previous = r
        }
    }

    // ---- what the law is FOR: structure at every zoom -----------------------------------------

    @Test
    fun `the same star is a different size under a different cut, which is the whole point`() {
        // ⚠️ An absolute law would draw a deep field as a flat wash of identical specks. Under a
        // naked-eye cut a fourth-magnitude star is middling; under a deep one it is the brightest
        // thing for miles and has to look like it.
        val wide = StarGlyph.sizeBand(4.0, SkyProjection.magnitudeLimit(SkyProjection.MAX_FOV_DEG, 12.0))
        val deep = StarGlyph.sizeBand(4.0, SkyProjection.magnitudeLimit(1.0, 12.0))
        assertTrue("a deep field must draw a bright star larger, not the same ($wide vs $deep)", deep > wide)
    }

    @Test
    fun `every band is reachable across the real zoom range`() {
        // A band nothing can ever land in is a bucket allocated for nothing. Sweep the actual field
        // range against the actual catalogue depth and require the whole ladder to be used.
        val seen = HashSet<Int>()
        var fov = SkyProjection.MIN_FOV_DEG
        while (fov <= SkyProjection.MAX_FOV_DEG) {
            val limit = SkyProjection.magnitudeLimit(fov, 12.0)
            var mag = -1.5
            while (mag <= limit) {
                seen += StarGlyph.sizeBand(mag, limit)
                mag += 0.1
            }
            fov *= 1.2
        }
        assertEquals(
            "some size band can never be drawn: $seen",
            (0 until StarGlyph.SIZE_BANDS).toSet(), seen,
        )
    }

    // ---- glow and labels ----------------------------------------------------------------------

    @Test
    fun `glow and labels are relative to the cut, not to a fixed brightness`() {
        // ⚠️ Derived from the constants rather than written as magnitudes, because the constants are
        // MEASURED — they moved once already when the halo count over the real catalogue turned out
        // to be a hundred and ninety rather than the few dozen intended. What is being tested here
        // is that the rule is relative at all; the values themselves are pinned separately below.
        val naked = 6.0
        val deep = 12.0
        assertTrue(StarGlyph.glows(naked - StarGlyph.GLOW_HEADROOM, naked))
        assertFalse(StarGlyph.glows(naked - StarGlyph.GLOW_HEADROOM + 0.1, naked))
        // The same star that does NOT glow against a naked-eye cut glows against a deep one, because
        // under a deep cut it is the brightest thing for miles. That is the whole property.
        val star = naked - StarGlyph.GLOW_HEADROOM + 0.1
        assertTrue(StarGlyph.glows(star, deep))
    }

    @Test
    fun `a label is rarer than a halo`() {
        // ⚠️ Pinned as an ordering rather than as two numbers: a chart with fifty words on it is
        // unreadable in a way that a chart with fifty haloes is not, so if these ever cross, labels
        // become the commoner of the two and the map fills with text.
        assertTrue(
            "labels must be at least as rare as glows",
            StarGlyph.LABEL_HEADROOM >= StarGlyph.GLOW_HEADROOM,
        )
        val cut = 6.0
        assertTrue(StarGlyph.labels(cut - StarGlyph.LABEL_HEADROOM, cut))
        assertFalse(StarGlyph.labels(cut - StarGlyph.LABEL_HEADROOM + 0.1, cut))
        // A star bright enough to be named is always bright enough to be haloed.
        assertTrue(StarGlyph.glows(cut - StarGlyph.LABEL_HEADROOM, cut))
    }

    @Test
    fun `the two headrooms are the values the real catalogue was swept for`() {
        // ⚠️ Pinned so a change is deliberate rather than a guess. Both were swept over the bundled
        // catalogue across twenty-four views at four latitudes and the whole zoom range; at the
        // busiest field five magnitudes of headroom yields about sixty haloes and five and a half
        // about seventeen names, where the first attempt at four and four and a half gave a hundred
        // and ninety and forty-eight. If either moves, re-run the sweep rather than reasoning about
        // it — star counts rise 2.8-fold per magnitude, so half a magnitude is nearly a factor of two.
        assertEquals(5.0, StarGlyph.GLOW_HEADROOM, 1e-9)
        assertEquals(5.5, StarGlyph.LABEL_HEADROOM, 1e-9)
    }

    @Test
    fun `an unmeasured magnitude neither glows nor gets a name`() {
        assertFalse(StarGlyph.glows(Double.NaN, 6.0))
        assertFalse(StarGlyph.labels(Double.NaN, 6.0))
    }

    // ---- the colour band index ----------------------------------------------------------------

    @Test
    fun `the band index agrees with the colour it stands for, on both scales`() {
        // ⚠️ The refactor that introduced these must not have moved a single edge, so the check is
        // that the index route and the colour route agree everywhere rather than that either one
        // returns some particular value.
        var v = -1.0
        while (v <= 4.0) {
            assertEquals(
                "B-V $v disagrees between the band and the colour",
                StarNames.colourArgb(v), StarNames.bandArgb(StarNames.bandFromBv(v)),
            )
            assertEquals(
                "bp_rp $v disagrees between the band and the colour",
                StarNames.colourArgbFromBpRp(v), StarNames.bandArgb(StarNames.bandFromBpRp(v)),
            )
            v += 0.01
        }
    }

    @Test
    fun `named stars land in the band their colour says they should`() {
        // ⚠️ The agreement test above CANNOT catch a moved edge, and finding that out is why this
        // exists: after the refactor both the band route and the colour route call one function, so
        // shifting an edge moves both sides together and they still agree. A negative test caught it
        // sitting there proving nothing.
        //
        // These are absolute, and they are real stars rather than the private table written out
        // again — a copy of the table would pass just as trivially. Every band is covered, so any
        // edge that moves far enough to change a spectral class fails here.
        val stars = listOf(
            Triple("Rigel", -0.03, 0),      // B8, blue-white
            Triple("Vega", 0.00, 1),        // A0, white — the zero point of the whole scale
            Triple("Procyon", 0.42, 2),     // F5
            Triple("the Sun", 0.65, 3),     // G2, yellow
            Triple("Arcturus", 1.23, 4),    // K1, orange
            Triple("Betelgeuse", 1.85, 5),  // M2, visibly red
        )
        for ((name, bv, band) in stars) {
            assertEquals("$name (B-V $bv) landed in the wrong colour band", band, StarNames.bandFromBv(bv))
        }

        // Gaia's scale, pinned away from its own edges so this is a claim about where the bands sit
        // rather than a restatement of where they end. The measured boundaries run 0.228, 0.490,
        // 0.780, 1.207, 2.159 — every value below is comfortably inside a band.
        val gaia = listOf(0.00 to 0, 0.35 to 1, 0.62 to 2, 1.00 to 3, 1.60 to 4, 2.60 to 5)
        for ((bpRp, band) in gaia) {
            assertEquals("bp_rp $bpRp landed in the wrong colour band", band, StarNames.bandFromBpRp(bpRp))
        }
    }

    @Test
    fun `the two scales agree about the same physical star`() {
        // The edges of both tables are the same five main-sequence temperatures, so a star sitting
        // midway between one pair of B-V edges must sit midway between the matching bp_rp pair. This
        // is the cross-check the two tables exist to survive, and unlike the edge values themselves
        // it is not something either table can be edited to satisfy on its own.
        val pairs = listOf(-0.5 to -0.2, 0.15 to 0.36, 0.45 to 0.63, 0.80 to 0.99, 1.25 to 1.68, 2.0 to 3.0)
        for ((bv, bpRp) in pairs) {
            assertEquals(
                "B-V $bv and bp_rp $bpRp are the same temperature and must be the same colour",
                StarNames.bandFromBv(bv), StarNames.bandFromBpRp(bpRp),
            )
        }
    }

    @Test
    fun `the two scales do not share a zero point`() {
        // An A0 star is white: B-V 0.00, bp_rp +0.23. Reaching for one table with the other's
        // numbers paints every white star blue, which is the mistake the measured edges exist to
        // avoid — so the bands must genuinely differ at that value.
        assertTrue(StarNames.bandFromBv(0.10) != StarNames.bandFromBpRp(0.10))
    }

    @Test
    fun `bands run blue to orange in order, with no gaps`() {
        val bands = (0 until StarNames.COLOUR_BANDS).map { StarNames.bandArgb(it) }
        assertTrue("every band has a colour", bands.all { it != null })
        assertEquals("bands are distinct", StarNames.COLOUR_BANDS, bands.toSet().size)
        // Blue-white first, orange last: red rises and blue falls across the ladder.
        val first = bands.first()!!
        val last = bands.last()!!
        assertTrue("the first band should be the bluest", (first and 0xFF) > (last and 0xFF))
        assertTrue("the last band should be the reddest", ((last shr 16) and 0xFF) >= ((first shr 16) and 0xFF))
    }

    @Test
    fun `no measured colour is a band of its own, and NaN counts as unmeasured`() {
        // ⚠️ NaN is what the deep catalogue's reader puts in the colour array for the roughly one
        // star in three hundred with no measurement. It used to fall through every `<` comparison
        // and come out ORANGE — a claim about a measurement nobody made, on thousands of stars.
        assertEquals(StarNames.NO_COLOUR_BAND, StarNames.bandFromBpRp(null))
        assertEquals(StarNames.NO_COLOUR_BAND, StarNames.bandFromBpRp(Double.NaN))
        assertEquals(StarNames.NO_COLOUR_BAND, StarNames.bandFromBv(Double.NaN))
        assertNull(StarNames.bandArgb(StarNames.NO_COLOUR_BAND))
        assertNull(StarNames.colourArgbFromBpRp(Double.NaN))
        assertNull(StarNames.bandArgb(StarNames.COLOUR_BANDS))
        assertNotNull(StarNames.bandArgb(StarNames.COLOUR_BANDS - 1))
    }
}
