package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The one arithmetic that moves each star differently.
 *
 * ⚠️ **The rule worth guarding is the cosine, and it is invisible where most tests would put a
 * star.** Both catalogues state the right-ascension component as the PROJECTED motion — Gaia's
 * `pmra` is mu-alpha-star, and the Bright Star Catalogue's ReadMe says outright *"the proper motion
 * in RA is the projected motion (cos(DE).d(RA)/dt)"* — so turning it back into a change in right
 * ascension means dividing by cos(dec). At the equator that divisor is 1, so a test written only
 * there passes whether the division is present, absent, or replaced by a multiplication. Half the
 * cases below are deliberately at high declination for that reason.
 *
 * Every expected value is derived from the definition rather than recalled, and the arithmetic is
 * in the comment beside it.
 */
class ProperMotionTest {

    private val out = DoubleArray(2)

    // ---- the projected-motion convention ----------------------------------------------------

    @Test
    fun `the right ascension step is divided by the cosine of declination`() {
        // One degree a year of PROJECTED motion, at dec 60 where cos = 0.5. A degree of projected
        // motion there is two degrees of right ascension, because the hour circles are half as far
        // apart on the ground as they are at the equator.
        ProperMotion.carry(100.0, 60.0, ProperMotion.MAS_PER_DEGREE, 0.0, 1.0, out)
        assertEquals("1 deg/yr projected at dec 60 is 2 deg of RA", 102.0, out[0], 1e-9)
        assertEquals("declination is untouched", 60.0, out[1], 1e-12)
    }

    @Test
    fun `the declination step is not divided by anything`() {
        // The mirror mistake: applying the cosine to both components. Declination is measured along
        // a great circle, so a mas is a mas wherever the star is.
        ProperMotion.carry(100.0, 60.0, 0.0, ProperMotion.MAS_PER_DEGREE, 1.0, out)
        assertEquals("1 deg/yr in dec is 1 degree, at any declination", 61.0, out[1], 1e-12)
        assertEquals("right ascension is untouched", 100.0, out[0], 1e-12)
    }

    @Test
    fun `at the equator the two components move the same amount`() {
        // ⚠️ Pinned as the case that proves nothing on its own. cos(0) is 1, so this passes with the
        // division present, absent, or turned into a multiplication — which is exactly why the two
        // tests above sit at dec 60. Here to say so, not to guard.
        // 1000 mas is one arcsecond, which is 1/3600 of a degree.
        ProperMotion.carry(10.0, 0.0, 1_000.0, 1_000.0, 1.0, out)
        val dRa = out[0] - 10.0
        val dDec = out[1] - 0.0
        assertEquals("both components move by the same 1000 mas", dRa, dDec, 1e-12)
        assertEquals(1.0 / 3600.0, dRa, 1e-12)
    }

    @Test
    fun `the cosine grows the step without limit toward the pole`() {
        // The failure mode of getting this wrong is not a small error — it is unbounded. Same
        // projected motion at three declinations: 1 / cos(60) = 2, 1 / cos(80) = 5.76.
        val shifts = doubleArrayOf(0.0, 60.0, 80.0).map { dec ->
            ProperMotion.carry(0.0, dec, ProperMotion.MAS_PER_DEGREE, 0.0, 1.0, out)
            out[0]
        }
        assertEquals(1.0, shifts[0], 1e-9)
        assertEquals(1.0 / cos(Math.toRadians(60.0)), shifts[1], 1e-9)
        assertEquals(1.0 / cos(Math.toRadians(80.0)), shifts[2], 1e-9)
        assertTrue("it grows monotonically toward the pole", shifts[0] < shifts[1] && shifts[1] < shifts[2])
    }

    // ---- the pole floor ---------------------------------------------------------------------

    @Test
    fun `a star at the pole gets a bounded shift rather than an arbitrary one`() {
        // ⚠️ cos(90 deg) in binary floating point is 6.1e-17 rather than zero, so this never divides
        // by zero and never returns NaN — which means "the answer is finite" is NOT the property
        // under test. What the floor buys is that the answer is BOUNDED and deterministic: without
        // it, 100 mas/yr for a year becomes 100 / (3.6e6 * 6.1e-17) = 4.5e11 degrees, wrapped into
        // the circle at a precision of about a ten-thousandth of a degree — a number that depends
        // entirely on how many digits of pi/2 a double happens to land on. Two stars a
        // hundred-millionth of a degree apart would be flung to unrelated right ascensions.
        //
        // With the floor: 100 * 1 / (3.6e6 * 1e-6) = 100 / 3.6 = 27.777... degrees.
        ProperMotion.carry(0.0, 90.0, 100.0, 0.0, 1.0, out)
        assertEquals(100.0 / 3.6, out[0], 1e-9)
    }

    @Test
    fun `the floor does not engage for a star merely close to the pole`() {
        // cos(89.99) is 1.75e-4, comfortably above the 1e-6 floor, so the real cosine is used and
        // the answer is the honest one rather than the clamped one.
        //
        // ⚠️ The single assertion below does discriminate, and it is worth saying which two values
        // it separates: the real cosine gives 1 / 1.75e-4 = 5729.6 degrees, which wraps to 329.58,
        // where a floor that engaged here would give 1 / 1e-6 = a million degrees, wrapping to
        // exactly 280.0. My first version of this test also asserted the answer was not a million,
        // which can NEVER fail — every answer is wrapped into [0, 360) before it is returned. An
        // assertion that cannot fail is worse than no assertion, so it is gone rather than fixed.
        val dec = 89.99
        ProperMotion.carry(0.0, dec, 3_600_000.0, 0.0, 1.0, out)
        val real = 1.0 / cos(Math.toRadians(dec))
        assertEquals(real % 360.0, out[0], 1e-6)
    }

    // ---- range ------------------------------------------------------------------------------

    @Test
    fun `right ascension wraps rather than running past the circle`() {
        // Forwards past 360 and backwards past 0, since a star near the meridian does both.
        ProperMotion.carry(359.5, 0.0, ProperMotion.MAS_PER_DEGREE, 0.0, 1.0, out)
        assertEquals("359.5 + 1 wraps to 0.5", 0.5, out[0], 1e-9)

        ProperMotion.carry(0.5, 0.0, -ProperMotion.MAS_PER_DEGREE, 0.0, 1.0, out)
        assertEquals("0.5 - 1 wraps to 359.5", 359.5, out[0], 1e-9)
    }

    @Test
    fun `declination clamps at the pole rather than running past it`() {
        // ⚠️ A clamp rather than a reflection, and the difference matters: a star carried past the
        // pole really does come back down the other side with its right ascension 180 degrees
        // round, which this does not model. Nothing in the real sky reaches it — the whole
        // catalogue's fastest star would need forty thousand years — so the clamp is a guard on
        // absurd input rather than an approximation to something physical.
        ProperMotion.carry(10.0, 89.9, 0.0, ProperMotion.MAS_PER_DEGREE, 1.0, out)
        assertEquals(90.0, out[1], 1e-12)

        ProperMotion.carry(10.0, -89.9, 0.0, -ProperMotion.MAS_PER_DEGREE, 1.0, out)
        assertEquals(-90.0, out[1], 1e-12)
    }

    // ---- direction and reuse ----------------------------------------------------------------

    @Test
    fun `carrying forward then back returns the star to where it started`() {
        // Negative years is what a cross-match against an older catalogue wants, and it has to be
        // the exact inverse or the two directions would disagree about the same star.
        //
        // ⚠️ Not exactly reversible at high declination, and knowing why matters: the RA step is
        // computed from the cosine at the STARTING declination, so a star that also moves in
        // declination is carried back using a slightly different divisor. That is a property of the
        // linear model, not a defect — over a century the residual here is under a milliarcsecond.
        val ra = 45.0
        val dec = 70.0
        ProperMotion.carry(ra, dec, 500.0, 300.0, 100.0, out)
        val movedRa = out[0]
        val movedDec = out[1]
        ProperMotion.carry(movedRa, movedDec, 500.0, 300.0, -100.0, out)
        assertEquals(ra, out[0], 1e-4)
        assertEquals(dec, out[1], 1e-12)
        assertTrue("it really did move", abs(movedDec - dec) > 0.008)
    }

    @Test
    fun `a star with no measured motion never moves`() {
        // Four entries in the bright catalogue have no measured proper motion and the deep set has
        // its own; zero is what the builders write for them, and absent must not become invented.
        ProperMotion.carry(123.456, -41.5, 0.0, 0.0, 5_000.0, out)
        assertEquals(123.456, out[0], 0.0)
        assertEquals(-41.5, out[1], 0.0)
    }

    @Test
    fun `the scratch array can be reused across stars with no residue`() {
        // The documented call pattern: one array hoisted outside a loop over tens of thousands of
        // records. Each answer has to depend only on its own inputs.
        ProperMotion.carry(10.0, 20.0, 1_000.0, 500.0, 10.0, out)
        val first = out.copyOf()
        ProperMotion.carry(200.0, -30.0, 0.0, 0.0, 10.0, out)
        assertEquals("the second star did not inherit the first", 200.0, out[0], 0.0)
        assertEquals(-30.0, out[1], 0.0)
        ProperMotion.carry(10.0, 20.0, 1_000.0, 500.0, 10.0, out)
        assertEquals("and the first still answers the same", first[0], out[0], 0.0)
        assertEquals(first[1], out[1], 0.0)
    }

    // ---- Julian years -----------------------------------------------------------------------

    @Test
    fun `years are julian years and not calendar ones`() {
        // J2000.0 is JD 2451545.0, which `julianDate` reaches at
        // (2451545.0 - 2440587.5) * 86_400_000 = 946_728_000_000 ms.
        val j2000Ms = 946_728_000_000L
        assertEquals(2000.0, Ephemeris.julianYear(j2000Ms), 1e-12)

        // ⚠️ A Julian year is 365.25 days by definition, not a calendar year. Counting calendar
        // years would drift by a day every four, which over the 26 years since J2000 is six days.
        val oneJulianYearMs = j2000Ms + (365.25 * 86_400_000.0).toLong()
        assertEquals(2001.0, Ephemeris.julianYear(oneJulianYearMs), 1e-9)

        val oneCalendarYearMs = j2000Ms + 365L * 86_400_000L
        assertNotEquals(
            "a calendar year is not a Julian one",
            2001.0,
            Ephemeris.julianYear(oneCalendarYearMs),
            1e-6,
        )
    }

    @Test
    fun `the catalogue epoch is subtracted so a deeper catalogue carries less`() {
        // The two bundled catalogues are J2000.0 and J2016.0, so at any instant the deep one is
        // carried exactly sixteen Julian years less far than the bright one.
        val now = 1_800_000_000_000L
        val bright = ProperMotion.yearsSince(2000.0, now)
        val deep = ProperMotion.yearsSince(2016.0, now)
        assertEquals(16.0, bright - deep, 1e-12)
        assertTrue("both are in the future of their own epochs", deep > 0.0 && bright > deep)
    }

    // ---- why this had to exist --------------------------------------------------------------

    @Test
    fun `an ordinary bright star breaches the occultation budget in under a decade`() {
        // ⚠️ This is the measurement that made the occultation search carry proper motion.
        // `Occultations.STAR_UNCERTAINTY_DEG` is two arcseconds, measured for the precession
        // rotation alone against DE421; a star's own motion is a separate error stacked on top.
        // Regulus moves about 248 mas/yr, so it eats the whole budget in 2000 / 248 = 8.1 years,
        // and over the 26 since J2000 it is more than three times over.
        val budgetArcsec = Occultations.STAR_UNCERTAINTY_DEG * 3600.0
        assertEquals(2.0, budgetArcsec, 1e-12)

        val ra = 152.09
        val dec = 11.97
        ProperMotion.carry(ra, dec, -248.0, 6.0, 26.0, out)
        val movedArcsec = separationArcsec(ra, dec, out[0], out[1])
        assertTrue(
            "26 years of Regulus's own motion is $movedArcsec arcsec, past a $budgetArcsec budget",
            movedArcsec > 3.0 * budgetArcsec,
        )
    }

    /**
     * Small-angle separation in arcseconds, with the right ascension projected onto the sky.
     *
     * ⚠️ Deliberately NOT [Ephemeris.angularSeparationDeg]: that ends in `acos`, which loses half
     * its significant figures for two nearly identical directions and would report an arcsecond
     * gap with two digits of noise in it.
     */
    private fun separationArcsec(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val dRa = (ra2 - ra1) * cos(Math.toRadians((dec1 + dec2) / 2.0))
        return hypot(dRa, dec2 - dec1) * 3600.0
    }
}
