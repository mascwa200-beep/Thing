package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **Every expectation here was computed by running the shipped functions before it was written**,
 * which is the discipline this file exists under and which caught two of my own claims in the KDoc
 * next door. Where a value is exact rather than merely close, it says so and asserts exactly — an
 * exact assertion that has been checked is a much stronger gate than a loose one that has not.
 */
class ReferenceCirclesTest {

    private val obliquityNow = 23.4392911

    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun declinationOf(v: DoubleArray) = Math.toDegrees(asin(v[2].coerceIn(-1.0, 1.0)))

    // ------------------------------------------------------------------ the traversal

    @Test
    fun `the runs tile the circle exactly once`() {
        // ⚠️ The load-bearing invariant of the whole file, and the one that is silently breakable:
        // ARCS must divide 360, and STEP_DEG must divide the span each run covers. Neither is
        // enforced by a type, and getting either wrong leaves a visible gap every span, or an arc
        // count that no longer matches the preallocated buffer.
        assertEquals(360.0, ReferenceCircles.ARC_SPAN_DEG * ReferenceCircles.ARCS, 0.0)
        assertEquals(
            ReferenceCircles.ARC_SPAN_DEG,
            (ReferenceCircles.PER_ARC - 1) * ReferenceCircles.STEP_DEG,
            0.0,
        )
    }

    @Test
    fun `each run ends exactly where the next begins`() {
        // Measured: with 12 runs of 30 degrees at a 2-degree step every join is EXACT in binary
        // floating point — the longitudes are small integers, so `k*30 + 30` and `(k+1)*30` are the
        // same double. Asserted exactly rather than with a tolerance, because a tolerance here would
        // pass a step size that leaves a real hairline gap.
        for (arc in 0 until ReferenceCircles.ARCS - 1) {
            assertEquals(
                "join after run $arc",
                ReferenceCircles.longitudeOf(arc + 1, 0),
                ReferenceCircles.longitudeOf(arc, ReferenceCircles.PER_ARC - 1),
                0.0,
            )
        }
    }

    @Test
    fun `the last run closes the circle and nothing runs past it`() {
        assertEquals(0.0, ReferenceCircles.longitudeOf(0, 0), 0.0)
        assertEquals(
            360.0,
            ReferenceCircles.longitudeOf(ReferenceCircles.ARCS - 1, ReferenceCircles.PER_ARC - 1),
            0.0,
        )
    }

    @Test
    fun `the vertex count is exactly what a caller preallocates`() {
        // A caller sizes its buffer ARCS * PER_ARC and fills it with two integer loops, so this
        // product IS the count. 12 * 16 = 192, confirmed by running the shipped fill.
        assertEquals(192, ReferenceCircles.ARCS * ReferenceCircles.PER_ARC)
    }

    // ------------------------------------------------------------------ the equator

    @Test
    fun `the equator lies exactly on declination zero`() {
        val v = DoubleArray(3)
        var lon = 0.0
        while (lon <= 360.0) {
            ReferenceCircles.equatorPoint(lon, v)
            // Measured over the whole circle: the largest |z| is exactly 0.0, because the function
            // assigns the literal rather than computing a sine that would land near it.
            assertEquals("z at $lon", 0.0, v[2], 0.0)
            assertEquals("norm at $lon", 1.0, norm(v), 1e-12)
            lon += 1.0
        }
    }

    @Test
    fun `the equator is the same thing SkyProjection already computes`() {
        // The KDoc says this function is `equatorialVector` with the declination dropped. At
        // declination zero that routine multiplies by `cos(0.0)` — exactly 1.0 — so the two agree
        // BIT FOR BIT, not merely closely. Asserting exactly is what makes this a real gate: a
        // tolerance would let the two drift apart by an arcsecond and still pass.
        val v = DoubleArray(3)
        for (raDeg in 0 until 360) {
            ReferenceCircles.equatorPoint(raDeg.toDouble(), v)
            val other = SkyProjection.equatorialVector(raDeg.toDouble(), 0.0)
            assertEquals("x at $raDeg", other[0], v[0], 0.0)
            assertEquals("y at $raDeg", other[1], v[1], 0.0)
            assertEquals("z at $raDeg", other[2], v[2], 0.0)
        }
    }

    // ------------------------------------------------------------------ the ecliptic

    @Test
    fun `the ecliptic meets the equator at the equinoxes, at every obliquity`() {
        // ⚠️ The one property of these two circles a reader can check by eye, and the only thing
        // that would catch the rotation being applied about the wrong axis. Measured: at longitude
        // zero the answer is (1.0, 0.0, 0.0) EXACTLY at every obliquity; at 180 the x is exactly
        // -1.0 and the other two are at machine epsilon (about 1.2e-16), because `sin(PI)` is not
        // quite zero. Hence an exact assertion on x and a tight tolerance on the rest.
        val v = DoubleArray(3)
        for (obl in doubleArrayOf(0.0, 15.0, obliquityNow, 23.5, 40.0, 90.0)) {
            ReferenceCircles.eclipticPoint(0.0, obl, v)
            assertEquals("vernal x at $obl", 1.0, v[0], 0.0)
            assertEquals("vernal y at $obl", 0.0, v[1], 0.0)
            assertEquals("vernal z at $obl", 0.0, v[2], 0.0)

            ReferenceCircles.eclipticPoint(180.0, obl, v)
            assertEquals("autumnal x at $obl", -1.0, v[0], 0.0)
            assertTrue("autumnal y at $obl was ${v[1]}", abs(v[1]) < 1e-15)
            assertTrue("autumnal z at $obl was ${v[2]}", abs(v[2]) < 1e-15)
        }
    }

    @Test
    fun `equinox agrees with both circles`() {
        val e = DoubleArray(3)
        val c = DoubleArray(3)
        for (vernal in booleanArrayOf(true, false)) {
            ReferenceCircles.equinox(vernal, e)
            ReferenceCircles.equatorPoint(if (vernal) 0.0 else 180.0, c)
            assertEquals("equator x", e[0], c[0], 1e-15)
            assertEquals("equator y", e[1], c[1], 1e-15)
            assertEquals("equator z", e[2], c[2], 1e-15)

            ReferenceCircles.eclipticPoint(if (vernal) 0.0 else 180.0, obliquityNow, c)
            assertEquals("ecliptic x", e[0], c[0], 1e-15)
            assertEquals("ecliptic y", e[1], c[1], 1e-15)
            assertEquals("ecliptic z", e[2], c[2], 1e-15)
        }
    }

    @Test
    fun `the ecliptic reaches exactly the obliquity and no further`() {
        // Measured at 0, 23.4393, 23.5, 40 and 90 degrees: the declination at longitude 90 equals
        // the obliquity to twelve decimal places, and no point on the circle exceeds it.
        val v = DoubleArray(3)
        for (obl in doubleArrayOf(0.0, 15.0, obliquityNow, 40.0, 90.0)) {
            ReferenceCircles.eclipticPoint(90.0, obl, v)
            assertEquals("summer solstice at $obl", obl, declinationOf(v), 1e-9)
            ReferenceCircles.eclipticPoint(270.0, obl, v)
            assertEquals("winter solstice at $obl", -obl, declinationOf(v), 1e-9)

            var worst = 0.0
            var lon = 0.0
            while (lon < 360.0) {
                ReferenceCircles.eclipticPoint(lon, obl, v)
                worst = maxOf(worst, abs(declinationOf(v)))
                lon += 0.5
            }
            assertTrue("max declination $worst exceeded obliquity $obl", worst <= obl + 1e-9)
        }
    }

    @Test
    fun `the ecliptic is a great circle whose pole is at the obliquity`() {
        // Stronger than the extremes above: this says every point lies in ONE plane, and that the
        // plane's normal is tilted from the celestial pole by exactly the obliquity. Measured:
        // 23.439291100000 for an obliquity of 23.4392911, and the worst out-of-plane component is
        // 5.6e-17 — machine epsilon, so the circle really is a circle.
        val a = DoubleArray(3)
        val b = DoubleArray(3)
        val v = DoubleArray(3)
        ReferenceCircles.eclipticPoint(0.0, obliquityNow, a)
        ReferenceCircles.eclipticPoint(90.0, obliquityNow, b)
        val nx = a[1] * b[2] - a[2] * b[1]
        val ny = a[2] * b[0] - a[0] * b[2]
        val nz = a[0] * b[1] - a[1] * b[0]
        val n = sqrt(nx * nx + ny * ny + nz * nz)
        assertEquals(
            obliquityNow,
            Math.toDegrees(acos((nz / n).coerceIn(-1.0, 1.0))),
            1e-9,
        )

        var worst = 0.0
        var lon = 0.0
        while (lon < 360.0) {
            ReferenceCircles.eclipticPoint(lon, obliquityNow, v)
            worst = maxOf(worst, abs((v[0] * nx + v[1] * ny + v[2] * nz) / n))
            assertEquals("norm at $lon", 1.0, norm(v), 1e-12)
            lon += 0.5
        }
        assertTrue("worst out-of-plane $worst", worst < 1e-12)
    }

    @Test
    fun `a zero obliquity puts the ecliptic exactly on the equator`() {
        // The degenerate case, and a real check on the rotation rather than a curiosity: with no
        // tilt the two circles are the same circle. Measured over the whole sky, the worst component
        // difference is exactly 0.0.
        val e = DoubleArray(3)
        val q = DoubleArray(3)
        var lon = 0.0
        while (lon < 360.0) {
            ReferenceCircles.eclipticPoint(lon, 0.0, e)
            ReferenceCircles.equatorPoint(lon, q)
            assertEquals("x at $lon", q[0], e[0], 0.0)
            assertEquals("y at $lon", q[1], e[1], 0.0)
            assertEquals("z at $lon", q[2], e[2], 0.0)
            lon += 1.0
        }
    }

    @Test
    fun `the obliquity is honoured rather than assumed`() {
        // ⚠️ Guards the whole reason the obliquity is a parameter. It drifts about 0.013 degrees a
        // century, which is invisible now and a quarter of a degree by the year 4000 — inside what a
        // chart with a time control can be asked. A version that ignored the argument would pass
        // every test above that uses a single obliquity.
        val a = DoubleArray(3)
        val b = DoubleArray(3)
        ReferenceCircles.eclipticPoint(90.0, obliquityNow, a)
        ReferenceCircles.eclipticPoint(90.0, obliquityNow + 0.25, b)
        // The exact difference, not merely "they differ": a version that ignored the argument gives
        // zero here, and so does one that clamped it, and both would pass a looser check.
        assertEquals(0.25, declinationOf(b) - declinationOf(a), 1e-9)
    }
}
