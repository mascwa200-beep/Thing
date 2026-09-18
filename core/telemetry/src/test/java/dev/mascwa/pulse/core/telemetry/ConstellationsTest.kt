package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos

class ConstellationsTest {

    private fun sepDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val u = SkyProjection.equatorialVector(ra1, dec1)
        val v = SkyProjection.equatorialVector(ra2, dec2)
        return Math.toDegrees(acos((u[0] * v[0] + u[1] * v[1] + u[2] * v[2]).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun `the borders' epoch is a century and a quarter before J2000`() {
        // ⚠️ The SIGN is the whole point: positive would precess the wrong way and land at twice
        // the true offset, drawing a perfectly plausible sky in the wrong place.
        assertTrue(
            "B1875 is before J2000, so the value must be negative",
            Constellations.B1875_CENTURIES < 0.0,
        )
        assertEquals(-1.25, Constellations.B1875_CENTURIES, 0.001)
    }

    /**
     * Every one of these is a real pair: a vertex as published in CDS VI/49 `bound_18.dat` (B1875)
     * beside the same vertex as published in `bound_20.dat` (J2000). Across all 1,533 vertices the
     * two files share, this transformation reproduces the second from the first to a median of
     * 0.053 arcseconds; these five span the pole, the equator, the deep south and the zero-hour
     * meridian.
     */
    @Test
    fun `a published B1875 border vertex lands on its published J2000 counterpart`() {
        val published = listOf(
            // B1875 ra, dec          CDS J2000 ra, dec
            doubleArrayOf(315.00000, +86.16666, 308.3313560, +86.6306305), // UMI, near the pole
            doubleArrayOf(69.25005, +0.00000, 70.8523610, +0.2375014),     // ORI, on the equator
            doubleArrayOf(177.49995, -55.00000, 179.0707680, -55.6957932), // CRU, deep south
            doubleArrayOf(341.25000, +0.00000, 342.8497120, +0.6622211),   // PSC, near 0h
            doubleArrayOf(343.00005, +34.50000, 344.4653040, +35.1682358), // AND
        )
        for (row in published) {
            val got = Constellations.b1875ToJ2000(row[0], row[1])
            val arcsec = sepDeg(got[0], got[1], row[2], row[3]) * 3600.0
            assertTrue(
                "B1875 ${row[0]},${row[1]} landed $arcsec arcsec from the published J2000 place",
                arcsec < 1.0,
            )
            // ⚠️ And the same pair proves the test can fail: an identity function would leave the
            // point where it started, which is a long way from the published answer. Without this,
            // "within one arcsecond" would pass for a function that does nothing at all.
            //
            // ⚠️ The bar is half a degree rather than the 1.75 the general figure suggests, and the
            // reason is worth knowing: precession turns the sky about the ECLIPTIC pole, so a place
            // near the CELESTIAL pole travels a small circle round it and moves much less than the
            // rest. Measured on these five: 0.62° at Ursa Minor's vertex against 1.73° near Pisces.
            val moved = sepDeg(row[0], row[1], row[2], row[3])
            assertTrue("125 years of precession should move this vertex, got $moved", moved > 0.5)
        }
    }

    @Test
    fun `a parallel border bows away from the great circle between its ends`() {
        // The Octans/Chamaeleon border: a parallel at declination -82.5, spanning 90 degrees of
        // right ascension. It is the worst case in the whole IAU set.
        val edge = Constellations.Edge(
            Constellations.EdgeKind.PARALLEL, 115.0, -82.5, 205.0, -82.5, "OCT", "CHA",
        )
        val points = ArrayList<DoubleArray>()
        Constellations.walkEdge(edge, 0.5) { ra, dec -> points.add(doubleArrayOf(ra, dec)) }
        assertTrue("expected a subdivided run, got ${points.size}", points.size > 20)

        // The midpoint of the drawn line against the midpoint of the great circle joining its ends.
        val first = points.first()
        val last = points.last()
        val u = SkyProjection.equatorialVector(first[0], first[1])
        val v = SkyProjection.equatorialVector(last[0], last[1])
        val gx = u[0] + v[0]
        val gy = u[1] + v[1]
        val gz = u[2] + v[2]
        val n = Math.sqrt(gx * gx + gy * gy + gz * gz)
        val chordRa = Math.toDegrees(kotlin.math.atan2(gy / n, gx / n))
        val chordDec = Math.toDegrees(kotlin.math.asin((gz / n).coerceIn(-1.0, 1.0)))
        val mid = points[points.size / 2]
        val bow = sepDeg(mid[0], mid[1], chordRa, chordDec)
        // ⚠️ 2.18 degrees — four Moon diameters. This is what drawing a border end-to-end costs,
        // and it is why the interpolation happens in B1875 rather than between two precessed ends.
        assertEquals(2.18, bow, 0.05)
    }

    @Test
    fun `a walked border starts and ends exactly where it is published`() {
        val edge = Constellations.Edge(
            Constellations.EdgeKind.MERIDIAN, 343.0, 34.5, 343.0, 52.5, "AND", "LAC",
        )
        val points = ArrayList<DoubleArray>()
        Constellations.walkEdge(edge, 1.0) { ra, dec -> points.add(doubleArrayOf(ra, dec)) }
        val a = Constellations.b1875ToJ2000(343.0, 34.5)
        val b = Constellations.b1875ToJ2000(343.0, 52.5)
        // ⚠️ Compared coordinate by coordinate rather than as an angle: `acos` loses about half its
        // significant digits when its argument is near 1, so an exactly-equal pair of directions
        // measures a few milliarcseconds apart through a separation formula. That is a property of
        // the measurement, not of the values, and asserting through it would need a tolerance loose
        // enough to hide a real slip.
        assertEquals(a[0], points.first()[0], 1e-12)
        assertEquals(a[1], points.first()[1], 1e-12)
        assertEquals(b[0], points.last()[0], 1e-12)
        assertEquals(b[1], points.last()[1], 1e-12)
    }

    @Test
    fun `a parallel is shorter than its change in right ascension`() {
        val far = Constellations.Edge(
            Constellations.EdgeKind.PARALLEL, 115.0, -82.5, 205.0, -82.5, "OCT", "CHA",
        )
        // 90 degrees of right ascension at declination -82.5 is 11.75 degrees of sky. Measuring it
        // as 90 would cut it into eight times as many pieces as it needs.
        assertEquals(11.747, far.arcDeg, 0.01)

        val equator = Constellations.Edge(
            Constellations.EdgeKind.PARALLEL, 10.0, 0.0, 40.0, 0.0, "A", "B",
        )
        assertEquals(30.0, equator.arcDeg, 1e-9)

        val meridian = Constellations.Edge(
            Constellations.EdgeKind.MERIDIAN, 10.0, -5.0, 10.0, 25.0, "A", "B",
        )
        assertEquals(30.0, meridian.arcDeg, 1e-9)
    }

    @Test
    fun `the step tightens as the field narrows, and stops at the vertex budget`() {
        val wide = Constellations.stepDegFor(60.0)
        val medium = Constellations.stepDegFor(5.0)
        val narrow = Constellations.stepDegFor(0.25)
        assertTrue("$wide should be coarser than $medium", wide > medium)
        assertTrue("$medium should be coarser than $narrow", medium > narrow)
        assertEquals(0.745, wide, 0.005)
        assertEquals(0.215, medium, 0.005)
        // ⚠️ Clamped, not computed: the ideal step at a quarter-degree field is 0.048, which is
        // ninety-nine thousand vertices for the whole sky.
        assertEquals(Constellations.MIN_STEP_DEG, narrow, 1e-9)
        assertEquals(Constellations.MAX_STEP_DEG, Constellations.stepDegFor(1e6), 1e-9)
        assertEquals(Constellations.MAX_STEP_DEG, Constellations.stepDegFor(0.0), 1e-9)
        assertEquals(Constellations.MAX_STEP_DEG, Constellations.stepDegFor(Double.NaN), 1e-9)
    }

    @Test
    fun `the step meets the stated tolerance wherever it is not clamped`() {
        var fov = 0.25
        while (fov <= 150.0) {
            val step = Constellations.stepDegFor(fov)
            if (step > Constellations.MIN_STEP_DEG && step < Constellations.MAX_STEP_DEG) {
                val deviation = Constellations.CHORD_K * step * step / fov
                assertTrue(
                    "at fov $fov the step $step deviates $deviation",
                    deviation <= Constellations.SCREEN_TOLERANCE * 1.0001,
                )
            }
            fov *= 1.3
        }
    }

    @Test
    fun `the short way round is taken across the zero-hour mark`() {
        assertEquals(7.0, Constellations.deltaRa(343.0, 350.0), 1e-9)
        // 350 -> 5 is five degrees forward, not three hundred and fifty-five back.
        assertEquals(15.0, Constellations.deltaRa(350.0, 5.0), 1e-9)
        assertEquals(-15.0, Constellations.deltaRa(5.0, 350.0), 1e-9)
    }

    @Test
    fun `a walked great circle stays on the sphere and passes through its own middle`() {
        val points = ArrayList<DoubleArray>()
        Constellations.walkGreatCircle(10.0, 20.0, 40.0, -10.0, 1.0) { ra, dec ->
            points.add(doubleArrayOf(ra, dec))
        }
        assertTrue(points.size > 30)
        for (p in points) {
            assertTrue("declination out of range: ${p[1]}", abs(p[1]) <= 90.0 + 1e-9)
            assertTrue("right ascension out of range: ${p[0]}", p[0] >= 0.0 && p[0] < 360.0)
        }
        // Every point should sit on the great circle through the two ends, which is to say the
        // separations to each end should sum to the separation between them.
        val total = sepDeg(10.0, 20.0, 40.0, -10.0)
        for (p in points) {
            val sum = sepDeg(10.0, 20.0, p[0], p[1]) + sepDeg(p[0], p[1], 40.0, -10.0)
            // A tenth of an arcsecond. The floor here is `acos` losing precision at the ends of the
            // run, where one of the two separations is near zero — not the interpolation.
            assertEquals(total, sum, 3e-5)
        }
        // ⚠️ And a straight blend of the two directions would ALSO satisfy that, because it stays in
        // the same plane — so the spacing has to be checked too. Slerp is evenly spaced; a linear
        // blend crowds toward the ends.
        val firstGap = sepDeg(points[0][0], points[0][1], points[1][0], points[1][1])
        val midGap = sepDeg(
            points[points.size / 2][0], points[points.size / 2][1],
            points[points.size / 2 + 1][0], points[points.size / 2 + 1][1],
        )
        assertEquals(firstGap, midGap, 3e-5)
    }

    @Test
    fun `coincident ends do not divide by zero`() {
        val points = ArrayList<DoubleArray>()
        Constellations.walkGreatCircle(10.0, 20.0, 10.0, 20.0, 1.0) { ra, dec ->
            points.add(doubleArrayOf(ra, dec))
        }
        assertTrue(points.isNotEmpty())
        for (p in points) {
            assertEquals(0.0, sepDeg(p[0], p[1], 10.0, 20.0), 1e-6)
        }
    }

    private val sample = """
        {
          "epoch": "J2000",
          "boundaryEpoch": "B1875",
          "stars": [[10.0, 20.0], [11.0, 21.0], [12.0, 22.0]],
          "figures": [{"code": "Aql", "name": "Eagle", "lines": [[0, 1, 2], [2, 0]]}],
          "asterisms": [{"code": "GDi", "name": "Great Diamond", "lines": [[0, 2]]}],
          "boundaries": [["M", 343.0, 34.5, 343.0, 52.5, "AND", "LAC"],
                         ["P", 343.0, 52.5, 350.0, 52.5, "AND", "CAS"]]
        }
    """.trimIndent()

    @Test
    fun `the asset's shapes come back whole`() {
        val data = Constellations.parse(sample)
        assertNotNull(data)
        data!!
        assertEquals(3, data.starRaDeg.size)
        assertEquals(22.0, data.starDecDeg[2], 1e-9)
        assertEquals(1, data.figures.size)
        assertEquals("Eagle", data.figures[0].name)
        assertEquals(2, data.figures[0].lines.size)
        assertEquals(listOf(0, 1, 2), data.figures[0].lines[0].toList())
        assertEquals(1, data.asterisms.size)
        assertEquals(2, data.boundaries.size)
        assertEquals(Constellations.EdgeKind.MERIDIAN, data.boundaries[0].kind)
        assertEquals(Constellations.EdgeKind.PARALLEL, data.boundaries[1].kind)
        assertEquals("LAC", data.boundaries[0].b)
    }

    @Test
    fun `a line drawn to a star that is not there is dropped, not shortened`() {
        // ⚠️ Shortening it would draw a real line between the wrong two stars, which looks like a
        // constellation and is not one.
        val broken = sample.replace("[0, 1, 2]", "[0, 99, 2]")
        val data = Constellations.parse(broken)
        assertNotNull(data)
        assertEquals(1, data!!.figures[0].lines.size)
        assertEquals(listOf(2, 0), data.figures[0].lines[0].toList())
    }

    @Test
    fun `rubbish answers null rather than throwing`() {
        assertNull(Constellations.parse(""))
        assertNull(Constellations.parse("not json at all"))
        assertNull(Constellations.parse("""{"figures": []}"""))
        assertNull(Constellations.parse("""{"stars": []}"""))
    }

    @Test
    fun `an unknown border kind is skipped rather than guessed at`() {
        val odd = sample.replace("\"M\", 343.0, 34.5", "\"?\", 343.0, 34.5")
        val data = Constellations.parse(odd)
        assertNotNull(data)
        assertEquals(1, data!!.boundaries.size)
        assertEquals(Constellations.EdgeKind.PARALLEL, data.boundaries[0].kind)
    }
}
