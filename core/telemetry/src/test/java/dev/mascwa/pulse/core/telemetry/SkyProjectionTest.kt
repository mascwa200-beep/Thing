package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The projection is checked against its own definition and against geometry that can be worked out
 * by hand, because there is no external ephemeris for "where on a screen".
 *
 * ⚠️ The round trip is the strongest test here and it is deliberately run over a grid rather than at
 * a few tidy points. A projection can be exactly right on the axes and wrong in the corners — that
 * is what a sign error in the up vector looks like — and a fixture at the centre of the view would
 * never see it.
 */
class SkyProjectionTest {

    private val south = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 30.0, fovDeg = 60.0)

    @Test
    fun `the look direction lands in the middle`() {
        val p = SkyProjection.project(south.azimuthDeg, south.altitudeDeg, south)
        assertTrue(p.visible)
        assertEquals(0.0, p.x, 1e-12)
        assertEquals(0.0, p.y, 1e-12)
    }

    @Test
    fun `the edge of the field is at radius one`() {
        // Straight up from the centre by half the field: 30 degrees for a 60 degree view.
        val p = SkyProjection.project(south.azimuthDeg, south.altitudeDeg + 30.0, south)
        assertEquals(1.0, p.radius, 1e-9)
        assertTrue("up on the sky must be up the screen, so y is negative: ${p.y}", p.y < 0)
        assertTrue(p.inField)
    }

    @Test
    fun `turning right puts the sky to the right`() {
        // ⚠️ The convention that is easiest to get backwards. A star at a LARGER azimuth than the
        // view centre is further clockwise, which for somebody facing that way is to their right.
        // Reversed, the whole map is a mirror image and nothing about it looks wrong.
        //
        // ⚠️ Asserted with the view ON the horizon, where symmetry makes the answer exact. My first
        // version used the tilted view below and demanded y == 0 for a star at the same altitude,
        // which is simply not true — see the almucantar test.
        val level = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 0.0, fovDeg = 60.0)
        val p = SkyProjection.project(190.0, 0.0, level)
        assertTrue("x should be positive, was ${p.x}", p.x > 0)
        assertEquals("and exactly level, by symmetry", 0.0, p.y, 1e-12)
        val q = SkyProjection.project(170.0, 0.0, level)
        assertEquals("mirror image to the left", -p.x, q.x, 1e-12)
    }

    @Test
    fun `a line of constant altitude bends upward, because it is not a great circle`() {
        // ⚠️ This looks like a bug the first time you see it and is the projection being right. A
        // great circle leaving a point at 30 degrees horizontally is at its highest exactly there
        // and descends either way, so the almucantar — the small circle of constant altitude —
        // curves ABOVE it. On a screen whose y grows downward, that is a negative y.
        val p = SkyProjection.project(south.azimuthDeg + 10.0, south.altitudeDeg, south)
        assertTrue("x should be positive, was ${p.x}", p.x > 0)
        assertTrue("and slightly above centre, was ${p.y}", p.y < 0)
        // Small: about a third of a degree of sag over a ten-degree step at this altitude.
        val sagDeg = abs(p.y) * SkyProjection.degreesPerUnit(south)
        assertTrue("sag of $sagDeg deg is out of proportion", sagDeg in 0.1..1.0)
        // Symmetric about the centre, which a sign error in the up vector would break.
        val q = SkyProjection.project(south.azimuthDeg - 10.0, south.altitudeDeg, south)
        assertEquals(p.y, q.y, 1e-12)
    }

    @Test
    fun `higher in the sky is higher on the screen`() {
        val p = SkyProjection.project(south.azimuthDeg, south.altitudeDeg + 10.0, south)
        assertTrue("y should be negative, was ${p.y}", p.y < 0)
    }

    @Test
    fun `the point opposite the view cannot be drawn`() {
        val behind = SkyProjection.project(0.0, -30.0, south)
        assertFalse("directly behind projects to infinity", behind.visible)
    }

    @Test
    fun `something behind you is still projected, just outside the field`() {
        // ⚠️ Not the same case as the antipode. A star 100 degrees away is perfectly well defined in
        // a stereographic projection and simply lands beyond the visible circle; refusing to project
        // it would break any caller that wants to draw a hint at the edge.
        val p = SkyProjection.project(south.azimuthDeg + 100.0, south.altitudeDeg, south)
        assertTrue(p.visible)
        assertFalse("but outside the field: r=${p.radius}", p.inField)
    }

    @Test
    fun `project and unproject are inverses across the whole field`() {
        val views = listOf(
            SkyProjection.View(0.0, 0.0, 60.0),
            SkyProjection.View(180.0, 30.0, 90.0),
            SkyProjection.View(275.0, -20.0, 20.0),
            SkyProjection.View(43.0, 85.0, 120.0),
        )
        views.forEach { view ->
            for (dAz in -60..60 step 15) {
                for (dAlt in -40..40 step 10) {
                    val az = view.azimuthDeg + dAz
                    val alt = (view.altitudeDeg + dAlt).coerceIn(-89.0, 89.0)
                    val p = SkyProjection.project(az, alt, view)
                    if (!p.visible) continue
                    val (az2, alt2) = SkyProjection.unproject(p.x, p.y, view)
                    val sep = SkyProjection.separationDeg(az, alt, az2, alt2)
                    assertTrue("round trip lost $sep deg at ($az, $alt) in $view", sep < 1e-6)
                }
            }
        }
    }

    @Test
    fun `separation is exact for the cases that can be worked out by hand`() {
        assertEquals(0.0, SkyProjection.separationDeg(10.0, 20.0, 10.0, 20.0), 1e-12)
        assertEquals(90.0, SkyProjection.separationDeg(0.0, 0.0, 90.0, 0.0), 1e-9)
        assertEquals(180.0, SkyProjection.separationDeg(0.0, 0.0, 180.0, 0.0), 1e-9)
        // Straight up against the horizon is a quarter turn whichever way you face.
        assertEquals(90.0, SkyProjection.separationDeg(123.0, 90.0, 45.0, 0.0), 1e-9)
        // And it survives the wrap.
        assertEquals(2.0, SkyProjection.separationDeg(359.0, 0.0, 1.0, 0.0), 1e-9)
    }

    @Test
    fun `separation keeps its precision at the small angles hit-testing uses`() {
        // ⚠️ This is why the half-angle form is used instead of acos of the dot product. At a
        // hundredth of a degree the dot product is 1 minus about 1.5e-8, which in double precision
        // has already lost half its significant figures.
        val tiny = SkyProjection.separationDeg(100.0, 40.0, 100.0, 40.01)
        assertEquals(0.01, tiny, 1e-9)
    }

    @Test
    fun `the view cannot be panned past the zenith`() {
        var v = SkyProjection.View(0.0, 80.0, 60.0)
        repeat(5) { v = SkyProjection.pan(v, 0.0, 20.0) }
        assertEquals(SkyProjection.MAX_ALTITUDE_DEG, v.altitudeDeg, 1e-12)
        // And still projects, which is the whole point of clamping rather than letting it through.
        assertTrue(SkyProjection.project(0.0, 45.0, v).visible)
    }

    @Test
    fun `panning sideways wraps rather than running off the end`() {
        var v = SkyProjection.View(350.0, 0.0, 60.0)
        v = SkyProjection.pan(v, 20.0, 0.0)
        assertTrue("azimuth must stay in 0..360, was ${v.azimuthDeg}", v.azimuthDeg in 0.0..360.0)
        assertTrue(SkyProjection.sameAzimuth(v.azimuthDeg, 10.0, 1e-9))
    }

    @Test
    fun `a sideways drag near the zenith moves less sky than one at the horizon`() {
        // ⚠️ Without the cosine the top of the map whips round under the finger. Expressed as the
        // rule rather than as a number: the same drag must turn you FURTHER in azimuth up high,
        // because each degree of azimuth is worth less sky there.
        val low = SkyProjection.pan(SkyProjection.View(0.0, 0.0, 60.0), 10.0, 0.0)
        val high = SkyProjection.pan(SkyProjection.View(0.0, 80.0, 60.0), 10.0, 0.0)
        assertEquals(10.0, low.azimuthDeg, 1e-9)
        assertTrue("high up should turn further, was ${high.azimuthDeg}", high.azimuthDeg > 40.0)
    }

    @Test
    fun `zoom stays inside its limits and reverses cleanly`() {
        val v = SkyProjection.View(0.0, 0.0, 60.0)
        assertEquals(30.0, SkyProjection.zoom(v, 2.0).fovDeg, 1e-9)
        assertEquals(120.0, SkyProjection.zoom(v, 0.5).fovDeg, 1e-9)
        var wide = v
        repeat(20) { wide = SkyProjection.zoom(wide, 0.5) }
        assertEquals(SkyProjection.MAX_FOV_DEG, wide.fovDeg, 1e-12)
        var tight = v
        repeat(20) { tight = SkyProjection.zoom(tight, 2.0) }
        assertEquals(SkyProjection.MIN_FOV_DEG, tight.fovDeg, 1e-12)
    }

    @Test
    fun `the magnitude limit deepens as the field narrows`() {
        val wide = SkyProjection.magnitudeLimit(SkyProjection.MAX_FOV_DEG)
        val tight = SkyProjection.magnitudeLimit(SkyProjection.MIN_FOV_DEG)
        assertEquals("the widest field is the shallowest", SkyProjection.WIDEST_LIMIT, wide, 1e-9)
        assertEquals("tight should reach the catalogue's floor", 6.5, tight, 1e-9)
        assertTrue(SkyProjection.magnitudeLimit(60.0) in wide..tight)
        // Monotonic, so zooming never makes a star that was drawn disappear.
        var previous = wide
        var fov = SkyProjection.MAX_FOV_DEG
        while (fov > SkyProjection.MIN_FOV_DEG) {
            fov -= 5.0
            val here = SkyProjection.magnitudeLimit(fov)
            assertTrue("limit went backwards at $fov", here >= previous - 1e-12)
            previous = here
        }
    }

    /**
     * The property the whole renderer is sized against: **however deep the catalogue goes, the
     * number of stars actually drawn stays in the same band at every zoom.**
     *
     * The model is two measured facts and one exact one. Star counts rise by a factor of 2.63 per
     * magnitude — Gaia DR3 holds 9,100 brighter than 6.5 and 1,247,240 brighter than 11.0, which is
     * 137× over 4.5 magnitudes, and 137^(1/4.5) = 2.63. The solid angle of a field of full angle
     * `fov` is exactly 2π(1 − cos(fov/2)). Their product is proportional to what gets drawn.
     *
     * ⚠️ Only the **unclamped** range is checked, and that is not a convenience. Past the point where
     * the limit hits the catalogue's floor the count genuinely falls, because there are no more rows
     * — which is correct behaviour and would fail an assertion that demanded a flat count.
     */
    @Test
    fun `the drawn count stays in the same band at every zoom`() {
        val deepest = 14.0
        // 150 / 10^((14 - 6) / 4.2) = 1.87°, so everything above two degrees is unclamped.
        val fields = listOf(150.0, 100.0, 60.0, 30.0, 10.0, 3.0, 2.0)
        val counts = fields.map { fov ->
            val m = SkyProjection.magnitudeLimit(fov, deepest)
            assertTrue("$fov° should still be unclamped, was $m", m < deepest - 1e-9)
            val solidAngle = 2.0 * Math.PI * (1.0 - Math.cos(Math.toRadians(fov / 2.0)))
            solidAngle * Math.pow(2.63, m)
        }
        val spread = counts.max() / counts.min()
        assertTrue("the drawn count varies by ${spread}x across the zoom range", spread < 3.0)
    }

    /**
     * ⚠️ The floor exists so the Sun can be looked at rather than merely located. At the old 4° it
     * spanned an eighth of the half-field; here its radius alone is more than the whole half-field,
     * so the disc overflows the screen and the limb is what you are looking at.
     */
    @Test
    fun `the narrowest field is narrower than the Sun`() {
        val halfField = SkyProjection.degreesPerUnit(SkyProjection.View(0.0, 0.0, SkyProjection.MIN_FOV_DEG))
        val solarRadiusDeg = 0.2665
        assertTrue("half-field $halfField° must be under the Sun's radius", halfField < solarRadiusDeg)
    }

    @Test
    fun `degrees per unit halves when the field halves`() {
        assertEquals(30.0, SkyProjection.degreesPerUnit(SkyProjection.View(0.0, 0.0, 60.0)), 1e-12)
        assertEquals(15.0, SkyProjection.degreesPerUnit(SkyProjection.View(0.0, 0.0, 30.0)), 1e-12)
    }

    @Test
    fun `shapes are preserved locally, which is the reason for choosing this projection`() {
        // A small circle on the sky must come out a small circle on screen, at the CORNER of a wide
        // field as well as at the centre. Measured as the ratio of the widest to the narrowest
        // radius of eight points a degree from a centre; a conformal projection keeps it at 1.
        val view = SkyProjection.View(0.0, 0.0, 120.0)
        listOf(0.0 to 0.0, 50.0 to 40.0, -50.0 to -40.0).forEach { (az, alt) ->
            val centre = SkyProjection.project(az, alt, view)
            val radii = (0 until 8).map { i ->
                val theta = i * 45.0 * Math.PI / 180.0
                val p = SkyProjection.project(
                    az + Math.cos(theta) / Math.cos(alt * Math.PI / 180.0),
                    alt + Math.sin(theta),
                    view,
                )
                Math.hypot(p.x - centre.x, p.y - centre.y)
            }
            val ratio = radii.max() / radii.min()
            assertTrue("shape distorted by ${ratio}x at ($az, $alt)", abs(ratio - 1.0) < 0.02)
        }
    }

    // ---- the circle -----------------------------------------------------------------------------

    /**
     * ⚠️ **The regression test for the fault that prompted this work.** The map decided what to draw
     * with [SkyProjection.Screen.inField], which is a radius against the *declared field*, and the
     * caller scaled that radius by half the *narrow* screen dimension — so the sky came out as a
     * disc inscribed in a black rectangle. On a portrait phone that is a circle the width of the
     * screen with dead bands above and below, which is exactly what was reported.
     *
     * The two predicates have to disagree, in both directions, or one of them is pointless:
     * a star well above the view centre is off the declared circle and squarely on the screen, and
     * a star the same angular distance to the *side* is off both. Anything that clips radially
     * cannot tell those two apart.
     */
    @Test
    fun `a portrait screen draws sky the declared field does not cover`() {
        val portrait = SkyProjection.viewportOf(1080.0, 2400.0)
        assertEquals(1.0, portrait.halfWidth, 1e-12)
        assertEquals(2400.0 / 1080.0, portrait.halfHeight, 1e-12)

        // 40° above the centre of a 60° field: 2·tan(20°) / 2·tan(15°) = 0.7279 / 0.5359 = 1.358.
        val level = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 0.0, fovDeg = 60.0)
        val above = SkyProjection.project(180.0, 40.0, level)
        assertEquals("directly above, so no sideways offset", 0.0, above.x, 1e-9)
        assertEquals(1.358, above.radius, 0.002)
        assertFalse("outside the declared field, which is the whole point", above.inField)
        assertTrue("but plainly on a tall screen", above.onScreen(portrait))

        // The same 40° measured sideways is off a portrait screen, so this is not a predicate that
        // simply says yes.
        val beside = SkyProjection.project(220.0, 0.0, level)
        assertEquals(1.358, beside.radius, 0.002)
        assertFalse(beside.inField)
        assertFalse("off the narrow axis", beside.onScreen(portrait))

        // Turn the handset and the answers swap, because the field is normalised to the short side.
        val landscape = SkyProjection.viewportOf(2400.0, 1080.0)
        assertFalse(above.onScreen(landscape))
        assertTrue(beside.onScreen(landscape))
    }

    @Test
    fun `the margin is what stops things popping in and out at the edge`() {
        val square = SkyProjection.viewportOf(1000.0, 1000.0)
        val level = SkyProjection.View(azimuthDeg = 0.0, altitudeDeg = 0.0, fovDeg = 60.0)
        // Just past the right-hand edge: its centre is off, half of its disc is not.
        val edge = SkyProjection.project(31.0, 0.0, level)
        assertTrue("fixture should sit just outside, was ${edge.x}", edge.x > 1.0 && edge.x < 1.1)
        assertFalse(edge.onScreen(square))
        assertTrue(edge.onScreen(square, marginUnits = 0.15))
    }

    @Test
    fun `a viewport that has not been measured yet answers a unit square`() {
        // Composition genuinely hands a Canvas a zero size on its first pass, and dividing by it
        // would put NaN into every comparison — which reads as "nothing is on screen", i.e. a blank
        // map, rather than as a crash.
        listOf(
            SkyProjection.viewportOf(0.0, 0.0),
            SkyProjection.viewportOf(100.0, 0.0),
            SkyProjection.viewportOf(Double.NaN, 100.0),
        ).forEach {
            assertEquals(1.0, it.halfWidth, 1e-12)
            assertEquals(1.0, it.halfHeight, 1e-12)
        }
    }

    // ---- roll -----------------------------------------------------------------------------------

    @Test
    fun `roll turns the picture clockwise and leaves the middle alone`() {
        val flat = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 20.0, fovDeg = 60.0)
        val tipped = flat.copy(rollDeg = 90.0)

        // The look direction is the axis it turns about, so it cannot move.
        val centre = SkyProjection.project(180.0, 20.0, tipped)
        assertEquals(0.0, centre.x, 1e-12)
        assertEquals(0.0, centre.y, 1e-12)

        // Screen y points down, so a quarter turn takes twelve o'clock to three o'clock.
        val up = SkyProjection.project(180.0, 40.0, flat)
        val rolled = SkyProjection.project(180.0, 40.0, tipped)
        assertTrue("fixture must start above the centre", up.y < -0.1 && abs(up.x) < 1e-9)
        assertEquals(-up.y, rolled.x, 1e-9)
        assertEquals(0.0, rolled.y, 1e-9)
        assertEquals("a rotation cannot change how far out it is", up.radius, rolled.radius, 1e-12)
    }

    @Test
    fun `a tap still lands on what it was pointed at when the phone is tipped`() {
        // ⚠️ The error a missed inverse rotation produces is exactly zero at the centre of the
        // screen, so a grid is the only fixture that would catch it.
        listOf(0.0, 17.0, 90.0, -125.0, 359.0).forEach { roll ->
            val view = SkyProjection.View(azimuthDeg = 95.0, altitudeDeg = 15.0, fovDeg = 70.0, rollDeg = roll)
            for (dAz in -25..25 step 10) {
                for (dAlt in -25..25 step 10) {
                    val az = 95.0 + dAz
                    val alt = 15.0 + dAlt
                    val p = SkyProjection.project(az, alt, view)
                    val (az2, alt2) = SkyProjection.unproject(p.x, p.y, view)
                    val sep = SkyProjection.separationDeg(az, alt, az2, alt2)
                    assertTrue("round trip off by $sep° at roll $roll, ($az, $alt)", sep < 1e-9)
                }
            }
        }
    }
}
