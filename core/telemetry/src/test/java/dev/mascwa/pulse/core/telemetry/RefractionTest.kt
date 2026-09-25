package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **Every expectation here was computed by running the shipped functions before it was written**
 * (`scratchpad/sky/RefractionProbe.kt`), which is the discipline this file exists under. Where a
 * number is quoted it is the probe's, to the digits shown; the tolerances are the probe's measured
 * worst cases with a little headroom, and a wider one would be hiding a regression rather than
 * tolerating noise.
 */
class RefractionTest {

    private val arcsec = 1.0 / 3600.0

    // -------------------------------------------------------------- the formula itself

    @Test
    fun `Bennett at the apparent horizon is the classical thirty-four and a half arcminutes`() {
        // Meeus 16.3's own headline number, and the one every table of refraction opens with.
        assertEquals(34.478, Refraction.bennettDeg(0.0) * 60.0, 0.001)
    }

    @Test
    fun `the bend at a true altitude is the fixed point of Bennett`() {
        // apparent = true + R(apparent), to a thousandth of a milliarcsecond — the defining property.
        for (h in listOf(-0.9, 0.0, 0.5, 2.0, 5.0, 10.0, 30.0, 45.0, 70.0, 89.0)) {
            val apparent = Refraction.apparentOf(h)
            assertEquals("fixed point at $h", apparent, h + Refraction.bennettDeg(apparent), 1e-9)
        }
        // And the values the KDoc quotes, from the probe, so the prose cannot drift from the code.
        assertEquals(28.933, Refraction.bendingDeg(0.0) * 60.0, 0.001)
        assertEquals(9.635, Refraction.bendingDeg(5.0) * 60.0, 0.001)
        assertEquals(5.347, Refraction.bendingDeg(10.0) * 60.0, 0.001)
        assertEquals(0.994, Refraction.bendingDeg(45.0) * 60.0, 0.001)
        assertEquals(0.965, Refraction.bendingDeg(89.0) * 3600.0, 0.001)
    }

    @Test
    fun `the bend is never negative and vanishes at the zenith`() {
        // ⚠️ Bennett's argument passes 90° before the altitude does, so unclamped it answers about
        // −5″ at the zenith. A negative bend would push a star DOWN and break the monotonicity below.
        assertEquals(0.0, Refraction.bennettDeg(90.0), 0.0)
        assertEquals(0.0, Refraction.bendingDeg(90.0), 0.0)
        assertEquals(0.0, Refraction.fastBendingDeg(90.0), 0.0)
        // Below the formula's pole it says nothing rather than infinity.
        assertEquals(0.0, Refraction.bennettDeg(-5.0), 0.0)
        var h = -5.0
        while (h <= 90.0) {
            assertTrue("negative bend at $h", Refraction.bendingDeg(h) >= 0.0)
            h += 0.05
        }
    }

    // -------------------------------------------------------------- the two properties the tap needs

    @Test
    fun `apparent altitude is monotonic in true altitude over the whole range`() {
        // A fold anywhere would draw two stars at one altitude and give the inverse two answers.
        // Measured: the flattest slope is 0.800 at the top of the taper (true −1°), where the taper's
        // own slope subtracts from one; everywhere else it is closer to unity.
        var prev = Refraction.apparentOf(-5.0)
        var h = -5.0
        var minSlope = Double.MAX_VALUE
        val step = 0.001
        while (h < 90.0) {
            h += step
            val a = Refraction.apparentOf(h)
            assertTrue("not increasing at $h", a > prev)
            minSlope = minOf(minSlope, (a - prev) / step)
            prev = a
        }
        assertTrue("slope $minSlope", minSlope > 0.5)
    }

    @Test
    fun `the inverse round-trips to the last bit in both directions`() {
        // Measured 0.000″ either way — the bisection runs to double precision. A billionth of a
        // degree is 3.6 microarcseconds, a hundred thousand times finer than any pixel.
        var h = -5.0
        while (h <= 90.0) {
            assertEquals("true→apparent→true at $h", h, Refraction.trueOf(Refraction.apparentOf(h)), 1e-9)
            h += 0.01
        }
        var a = -5.0
        while (a <= 90.0) {
            assertEquals("apparent→true→apparent at $a", a, Refraction.apparentOf(Refraction.trueOf(a)), 1e-9)
            a += 0.01
        }
        // Below the taper there is nothing to invert.
        assertEquals(-4.5, Refraction.trueOf(-4.5), 0.0)
    }

    // -------------------------------------------------------------- the per-star path

    @Test
    fun `the table agrees with the exact form to a tenth of an arcsecond everywhere`() {
        // Measured worst: 0.065″ at true −0.37°, 0.061″ just above the horizon — the curvature of
        // Bennett over a 0.05° step, and nothing at all higher up. Sampled off the nodes on purpose.
        var h = -4.5
        var worst = 0.0
        while (h <= 90.0) {
            worst = maxOf(worst, abs(Refraction.fastBendingDeg(h) - Refraction.bendingDeg(h)))
            h += 0.0007
        }
        assertTrue("worst ${worst * 3600.0}\"", worst < 0.1 * arcsec)
    }

    @Test
    fun `both taper joints sit exactly on table nodes`() {
        // ⚠️ The one place the bend has a kink is the top of the taper; a linear interpolation that
        // straddled it would be about an arcminute wrong there. Whole multiples of the step from the
        // table's origin means it never is, and the bottom joint and the top of the range likewise.
        fun whole(x: Double) = abs(x - Math.rint(x)) < 1e-9
        val step = Refraction.TABLE_STEP_DEG
        assertTrue(whole((Refraction.TAPER_TOP_DEG - Refraction.TAPER_BOTTOM_DEG) / step))
        assertTrue(whole((90.0 - Refraction.TAPER_BOTTOM_DEG) / step))
    }

    @Test
    fun `the taper is continuous at both joints, linear between them and zero below`() {
        val eps = 1e-9
        val top = Refraction.bendingDeg(Refraction.TAPER_TOP_DEG)
        assertEquals(top, Refraction.bendingDeg(Refraction.TAPER_TOP_DEG - eps), 1e-6)
        assertEquals(top, Refraction.bendingDeg(Refraction.TAPER_TOP_DEG + eps), 1e-6)
        assertEquals(0.0, Refraction.bendingDeg(Refraction.TAPER_BOTTOM_DEG), 0.0)
        assertEquals(0.0, Refraction.bendingDeg(Refraction.TAPER_BOTTOM_DEG + eps), 1e-6)
        assertEquals(0.0, Refraction.bendingDeg(Refraction.TAPER_BOTTOM_DEG - 1.0), 0.0)
        // Halfway down the taper is exactly half the bend at its top: measured 0.6550° at −1° and
        // 0.4366° at −2°, which is two thirds — so the midpoint (−2.5°) is one half.
        assertEquals(0.5 * top, Refraction.bendingDeg(-2.5), 1e-12)
        assertEquals(0.654957, top, 1e-6)
    }

    @Test
    fun `the largest bend is the one at the top of the taper, and nothing anywhere exceeds it`() {
        // The cull margin a frame pads its cone by rests on this being the maximum over the whole
        // range — above the taper the bend only falls with altitude, below it the taper only falls
        // to zero. Sampled off the nodes, like the table test.
        assertEquals(Refraction.bendingDeg(Refraction.TAPER_TOP_DEG), Refraction.MAX_BEND_DEG, 0.0)
        var h = -5.0
        while (h <= 90.0) {
            assertTrue("bend at $h exceeds the maximum", Refraction.bendingDeg(h) <= Refraction.MAX_BEND_DEG)
            assertTrue("table at $h exceeds the maximum", Refraction.fastBendingDeg(h) <= Refraction.MAX_BEND_DEG)
            h += 0.0007
        }
    }

    @Test
    fun `the true altitude of the apparent horizon is where the eye's horizon is`() {
        // Defined as the true altitude that the air lifts to exactly zero; a little over half a
        // degree down, between the bend at zero (0.48°) and the bend a degree down (0.65°).
        val h = Refraction.TRUE_AT_APPARENT_HORIZON_DEG
        assertEquals(0.0, Refraction.apparentOf(h), 1e-9)
        assertTrue("$h", h > -0.7 && h < -0.45)
    }

    @Test
    fun `the vector guards match the angles they stand for`() {
        assertEquals(sin(Math.toRadians(Refraction.TAPER_BOTTOM_DEG)), Refraction.SIN_TAPER_BOTTOM, 0.0)
        // Where the frame stops bending, the bend is already under a tenth of an arcsecond
        // (measured 0.023″ at 89.9°), so skipping it there moves nothing.
        val noBendAlt = Math.toDegrees(asin(Refraction.SIN_NO_BEND))
        assertTrue(noBendAlt > 89.8)
        assertTrue(Refraction.bendingDeg(noBendAlt) < 0.1 * arcsec)
    }

    // -------------------------------------------------------------- the rotation on the sphere

    private fun unit(altDeg: Double, azDeg: Double): DoubleArray {
        val alt = Math.toRadians(altDeg)
        val az = Math.toRadians(azDeg)
        return doubleArrayOf(cos(alt) * sin(az), cos(alt) * cos(az), sin(alt))
    }

    @Test
    fun `lift raises a direction by exactly the bend, keeps it a unit vector and leaves the azimuth alone`() {
        val v = unit(10.0, 40.0)
        val bend = Refraction.bendingDeg(10.0)
        val out = DoubleArray(3)
        Refraction.lift(v[0], v[1], v[2], 0.0, 0.0, 1.0, v[2], bend, out)
        val n = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
        assertEquals(1.0, n, 1e-12)
        assertEquals(10.0 + bend, Math.toDegrees(asin(out[2] / n)), 1e-7)
        assertEquals(40.0, Math.toDegrees(atan2(out[0], out[1])), 1e-9)
        // A negative bend is the inverse rotation, which is what the tap wants.
        val back = DoubleArray(3)
        Refraction.lift(out[0], out[1], out[2], 0.0, 0.0, 1.0, out[2], -bend, back)
        assertEquals(10.0, Math.toDegrees(asin(back[2])), 1e-7)
    }

    @Test
    fun `lift works in any frame, not only the one where the zenith is the z axis`() {
        // The map's zenith is a unit vector in the catalogue's equatorial frame, nowhere near +z.
        val zx = 0.3
        val zy = -0.5
        val zz = sqrt(1.0 - zx * zx - zy * zy)
        // A direction ten degrees above that horizon: a perpendicular to the zenith, tilted up.
        val pn = sqrt(zy * zy + zx * zx)
        val ux = zy / pn
        val uy = -zx / pn
        val alt = Math.toRadians(10.0)
        val wx = cos(alt) * ux + sin(alt) * zx
        val wy = cos(alt) * uy + sin(alt) * zy
        val wz = sin(alt) * zz
        val s = wx * zx + wy * zy + wz * zz
        assertEquals(10.0, Math.toDegrees(asin(s)), 1e-9)
        val bend = Refraction.bendingDeg(10.0)
        val out = DoubleArray(3)
        Refraction.lift(wx, wy, wz, zx, zy, zz, s, bend, out)
        val lifted = out[0] * zx + out[1] * zy + out[2] * zz
        assertEquals(10.0 + bend, Math.toDegrees(asin(lifted)), 1e-7)
    }
}
