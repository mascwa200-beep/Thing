package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.SkyProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A tap names only what the chart draws: the cuts `collectStars` makes and the two the draw pass
 * makes after it, applied to the stars within reach of the finger.
 *
 * ⚠️ Every fixture asserts the premise it depends on before the assertion that matters — that the
 * air really does push the star past the cut, that it really does reorder the pair, that the star
 * really is past the edge — because a test whose fixture never reaches the branch is green for the
 * wrong reason, which this project has recorded as the third of the ways a green test proves
 * nothing.
 */
class StarHitTestTest {

    private val lat = 42.7875
    private val lon = -86.1089
    private val at = 1_790_128_800_000L
    private val view = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 20.0, fovDeg = 80.0)

    /** Three degrees: a generous fingertip, so a pair of stars a couple of degrees apart both count. */
    private val tolerance = cos(Math.toRadians(3.0))

    /**
     * `SkyRenderer.EDGE_MARGIN`, restated because that object is Compose-bound and this test is
     * not. The hit test takes it as a parameter for the same reason, so what is under test here is
     * that the margin handed in is honoured, not the number itself.
     */
    private val margin = 0.06

    /**
     * A surface that reaches the whole sky, for the tests that are not about the edge — every
     * fixture below sits within 45° of the view centre, which is under a unit at this field, but a
     * test about magnitude should not be one that could also fail on geometry.
     */
    private val wide = SkyProjection.Viewport(4.0, 4.0)

    private val vacuum get() = SkyFrame.of(view, lat, lon, at, refracting = false)
    private val air get() = SkyFrame.of(view, lat, lon, at, refracting = true)

    private fun nearest(
        layer: StarLayer,
        target: DoubleArray,
        frame: SkyFrame,
        limit: Double,
        viewport: SkyProjection.Viewport = wide,
        ground: Boolean = false,
    ): Int? = StarHitTest.nearestDrawnStar(layer, target, tolerance, frame, limit, viewport, margin, ground)

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

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    @Test
    fun `a star past the catalogue cut is never named, however exactly the finger lands on it`() {
        val layer = StarLayer()
        val t = starAt(layer, 60.0, 180.0, 9.0f)
        assertNull(nearest(layer, t, vacuum, 8.5))
        // The same finger, a cut it is inside: named.
        assertEquals(0, nearest(layer, t, vacuum, 9.5))
    }

    @Test
    fun `a star the air dims past the cut is not named with the atmosphere on, and is with it off`() {
        val layer = StarLayer()
        // Two degrees up, a magnitude inside the cut: the air takes far more than that there.
        val t = starAt(layer, 2.0, 180.0, 7.5f)
        val frame = air
        val taken = frame.extinctionMag(frame.sinAltitude(t[0], t[1], t[2]))
        assertTrue("the fixture must be dimmed PAST the cut, and the air took only $taken", taken > 1.0)
        assertNull(nearest(layer, t, frame, 8.5))
        // With the atmosphere off the second cut is the first one repeated, so it is named.
        assertEquals(0, nearest(layer, t, vacuum, 8.5))
        // And overhead the air takes nothing worth the cut: the same star high up is named with
        // the atmosphere on.
        val high = StarLayer()
        val u = starAt(high, 85.0, 180.0, 7.5f)
        assertEquals(0, nearest(high, u, frame, 8.5))
    }

    @Test
    fun `the brightest DRAWN star wins, and at the horizon the air can reorder a pair`() {
        val layer = StarLayer()
        val a = starAt(layer, 0.5, 180.0, 4.0f) // index 0: the brighter catalogue entry, lowest
        val b = starAt(layer, 2.9, 180.0, 5.0f) // index 1
        val t = between(a, b)
        // Both are within reach of the finger, which is the premise of a tie-break.
        assertTrue(dot(t, a) >= tolerance)
        assertTrue(dot(t, b) >= tolerance)
        // In a vacuum the catalogue order stands.
        assertEquals(0, nearest(layer, t, vacuum, 8.5))
        // With the air on, the lower star loses more than the magnitude it had in hand.
        val frame = air
        val drawnA = 4.0 + frame.extinctionMag(frame.sinAltitude(a[0], a[1], a[2]))
        val drawnB = 5.0 + frame.extinctionMag(frame.sinAltitude(b[0], b[1], b[2]))
        assertTrue("the fixture must be reordered by the air: $drawnA vs $drawnB", drawnB < drawnA)
        assertTrue("and both must still be drawn: $drawnA, $drawnB", drawnA <= 8.5 && drawnB <= 8.5)
        assertEquals(1, nearest(layer, t, frame, 8.5))
    }

    @Test
    fun `nothing within reach names nothing`() {
        val layer = StarLayer()
        starAt(layer, 60.0, 180.0, 2.0f)
        val elsewhere = SkyFrame.catalogueOf(60.0, 190.0, lat, lon, at)
        val t = SkyProjection.equatorialVector(elsewhere.rightAscensionDeg, elsewhere.declinationDeg)
        assertNull(nearest(layer, t, vacuum, 8.5))
    }

    /**
     * ⚠️ The extincted cut is the draw pass's cut to the BIT. A first version rounded the drawn
     * magnitude to a float before comparing it, and a float has half an ulp of slack around 8.5,
     * so a star the draw pass rejected by under a millionth of a magnitude was named by the tap.
     * The fixture is searched for rather than written down — an altitude whose extinction lands
     * the star inside that half-ulp — and the search has to succeed, or the test says so instead of
     * passing on a fixture that never reached the branch.
     */
    @Test
    fun `a star the draw pass rejects by less than a float can tell is not named`() {
        val frame = air
        val limit = 8.5
        var layer: StarLayer? = null
        var target: DoubleArray? = null
        var alt = 1.0
        while (alt < 45.0 && layer == null) {
            val eq = SkyFrame.catalogueOf(alt, 180.0, lat, lon, at)
            val v = SkyProjection.equatorialVector(eq.rightAscensionDeg, eq.declinationDeg)
            val taken = frame.extinctionMag(frame.sinAltitude(v[0], v[1], v[2]))
            // The float nearest to "exactly on the cut": half the time it rounds up, and then the
            // Double sum is over the cut by less than a float can represent.
            val m0 = (limit - taken).toFloat()
            val drawn = m0 + taken
            if (drawn > limit && drawn.toFloat() <= limit) {
                layer = StarLayer().also { it.add(eq.rightAscensionDeg, eq.declinationDeg, m0, 0) }
                target = v
            }
            alt += 0.05
        }
        assertNotNull(
            "no altitude between 1° and 45° put a star inside the float's half-ulp — the fixture cannot reach the branch",
            layer,
        )
        // collectStars would not draw it (its Double sum is over the cut), so the tap must not name it.
        assertNull(nearest(layer!!, target!!, frame, limit))
    }

    /**
     * With the GROUND on the draw pass does not draw the below-horizon batches at all and paints
     * an opaque fill over where they would be; a star under it is not there to name, however
     * bright. With it off the same star is drawn dimmed, and named.
     */
    @Test
    fun `with the ground on, a star under the drawn horizon is not named, and with it off it is`() {
        val layer = StarLayer()
        val t = starAt(layer, -5.0, 180.0, 2.0f)
        for (frame in listOf(vacuum, air)) {
            val s = frame.sinAltitude(t[0], t[1], t[2])
            assertTrue("the fixture must be under the drawn horizon", !frame.aboveHorizon(s))
            assertEquals(0, nearest(layer, t, frame, 8.5, ground = false))
            assertNull(nearest(layer, t, frame, 8.5, ground = true))
        }
        // A star that is UP is named whatever the ground is doing.
        val up = StarLayer()
        val u = starAt(up, 30.0, 180.0, 2.0f)
        assertEquals(0, nearest(up, u, vacuum, 8.5, ground = true))
    }

    /**
     * The tap tolerance is an angle and the screen's edge is a line, and at the top of a portrait
     * screen the projection stretches a degree over more of the surface than in the middle — so a
     * star inside the tolerance of a finger at the edge can lie past the margin `collectStars`
     * draws to. Not drawn, so not named; and named on a surface tall enough to reach it.
     */
    @Test
    fun `a star inside the tap tolerance but past the edge of the screen is not named`() {
        // A phone held upright: one unit wide, 2340/1080 units tall.
        val portrait = SkyProjection.Viewport(1.0, 2340.0 / 1080.0)
        val level = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 0.0, fovDeg = 40.0)
        val frame = SkyFrame.of(level, lat, lon, at, refracting = false)
        // The finger at the very top edge of the surface.
        val (azT, altT) = SkyProjection.unproject(0.0, -portrait.halfHeight, level)
        val tapEq = SkyFrame.catalogueOf(altT, azT, lat, lon, at)
        val tap = SkyProjection.equatorialVector(tapEq.rightAscensionDeg, tapEq.declinationDeg)
        // A bright star two degrees further up: inside a three-degree fingertip, off the top.
        val layer = StarLayer()
        val star = starAt(layer, altT + 2.0, azT, 2.0f)
        assertTrue("the fixture must be within reach of the finger", dot(tap, star) >= tolerance)
        val p = frame.project(star[0], star[1], star[2])
        assertTrue("the fixture must be past the margin: y = ${p.y}", !p.onScreen(portrait, margin))
        assertNull(nearest(layer, tap, frame, 8.5, viewport = portrait))
        // A surface tall enough to reach it: drawn, so named.
        val taller = SkyProjection.Viewport(1.0, 3.0)
        assertTrue(p.onScreen(taller, margin))
        assertEquals(0, nearest(layer, tap, frame, 8.5, viewport = taller))
    }
}
