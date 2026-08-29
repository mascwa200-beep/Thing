package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class MilkyWayTest {

    // ---- the galactic frame --------------------------------------------------------------------

    @Test
    fun `the north galactic pole is at galactic latitude ninety by definition`() {
        // ⚠️ This is not a remembered coordinate: POLE_RA_DEG and POLE_DEC_DEG *define* where the
        // pole is, so the transform is only self-consistent if feeding it its own pole comes back
        // at +90. A sign error in the latitude term passes every round-trip and fails this.
        val g = MilkyWay.galacticOf(MilkyWay.POLE_RA_DEG, MilkyWay.POLE_DEC_DEG)
        assertEquals(90.0, g.latitudeDeg, 1e-9)

        val s = MilkyWay.galacticOf(MilkyWay.POLE_RA_DEG + 180.0, -MilkyWay.POLE_DEC_DEG)
        assertEquals(-90.0, s.latitudeDeg, 1e-9)
    }

    @Test
    fun `the celestial pole sits at the longitude that defines the zero`() {
        // Likewise NODE_L_DEG defines the longitude of the north CELESTIAL pole, so asking for it
        // must give that number back. This is what pins the rotation about the galactic axis — the
        // one degree of freedom the pole alone leaves free.
        val g = MilkyWay.galacticOf(0.0, 90.0)
        assertEquals(MilkyWay.NODE_L_DEG, g.longitudeDeg, 1e-9)
    }

    @Test
    fun `every direction survives a round trip through the galactic frame`() {
        // ⚠️ A round trip rather than a table of coordinates typed in from a reference. A transform
        // checked only against a handful of remembered positions is one wrong constant away from
        // being confidently wrong everywhere, and the wrongness would be a smooth rotation of the
        // whole sky — which looks entirely plausible on a chart.
        var worst = 0.0
        var at = ""
        for (raStep in 0 until 36) {
            for (decStep in -8..8) {
                val ra = raStep * 10.0
                val dec = decStep * 10.0
                val g = MilkyWay.galacticOf(ra, dec)
                val back = MilkyWay.equatorialOf(g.longitudeDeg, g.latitudeDeg)
                // Compare as an angle on the sky: near a pole a large difference in right ascension
                // is a small difference in direction, and comparing the numbers would fail there
                // for no physical reason.
                val sep = separation(ra, dec, back[0], back[1])
                if (sep > worst) { worst = sep; at = "ra=$ra dec=$dec" }
            }
        }
        // 1e-11 rather than 1e-9 because the measured worst case is 1.5e-13 — a bound loose enough
        // to be meaningless would pass a transform that had genuinely drifted.
        assertTrue("worst round-trip error ${worst}° at $at", worst < 1e-11)
    }

    @Test
    fun `the galactic plane runs through the constellations it actually runs through`() {
        // Three directions whose galactic latitude is a fact about the sky rather than about this
        // code. The galactic centre is in Sagittarius; Cygnus straddles the plane in the north; and
        // the Coma Berenices region is famously near the north galactic pole, which is why it is
        // full of distant galaxies. Tolerances are loose because these are constellation-sized.
        val centre = MilkyWay.galacticOf(266.405, -28.936) // Sagittarius A*
        assertEquals("the galactic centre is on the plane", 0.0, centre.latitudeDeg, 0.2)
        assertEquals("...and at the zero of longitude", 0.0, wrapSigned(centre.longitudeDeg), 0.2)

        val deneb = MilkyWay.galacticOf(310.358, 45.280) // Cygnus, in the northern Milky Way
        assertTrue("Deneb should be near the plane, was ${deneb.latitudeDeg}", abs(deneb.latitudeDeg) < 5.0)

        val ngp = MilkyWay.galacticOf(194.0, 27.9) // a degree or so off the north galactic pole
        assertTrue("should be near the pole, was ${ngp.latitudeDeg}", ngp.latitudeDeg > 88.0)
    }

    // ---- the encoding --------------------------------------------------------------------------

    @Test
    fun `a density survives the byte it is stored in`() {
        val peak = 717.3 // the real raster's maximum
        for (d in listOf(0.0, 21.0, 40.0, 162.0, 400.0, 717.3)) {
            val back = MilkyWay.decodeDensity(MilkyWay.encodeDensity(d, peak), peak)
            val err = if (d == 0.0) abs(back) else abs(back - d) / d
            assertTrue("$d came back as $back", err < 0.06)
        }
    }

    @Test
    fun `a linear byte would delete the faintest sky entirely, and this is where that happens`() {
        // ⚠️ My first version of this test asserted the difference at 25 stars per square degree and
        // FAILED — because at 25 a linear byte is accurate to 1.3% and the square root to 1.7%, so
        // linear actually wins there. Computing the two curves across the range rather than picking
        // a number showed where the difference really lives:
        //
        //   density   linear    sqrt
        //      0.5     100.0%    8.1%     <- rounds to byte 0 and the cell VANISHES
        //      1.0     100.0%   10.3%
        //      2.0      40.6%    6.8%
        //      5.0      12.5%    2.7%
        //     25.0       1.3%    1.7%     <- linear is fine by here
        //    400.0       0.1%    0.4%
        //
        // So linear's failure mode is not imprecision, it is DELETION: a faint cell rounds to zero
        // and stops existing. The outermost Milky Way is made of exactly those cells, and it would
        // have come out with a hard edge where the glow simply stopped.
        val peak = 717.3
        for (faint in listOf(0.5, 1.0, 2.0)) {
            val stored = MilkyWay.decodeDensity(MilkyWay.encodeDensity(faint, peak), peak)
            assertTrue("$faint should survive the round trip, came back $stored", stored > 0.0)
            val linear = (Math.round(255.0 * faint / peak) / 255.0) * peak
            if (faint <= 1.0) {
                assertEquals("a linear byte deletes $faint outright", 0.0, linear, 0.0)
            }
        }
        // And at the bright end the square root is still well within the Poisson noise of counting.
        val bright = 400.0
        val back = MilkyWay.decodeDensity(MilkyWay.encodeDensity(bright, peak), peak)
        assertTrue(abs(back - bright) / bright < 0.02)
    }

    @Test
    fun `an absent measurement encodes to nothing rather than to something faint`() {
        assertEquals(0, MilkyWay.encodeDensity(0.0, 717.0))
        assertEquals(0.0, MilkyWay.decodeDensity(0, 717.0), 0.0)
        // A peak of zero means the raster measured nothing at all, which must not divide.
        assertEquals(0, MilkyWay.encodeDensity(100.0, 0.0))
    }

    // ---- sampling ------------------------------------------------------------------------------

    @Test
    fun `a sample at a cell centre is that cell`() {
        val peak = 400.0
        val cells = ByteArray(MilkyWay.CELLS)
        cells[90 * MilkyWay.COLUMNS + 200] = 255.toByte()
        // Row 90 spans latitude 0..1, column 200 spans longitude 200..201, so the centre is at
        // (200.5, 0.5) — computed from the raster's own definition, not guessed.
        assertEquals(peak, MilkyWay.sample(cells, peak, 200.5, 0.5), 1e-9)
    }

    @Test
    fun `longitude wraps, so the seam is not a stripe down the sky`() {
        // ⚠️ The defect this guards is silent and looks like data: get the wrap wrong and a
        // one-degree band of nonsense runs from pole to pole, straight through Sagittarius.
        // ⚠️ Only the cell on ONE side of the seam is filled, and that is the whole design of this
        // fixture. My first version filled both — so a sampler that clamped instead of wrapping read
        // the wrong column, got 255 from it anyway, and the test passed against the defect. The
        // fixture never reached the branch.
        val peak = 400.0
        val cells = ByteArray(MilkyWay.CELLS)
        cells[90 * MilkyWay.COLUMNS + 359] = 255.toByte()

        // At l = 0 the two neighbours are the centres at 359.5 and 0.5, weighted equally: one full,
        // one empty. Encoded that is 127.5, and the decode squares it — so the answer is
        // (127.5/255)^2 * peak = peak/4, computed from the shipped encoding rather than guessed.
        assertEquals(peak / 4.0, MilkyWay.sample(cells, peak, 0.0, 0.5), 1e-9)
        // A clamping sampler reads column 0 for both neighbours and returns nothing at all.
        assertNotEquals(0.0, MilkyWay.sample(cells, peak, 0.0, 0.5), 1e-12)
        // Well past the filled cell it really is dark, which proves the value above came from
        // interpolating across the seam rather than from a blanket fill.
        assertEquals(0.0, MilkyWay.sample(cells, peak, 5.5, 0.5), 1e-12)
    }

    @Test
    fun `latitude clamps at the pole rather than wrapping onto the far side`() {
        val peak = 400.0
        val cells = ByteArray(MilkyWay.CELLS)
        for (x in 0 until MilkyWay.COLUMNS) {
            cells[(MilkyWay.ROWS - 1) * MilkyWay.COLUMNS + x] = 255.toByte() // the top row
            cells[0 * MilkyWay.COLUMNS + x] = 0                              // the bottom row
        }
        // At and beyond the north pole the answer is the north edge, never the south one.
        assertEquals(peak, MilkyWay.sample(cells, peak, 123.0, 90.0), 1e-9)
        assertEquals(peak, MilkyWay.sample(cells, peak, 123.0, 95.0), 1e-9)
        assertEquals(0.0, MilkyWay.sample(cells, peak, 123.0, -90.0), 1e-9)
    }

    @Test
    fun `a sample between two cells lies between their values`() {
        val peak = 400.0
        val cells = ByteArray(MilkyWay.CELLS)
        cells[90 * MilkyWay.COLUMNS + 200] = 255.toByte()
        cells[90 * MilkyWay.COLUMNS + 201] = 0
        val a = MilkyWay.sample(cells, peak, 200.5, 0.5)
        val mid = MilkyWay.sample(cells, peak, 201.0, 0.5)
        val b = MilkyWay.sample(cells, peak, 201.5, 0.5)
        assertTrue("$mid should sit between $b and $a", mid in b..a)
        assertNotEquals("a mosaic would give the same value across the whole cell", a, mid)
    }

    @Test
    fun `a raster too small to be one is refused rather than read past its end`() {
        assertEquals(0.0, MilkyWay.sample(ByteArray(10), 400.0, 0.0, 0.0), 0.0)
        assertEquals(0.0, MilkyWay.sample(ByteArray(0), 400.0, 0.0, 0.0), 0.0)
    }

    // ---- drawing -------------------------------------------------------------------------------

    @Test
    fun `the poles are drawn as nothing at all`() {
        // The measured polar density is 21-24 stars per square degree. Whatever else the curve
        // does, it must leave that black, or the whole sky carries a wash.
        assertEquals(0.0, MilkyWay.opacity(21.0), 0.0)
        assertEquals(0.0, MilkyWay.opacity(24.0), 0.0)
        assertEquals(0.0, MilkyWay.opacity(MilkyWay.FAINTEST_DENSITY), 0.0)
    }

    @Test
    fun `the Great Rift stays visibly darker than the plane around it`() {
        // ⚠️ The whole point of the feature, expressed as a property. The measured trough is 82
        // stars per square degree against ~210 either side; if the curve does not preserve that
        // contrast the Milky Way draws as a featureless band and nobody could tell it from a
        // painted one.
        val rift = MilkyWay.opacity(82.0)
        val plane = MilkyWay.opacity(210.0)
        assertTrue("the rift must be drawn at all, was $rift", rift > 0.0)
        assertTrue("the rift ($rift) should be well under the plane ($plane)", rift < plane * 0.7)
    }

    @Test
    fun `brightness rises with density and never exceeds the cap`() {
        var previous = -1.0
        var d = 0.0
        while (d <= 900.0) {
            val o = MilkyWay.opacity(d)
            assertTrue("opacity went backwards at $d", o >= previous)
            assertTrue("opacity $o exceeded the cap at $d", o <= MilkyWay.MAX_OPACITY + 1e-12)
            previous = o
            d += 5.0
        }
        assertEquals(MilkyWay.MAX_OPACITY, MilkyWay.opacity(MilkyWay.BRIGHTEST_DENSITY), 1e-12)
        assertEquals("and it saturates rather than growing", MilkyWay.MAX_OPACITY, MilkyWay.opacity(5000.0), 1e-12)
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * The angle between two directions, measured through the CHORD between them.
     *
     * ⚠️ **The obvious `acos(dot)` cannot measure a small angle and this test needs to.** For two
     * nearly-identical directions the dot product is 1 − δ with δ around 1e-16, and `acos` near 1
     * has an infinite derivative, so the answer carries an error of about √ε — **9e-7 degrees**,
     * regardless of how exact the inputs are. The first version of this helper used `acos` and duly
     * reported the round trip as accurate to "only" 1.2e-6°, which reads exactly like a wrong
     * constant somewhere in the transform. Measured through the chord instead, the same round trip
     * is accurate to **1.5e-13°** — ordinary double precision, and the transform was never wrong.
     *
     * The harness needs the same care as the thing it checks.
     */
    // ---- the vector form, and the file header ---------------------------------------------------

    @Test
    fun `the vector transform agrees with the angular one everywhere on the sky`() {
        // ⚠️ The rearranged rotation is not obviously the same arithmetic — it folds
        // `cos d cos(a - ap)` into two dot products — so this compares it against the readable form
        // over the whole sphere rather than at a handful of remembered coordinates. Longitude error
        // is weighted by cos(latitude) because at the pole longitude means nothing: an enormous
        // difference there is no distance at all.
        val out = DoubleArray(2)
        var worstL = 0.0
        var worstB = 0.0
        var ra = 0.0
        while (ra < 360.0) {
            var dec = -89.0
            while (dec <= 89.0) {
                val v = SkyProjection.equatorialVector(ra, dec)
                MilkyWay.galacticOfVector(v[0], v[1], v[2], out)
                val ref = MilkyWay.galacticOf(ra, dec)
                var dl = abs(out[0] - ref.longitudeDeg)
                if (dl > 180.0) dl = 360.0 - dl
                worstL = maxOf(worstL, dl * cos(ref.latitudeDeg * Math.PI / 180.0))
                worstB = maxOf(worstB, abs(out[1] - ref.latitudeDeg))
                // ⚠️ **An ABSOLUTE check beside the comparison, and it is not redundant.** Both
                // functions fold their longitude through the same `wrapLongitude`, so a fault in
                // THAT shifts the two sides equally and the comparison above stays perfectly happy
                // — which is exactly what happened when the wrap's fast path was negative-tested.
                // A range is a property neither function can talk the other into.
                assertTrue("longitude ${out[0]} is outside 0..360", out[0] >= 0.0 && out[0] < 360.0)
                assertTrue("latitude ${out[1]} is outside -90..90", abs(out[1]) <= 90.0)
                dec += 3.7
            }
            ra += 2.3
        }
        // Measured at 1.1e-13 and 1.3e-13 degrees; the bound is loose enough not to be brittle and
        // tight enough that a genuinely different rotation could not pass.
        assertTrue("longitude drifted by $worstL deg", worstL < 1e-11)
        assertTrue("latitude drifted by $worstB deg", worstB < 1e-11)
    }

    @Test
    fun `a header read the wrong way round is refused, not drawn`() {
        // ⚠️ The specific mistake this guards was made once while writing the builder: reading a
        // little-endian file as big-endian. It does not throw and it does not look wrong in a
        // debugger — the magic simply fails to match, and without this check the peak would come
        // back as a nonsense float and the whole sky would be scaled by it.
        val good = raster { _, _ -> 200 }
        assertNotNull(MilkyWay.readRaster(good))

        val swapped = good.copyOf()
        for (i in 0 until 4) swapped[i] = good[3 - i]
        assertNull("a byte-swapped magic must be refused", MilkyWay.readRaster(swapped))

        val futureVersion = good.copyOf()
        futureVersion[MilkyWay.OFF_VERSION] = (MilkyWay.FILE_VERSION + 1).toByte()
        assertNull("a layout this code does not know must be refused", MilkyWay.readRaster(futureVersion))

        val wrongShape = good.copyOf()
        wrongShape[MilkyWay.OFF_COLUMNS] = 0
        wrongShape[MilkyWay.OFF_COLUMNS + 1] = 1 // 256 columns, not 360
        assertNull("a raster built at another resolution must be refused", MilkyWay.readRaster(wrongShape))

        assertNull("a truncated file must be refused", MilkyWay.readRaster(good.copyOf(good.size - 1)))

        val zeroPeak = good.copyOf()
        for (i in 0 until 4) zeroPeak[MilkyWay.OFF_PEAK + i] = 0
        assertNull("a peak of zero would scale every cell to nothing", MilkyWay.readRaster(zeroPeak))
    }

    @Test
    fun `a decoded raster reads back the densities it was built from`() {
        // The round trip that matters: a cell written at a known density comes back at that density
        // through the file header rather than through a peak the caller had to remember separately.
        val peak = 717.3
        val bright = MilkyWay.encodeDensity(peak, peak)
        val mid = MilkyWay.encodeDensity(peak / 4.0, peak)
        val bytes = raster { c, _ -> if (c == 10) bright else mid }
        val r = MilkyWay.readRaster(bytes)!!
        assertEquals(peak, r.peak, 1e-3)
        assertEquals(peak, MilkyWay.sample(r.cells, r.peak, 10.5, 0.0), peak * 0.02)
        assertEquals(peak / 4.0, MilkyWay.sample(r.cells, r.peak, 200.5, 0.0), peak * 0.02)
    }

    /** A whole file: a real header plus one byte per cell from [fill]. */
    private fun raster(fill: (column: Int, row: Int) -> Int): ByteArray {
        val b = ByteArray(MilkyWay.FILE_BYTES)
        put32(b, MilkyWay.OFF_MAGIC, MilkyWay.MAGIC)
        put16(b, MilkyWay.OFF_VERSION, MilkyWay.FILE_VERSION)
        put16(b, MilkyWay.OFF_COLUMNS, MilkyWay.COLUMNS)
        put16(b, MilkyWay.OFF_ROWS, MilkyWay.ROWS)
        put32(b, MilkyWay.OFF_PEAK, 717.3f.toRawBits())
        for (row in 0 until MilkyWay.ROWS) {
            for (col in 0 until MilkyWay.COLUMNS) {
                b[MilkyWay.HEADER_BYTES + row * MilkyWay.COLUMNS + col] =
                    fill(col, row).coerceIn(0, 255).toByte()
            }
        }
        return b
    }

    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = (v and 0xFF).toByte()
        b[at + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) b[at + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun separation(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val d = Math.PI / 180.0
        val ux = cos(dec1 * d) * cos(ra1 * d)
        val uy = cos(dec1 * d) * sin(ra1 * d)
        val uz = sin(dec1 * d)
        val vx = cos(dec2 * d) * cos(ra2 * d)
        val vy = cos(dec2 * d) * sin(ra2 * d)
        val vz = sin(dec2 * d)
        val chord = kotlin.math.sqrt(
            (ux - vx) * (ux - vx) + (uy - vy) * (uy - vy) + (uz - vz) * (uz - vz),
        )
        return Math.toDegrees(2.0 * kotlin.math.asin((chord / 2.0).coerceAtMost(1.0)))
    }

    /** Longitude as -180..+180, so "near zero" can be asserted across the wrap. */
    private fun wrapSigned(deg: Double): Double {
        val w = ((deg % 360.0) + 360.0) % 360.0
        return if (w > 180.0) w - 360.0 else w
    }
}
