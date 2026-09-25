package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.SkyProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A tap names only what the chart draws: the two cuts `collectStars` makes, applied to the stars
 * within reach of the finger.
 *
 * ⚠️ Every fixture asserts the premise it depends on before the assertion that matters — that the
 * air really does push the star past the cut, that it really does reorder the pair — because a
 * test whose fixture never reaches the branch is green for the wrong reason, which this project
 * has recorded as the third of the ways a green test proves nothing.
 */
class StarHitTestTest {

    private val lat = 42.7875
    private val lon = -86.1089
    private val at = 1_790_128_800_000L
    private val view = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 20.0, fovDeg = 80.0)

    /** Three degrees: a generous fingertip, so a pair of stars a couple of degrees apart both count. */
    private val tolerance = cos(Math.toRadians(3.0))

    private val vacuum get() = SkyFrame.of(view, lat, lon, at, refracting = false)
    private val air get() = SkyFrame.of(view, lat, lon, at, refracting = true)

    /** Add a star at a TRUE altitude and azimuth; returns its catalogue direction. */
    private fun starAt(layer: StarLayer, altDeg: Double, azDeg: Double, magnitude: Float): DoubleArray {
        val eq = SkyFrame.catalogueOf(altDeg, azDeg, lat, lon, at)
        layer.add(eq.rightAscensionDeg, eq.declinationDeg, magnitude, 0)
        return SkyProjection.equatorialVector(eq.rightAscensionDeg, eq.declinationDeg)
    }

    private fun between(a: DoubleArray, b: DoubleArray): DoubleArray {
        val x = a[0] + b[0]
        val y = a[1] + b[1]
        val z = a[2] + b[2]
        val n = sqrt(x * x + y * y + z * z)
        return doubleArrayOf(x / n, y / n, z / n)
    }

    @Test
    fun `a star past the catalogue cut is never named, however exactly the finger lands on it`() {
        val layer = StarLayer()
        val t = starAt(layer, 60.0, 180.0, 9.0f)
        assertNull(StarHitTest.nearestDrawnStar(layer, t, tolerance, vacuum, 8.5))
        // The same finger, a cut it is inside: named.
        assertEquals(0, StarHitTest.nearestDrawnStar(layer, t, tolerance, vacuum, 9.5))
    }

    @Test
    fun `a star the air dims past the cut is not named with the atmosphere on, and is with it off`() {
        val layer = StarLayer()
        // Two degrees up, a magnitude inside the cut: the air takes far more than that there.
        val t = starAt(layer, 2.0, 180.0, 7.5f)
        val frame = air
        val taken = frame.extinctionMag(frame.sinAltitude(t[0], t[1], t[2]))
        assertTrue("the fixture must be dimmed PAST the cut, and the air took only $taken", taken > 1.0)
        assertNull(StarHitTest.nearestDrawnStar(layer, t, tolerance, frame, 8.5))
        // With the atmosphere off the second cut is the first one repeated, so it is named.
        assertEquals(0, StarHitTest.nearestDrawnStar(layer, t, tolerance, vacuum, 8.5))
        // And overhead the air takes nothing worth the cut: the same star high up is named with
        // the atmosphere on.
        val high = StarLayer()
        val u = starAt(high, 85.0, 180.0, 7.5f)
        assertEquals(0, StarHitTest.nearestDrawnStar(high, u, tolerance, frame, 8.5))
    }

    @Test
    fun `the brightest DRAWN star wins, and at the horizon the air can reorder a pair`() {
        val layer = StarLayer()
        val a = starAt(layer, 0.5, 180.0, 4.0f) // index 0: the brighter catalogue entry, lowest
        val b = starAt(layer, 2.9, 180.0, 5.0f) // index 1
        val t = between(a, b)
        // Both are within reach of the finger, which is the premise of a tie-break.
        assertTrue(t[0] * a[0] + t[1] * a[1] + t[2] * a[2] >= tolerance)
        assertTrue(t[0] * b[0] + t[1] * b[1] + t[2] * b[2] >= tolerance)
        // In a vacuum the catalogue order stands.
        assertEquals(0, StarHitTest.nearestDrawnStar(layer, t, tolerance, vacuum, 8.5))
        // With the air on, the lower star loses more than the magnitude it had in hand.
        val frame = air
        val drawnA = 4.0 + frame.extinctionMag(frame.sinAltitude(a[0], a[1], a[2]))
        val drawnB = 5.0 + frame.extinctionMag(frame.sinAltitude(b[0], b[1], b[2]))
        assertTrue("the fixture must be reordered by the air: $drawnA vs $drawnB", drawnB < drawnA)
        assertTrue("and both must still be drawn: $drawnA, $drawnB", drawnA <= 8.5 && drawnB <= 8.5)
        assertEquals(1, StarHitTest.nearestDrawnStar(layer, t, tolerance, frame, 8.5))
    }

    @Test
    fun `nothing within reach names nothing`() {
        val layer = StarLayer()
        starAt(layer, 60.0, 180.0, 2.0f)
        val elsewhere = SkyFrame.catalogueOf(60.0, 190.0, lat, lon, at)
        val t = SkyProjection.equatorialVector(elsewhere.rightAscensionDeg, elsewhere.declinationDeg)
        assertNull(StarHitTest.nearestDrawnStar(layer, t, tolerance, vacuum, 8.5))
    }
}
