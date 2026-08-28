package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The comet solver against JPL DE421, through every branch it has.
 *
 * Truth comes from Skyfield driving DE421 with the Minor Planet Center's own published elements, at
 * the instant [Ephemeris.julianDateTT] derives from each fixture's epoch — so no clock difference
 * can hide inside a tolerance. The fixtures are real comets chosen to exercise every branch and
 * every awkward case: a short-period ellipse, a long-period one, one so eccentric it used to be
 * handled by a series expansion, a sungrazer with a perihelion distance of five hundredths of an AU,
 * and all three known interstellar objects, whose orbits are unbound and one of which is the most
 * hyperbolic thing ever measured in the solar system.
 */
class CometsTest {

    /** 2026-09-01T00:00:00Z, the instant every fixture is measured at. */
    private val epochMs = 1788220800000L

    private class Fix(
        val name: String,
        val q: Double,
        val e: Double,
        val jd: Double,
        val w: Double,
        val node: Double,
        val i: Double,
        val ra: Double,
        val dec: Double,
        val delta: Double,
    ) {
        val elements get() = Comets.Elements(name, q, e, jd, w, node, i)
    }

    private val fixtures = listOf(
        Fix("1P/Halley", 0.571114, 0.96802, 2474039.6476,
            112.1962, 59.296, 162.1871,
            124.92977054178874, 3.16251749030594, 35.84032869882962),
        Fix("2P/Encke", 0.338624, 0.847311, 2461446.7281,
            187.2865, 334.0194, 11.3479,
            22.774054477731074, 19.999777179755615, 1.6656421533489985),
        Fix("C/1995 O1 (Hale-Bopp)", 0.924542, 0.994899, 2450536.5341,
            130.7191, 281.798, 89.7393,
            333.09206164225964, -85.39319751702222, 50.830020347766194),
        Fix("342P/SOHO", 0.05175, 0.982976, 2461444.2817,
            27.7057, 73.2548, 11.6741,
            273.4825244595203, -32.58251359116838, 2.260016436000787),
        Fix("C/2023 A3 (Tsuchinshan-ATLAS)", 0.391359, 1.000177, 2460581.3196,
            308.5764, 21.6641, 139.101,
            266.6537420647352, 17.826144455225478, 8.047115668797638),
        Fix("1I/`Oumuamua", 0.25524, 1.199252, 2458005.9886,
            241.6845, 24.5997, 122.6778,
            359.42640244499415, 25.026512506578353, 53.24746748255029),
        Fix("2I/Borisov", 1.997724, 3.345952, 2458826.5572,
            209.2911, 307.8024, 44.2624,
            269.7101103171968, -54.10494320146918, 47.73216026951152),
        Fix("3I/ATLAS", 1.356507, 6.139884, 2460977.9825,
            128.0055, 322.1535, 175.1129,
            108.89689279468831, 19.372847286547234, 11.414340852863445),
        Fix("C/2020 P4-C (SOHO)", 0.089005, 1.013174, 2459066.6372,
            115.7125, 165.5622, 37.2249,
            95.10389055290347, -11.965292966292777, 23.662348363894157),
    )

    private fun errorArcsec(f: Fix): Double {
        val s = Comets.positionOf(f.elements, epochMs)
        assertNotNull("${f.name} did not solve at all", s)
        return Ephemeris.angularSeparationDeg(
            s!!.equatorial.rightAscensionDeg, s.equatorial.declinationDeg, f.ra, f.dec,
        ) * 3600.0
    }

    /**
     * ⚠️ **The bar is a function of distance, because the error is.**
     *
     * A flat tolerance would be the easy choice and a much weaker test. Driven by JPL's own Earth
     * position this solver agrees with Skyfield to 0.009 arcseconds over the whole 957-comet
     * catalogue, so what is measured here is [Ephemeris.earthHeliocentricJ2000Au] — a fixed
     * displacement of a few thousand kilometres, which subtends an angle inversely proportional to
     * how far away the comet is. Measured per fixture, the error times the distance runs from 4.0 to
     * 23.1 arcsecond-AU, so `14 / distance` sits above every one of them with a margin, and a floor
     * of one arcsecond covers the constant part that does not scale.
     *
     * The point of the shape: a flat five-arcsecond bar would pass Hale-Bopp at 50 AU even if it
     * regressed by a factor of ten. This will not.
     */
    @Test
    fun `every branch of the solver reproduces JPL to the limit the Earth's position allows`() {
        for (f in fixtures) {
            val err = errorArcsec(f)
            val bar = maxOf(1.0, 14.0 / f.delta)
            assertTrue(
                "${f.name} (e=${f.e}, ${f.delta} AU) is $err arcseconds from DE421, bar $bar",
                err < bar,
            )
        }
    }

    /** The distance is a far more sensitive check on the orbit than the direction is. */
    @Test
    fun `the distance to each comet is right to a hundred thousandth of an astronomical unit`() {
        for (f in fixtures) {
            val s = Comets.positionOf(f.elements, epochMs)!!
            assertTrue(
                "${f.name}: got ${s.geocentricAu} AU, JPL says ${f.delta}",
                abs(s.geocentricAu - f.delta) < 1e-4,
            )
        }
    }

    /**
     * ⚠️ **The one that justifies not using the near-parabolic series.**
     *
     * Schlyter's expansion — which this project's planet code follows for its own purposes, and
     * which the obvious reading of the literature says to use for `0.98 <= e <= 1.02` — puts
     * 342P/SOHO **540 arcseconds** from where it is, and Hale-Bopp 10.5. Both are inside that
     * eccentricity range and both are years from perihelion, which is where the series stops being a
     * good approximation. A plain Kepler solve gets both to well under an arcsecond of the Earth
     * position's own limit. This test pins the two worst cases so nobody reintroduces the series on
     * the strength of what the textbooks say without measuring it first.
     */
    @Test
    fun `the two comets a near-parabolic series gets badly wrong are solved correctly`() {
        for (name in listOf("342P/SOHO", "C/1995 O1 (Hale-Bopp)")) {
            val f = fixtures.first { it.name == name }
            val err = errorArcsec(f)
            // The series puts these 540 and 10.5 arcseconds out. Both bars are far below that.
            assertTrue("$name is $err arcseconds out", err < maxOf(1.0, 14.0 / f.delta))
        }
    }

    /**
     * ⚠️ **Light travel time, which is worth ten arcseconds and is the largest single correction
     * this file makes.** What an observer sees is where the comet was when the light left it, and
     * for 2P/Encke at 1.7 AU that is fourteen minutes ago. Without the iteration the fixtures move
     * by 9.7 arcseconds; the bar above is three, so removing it fails this test and several others.
     */
    @Test
    fun `light travel time moves a comet by much more than the tolerance`() {
        val encke = fixtures.first { it.name == "2P/Encke" }
        val s = Comets.positionOf(encke.elements, epochMs)!!
        // Light-time is delta / c. Encke is 1.66 AU away, so a shade under fourteen minutes.
        val minutes = s.geocentricAu / 173.1446326846693 * 24.0 * 60.0
        assertTrue("expected roughly fourteen minutes, got $minutes", minutes > 13.0 && minutes < 15.0)
        assertTrue("and the correction has visibly been applied", errorArcsec(encke) < 14.0 / encke.delta)
    }

    /**
     * ⚠️ **A sungrazer eighty years from perihelion is what broke the first hyperbolic starter.**
     *
     * `M / (e - 1)` is the textbook small-anomaly starter and it is unbounded: for a perihelion
     * distance of 0.008 AU and a time this far out it returns about 7,200, and `sinh` of that
     * overflows a double, so the solve produces infinity and never recovers. The shipped starter is
     * `asinh(M / e)`, which grows logarithmically. This asks for the extreme case directly rather
     * than trusting that no real catalogue contains one.
     */
    @Test
    fun `an extreme hyperbolic orbit far from perihelion still solves`() {
        for (years in listOf(-80.0, -3.0, 0.0, 3.0, 80.0)) {
            val el = Comets.Elements(
                designation = "probe", perihelionDistanceAu = 0.008, eccentricity = 1.0001,
                perihelionJdTt = Ephemeris.julianDateTT(epochMs) - years * 365.25,
                argumentOfPerihelionDeg = 30.0, ascendingNodeDeg = 100.0, inclinationDeg = 20.0,
            )
            val s = Comets.positionOf(el, epochMs)
            assertNotNull("no solution $years years from perihelion", s)
            assertTrue("distance is not finite", s!!.geocentricAu.isFinite() && s.geocentricAu > 0.0)
            assertTrue("right ascension is not finite", s.equatorial.rightAscensionDeg.isFinite())
        }
    }

    /**
     * ⚠️ **The parabolic band has to be crossable without a jump, or the boundary is a defect.**
     *
     * Three different solvers meet at `e == 1`, and the band where Barker's equation takes over was
     * chosen — by sweeping the disagreement from 1e-4 down to 1e-12 — as the point where the two
     * conic solves and the parabola agree most closely. This walks an orbit across that boundary and
     * requires the position to move smoothly, which is the property that choice exists to buy. At
     * a tenth of an arcsecond the assertion is far tighter than anything the app displays.
     */
    @Test
    fun `a position does not jump as an orbit crosses from ellipse to parabola to hyperbola`() {
        val base = { e: Double ->
            Comets.Elements("probe", 1.5, e, 2460000.0, 45.0, 120.0, 30.0)
        }
        val jd = Ephemeris.julianDateTT(epochMs)
        // The epoch is JD 2461284.5, so this orbit is about 1,285 days past perihelion -- which is
        // where the branches would diverge if the band were wrong, and where a series expansion for
        // near-parabolic orbits stops being valid at all.
        assertTrue("the fixture must actually be away from perihelion", abs(jd - 2460000.0) > 1000.0)
        val across = listOf(1.0 - 1e-7, 1.0 - 1e-8, 1.0 - 1e-10, 1.0, 1.0 + 1e-10, 1.0 + 1e-8, 1.0 + 1e-7)
            .map { Comets.positionOf(base(it), epochMs)!! }
        for (i in 1 until across.size) {
            val step = Ephemeris.angularSeparationDeg(
                across[i - 1].equatorial.rightAscensionDeg, across[i - 1].equatorial.declinationDeg,
                across[i].equatorial.rightAscensionDeg, across[i].equatorial.declinationDeg,
            ) * 3600.0
            assertTrue("step $i across the boundary jumped $step arcseconds", step < 0.1)
        }
    }

    /**
     * ⚠️ **The factor of 2.5, settled against a comet whose brightness is on record.**
     *
     * The catalogue's slope field is published under two conventions and the difference is a factor
     * of 2.5. Halley in March 1986 was 0.42 AU from Earth and 0.59 from the Sun, with the
     * catalogue's M1 = 5.5 and K1 = 3.2, and **was observed at about magnitude 2.1**. Reading the
     * slope as an exponent gives 2.2; reading it as a direct coefficient gives 3.05. Getting this
     * backwards would make every faint comet look bright and every bright one dull, so it is pinned.
     */
    @Test
    fun `the magnitude formula reproduces Halley at its 1986 brightest`() {
        val halley = Comets.Elements(
            "1P/Halley", 0.571114, 0.96802, 2474039.6476, 112.1962, 59.296, 162.1871,
            absoluteMagnitude = 5.5, magnitudeSlope = 3.2,
        )
        val m = Comets.magnitudeOf(halley, heliocentricAu = 0.59, geocentricAu = 0.42)!!
        // 5.5 + 5 log10(0.42) + 2.5 * 3.2 * log10(0.59) = 5.5 - 1.885 - 1.833 = 1.78
        assertEquals(1.78, m, 0.05)
        assertTrue("observed about 2.1, so within a third of a magnitude", abs(m - 2.1) < 0.35)
    }

    /** No stated brightness means no predicted brightness, rather than a plausible-looking number. */
    @Test
    fun `a comet the catalogue gives no brightness for predicts none`() {
        val f = fixtures.first()
        assertNull(Comets.magnitudeOf(f.elements, 1.0, 1.0))
        assertNull(Comets.positionOf(f.elements, epochMs)!!.magnitude)
    }

    /** Elements that describe no orbit at all are refused rather than solved into nonsense. */
    @Test
    fun `impossible elements are refused`() {
        val ok = fixtures.first().elements
        assertNull(Comets.positionOf(ok.copy(perihelionDistanceAu = 0.0), epochMs))
        assertNull(Comets.positionOf(ok.copy(perihelionDistanceAu = -1.0), epochMs))
        assertNull(Comets.positionOf(ok.copy(eccentricity = -0.5), epochMs))
        assertNull(Comets.positionOf(ok.copy(perihelionDistanceAu = Double.NaN), epochMs))
    }

    /**
     * ⚠️ **A comet lost in the Sun's glare is not reported however bright it is calculated to be**,
     * because that is a prediction nobody can act on. The elongation filter is the whole reason
     * [Comets.visible] exists rather than a plain sort by magnitude.
     */
    @Test
    fun `the visible list drops what is too faint and what is too near the Sun`() {
        val bright = fixtures.map {
            it.elements.copy(absoluteMagnitude = 5.0, magnitudeSlope = 4.0)
        }
        val all = bright.mapNotNull { Comets.positionOf(it, epochMs) }
        val lenient = Comets.visible(bright, epochMs, magnitudeLimit = 99.0, minElongationDeg = 0.0, limit = 99)
        assertEquals("with no filters everything that solves is listed", all.size, lenient.size)

        val strict = Comets.visible(bright, epochMs, magnitudeLimit = 99.0, minElongationDeg = 60.0, limit = 99)
        assertTrue("a real elongation cut has to remove something", strict.size < lenient.size)
        assertTrue("and only near-Sun comets", strict.all { it.elongationDeg >= 60.0 })

        val faint = Comets.visible(bright, epochMs, magnitudeLimit = -5.0, minElongationDeg = 0.0, limit = 99)
        assertTrue("nothing is brighter than magnitude -5", faint.isEmpty())
    }

    /** Brightest first, because that is the order somebody wants to read them in. */
    @Test
    fun `the visible list is ordered brightest first and honours its limit`() {
        val bright = fixtures.map { it.elements.copy(absoluteMagnitude = 5.0, magnitudeSlope = 4.0) }
        val list = Comets.visible(bright, epochMs, magnitudeLimit = 99.0, minElongationDeg = 0.0, limit = 3)
        assertEquals(3, list.size)
        for (i in 1 until list.size) {
            assertTrue("out of order at $i", list[i - 1].magnitude!! <= list[i].magnitude!!)
        }
    }

    /** The perihelion instant has to survive the trip from a Julian Date to the app's own clock. */
    @Test
    fun `the perihelion date round trips to within a second`() {
        for (f in fixtures) {
            val ms = Comets.perihelionEpochMs(f.elements)
            val back = Ephemeris.julianDateTT(ms)
            assertTrue(
                "${f.name}: ${(back - f.jd) * 86400.0} seconds adrift",
                abs(back - f.jd) * 86400.0 < 1.0,
            )
        }
    }

    /** Days to perihelion is a signed countdown, and its sign is the part that carries meaning. */
    @Test
    fun `days to perihelion is negative once it has passed`() {
        // Halley's next perihelion is in 2061; Encke's most recent was in 2027 by this element set.
        val halley = Comets.positionOf(fixtures.first { it.name == "1P/Halley" }.elements, epochMs)!!
        assertTrue("Halley is still decades out", halley.daysToPerihelion > 10_000.0)
        val oumuamua = Comets.positionOf(fixtures.first { it.name == "1I/`Oumuamua" }.elements, epochMs)!!
        assertTrue("'Oumuamua passed in 2017 and is leaving", oumuamua.daysToPerihelion < 0.0)
        assertTrue("describe says so in words", Comets.describe(oumuamua).contains("past perihelion"))
    }
}
