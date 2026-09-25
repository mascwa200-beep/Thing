package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.SkyProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The ground shape classifies the screen exactly as the sky's own altitude does.
 *
 * The decisive test is the last one: hundreds of random views, dozens of random directions each,
 * and the shape must put every direction on the side its true altitude says — inside-circle,
 * outside-circle and half-plane alike, without knowing which it got.
 */
class GroundShapeTest {

    private fun basisAndForward(az: Double, alt: Double, fov: Double): Pair<SkyProjection.Basis, DoubleArray> =
        SkyProjection.basisOf(SkyProjection.View(az, alt, fov)) to SkyProjection.unitVector(az, alt)

    private fun classify(shape: GroundShape.Shape, az: Double, alt: Double, basis: SkyProjection.Basis): Boolean {
        val v = SkyProjection.unitVector(az, alt)
        val p = SkyProjection.projectUnit(v[0], v[1], v[2], basis)
        assertTrue("probe must project", p.visible)
        return GroundShape.isGround(shape, p.x, p.y)
    }

    @Test
    fun `looking up the ground is outside the circle, looking down it is inside`() {
        val (up, fUp) = basisAndForward(0.0, 35.0, 80.0)
        val above = GroundShape.of(up, fUp[0], fUp[1], fUp[2])
        assertTrue(above is GroundShape.Shape.Disc)
        assertFalse((above as GroundShape.Shape.Disc).groundInside)
        assertTrue(classify(above, 0.0, -10.0, up))
        assertFalse(classify(above, 0.0, 60.0, up))

        val (down, fDown) = basisAndForward(0.0, -60.0, 60.0)
        val below = GroundShape.of(down, fDown[0], fDown[1], fDown[2])
        assertTrue(below is GroundShape.Shape.Disc)
        assertTrue((below as GroundShape.Shape.Disc).groundInside)
        assertTrue(classify(below, 0.0, -80.0, down))
        assertFalse(classify(below, 0.0, 20.0, down))
    }

    @Test
    fun `a level view makes the horizon a line, with the ground down the screen`() {
        val (basis, f) = basisAndForward(90.0, 0.0, 60.0)
        val shape = GroundShape.of(basis, f[0], f[1], f[2])
        assertTrue("got $shape", shape is GroundShape.Shape.HalfPlane)
        val hp = shape as GroundShape.Shape.HalfPlane
        // Screen y grows DOWNWARD, so the ground side of a level horizon is +y.
        assertEquals(0.0, hp.dx, 1e-9)
        assertEquals(1.0, hp.dy, 1e-9)
        assertTrue(classify(shape, 90.0, -5.0, basis))
        assertFalse(classify(shape, 90.0, 5.0, basis))
    }

    @Test
    fun `aimed almost straight up the nadir cannot project and the zenith decides`() {
        val (basis, f) = basisAndForward(180.0, 89.5, 40.0)
        val shape = GroundShape.of(basis, f[0], f[1], f[2])
        assertTrue(shape is GroundShape.Shape.Disc)
        assertFalse((shape as GroundShape.Shape.Disc).groundInside)
        // The zenith is inside the circle, and a direction ten degrees under the horizon is not.
        assertFalse(classify(shape, 180.0, 88.0, basis))
        assertTrue(classify(shape, 180.0, -10.0, basis))
    }

    @Test
    fun `every random direction lands on the side its true altitude says`() {
        val rng = Random(20260923)
        var checked = 0
        repeat(300) {
            val az = rng.nextDouble(0.0, 360.0)
            val alt = rng.nextDouble(-85.0, 85.0)
            val fov = rng.nextDouble(1.0, 150.0)
            val (basis, f) = basisAndForward(az, alt, fov)
            val shape = GroundShape.of(basis, f[0], f[1], f[2])
            assertTrue("view $az/$alt/$fov produced no shape", shape !is GroundShape.Shape.None)
            repeat(20) {
                val pa = rng.nextDouble(0.0, 360.0)
                val pAlt = rng.nextDouble(-89.0, 89.0)
                // The boundary itself is the one place a pixel's worth of rounding can go either way.
                if (kotlin.math.abs(pAlt) < 0.5) return@repeat
                val v = SkyProjection.unitVector(pa, pAlt)
                val p = SkyProjection.projectUnit(v[0], v[1], v[2], basis)
                if (!p.visible) return@repeat
                assertEquals(
                    "view $az/$alt/$fov, direction $pa/$pAlt, shape ${shape::class.simpleName}",
                    pAlt < 0.0, GroundShape.isGround(shape, p.x, p.y),
                )
                checked++
            }
        }
        assertTrue("checked only $checked directions", checked > 4000)
    }
}
