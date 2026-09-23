package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Refraction
import dev.mascwa.pulse.core.telemetry.SkyPointing
import dev.mascwa.pulse.core.telemetry.SkyProjection
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame bends what it projects, exactly as the atmosphere does, and only when asked.
 *
 * ⚠️ These run on the JVM against the real `Ephemeris` and `Refraction`, so the star that is
 * "truly on the horizon" here is placed by the same conversions the map uses, not by a fixture that
 * assumes where the horizon is in the catalogue's frame.
 */
class SkyFrameRefractionTest {

    private val lat = 42.7875
    private val lon = -86.1089
    private val at = 1_790_128_800_000L
    private val view = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 20.0, fovDeg = 80.0)

    /** A catalogue-frame unit vector for a TRUE horizon position. */
    private fun catalogueUnit(altDeg: Double, azDeg: Double): DoubleArray {
        val eq = SkyFrame.catalogueOf(altDeg, azDeg, lat, lon, at)
        return SkyProjection.equatorialVector(eq.rightAscensionDeg, eq.declinationDeg)
    }

    /** The horizon altitude a frame draws a screen point at, read back through its own basis. */
    private fun drawnAltitude(frame: SkyFrame, p: SkyProjection.Screen): Double {
        val dir = DoubleArray(3)
        assertTrue(SkyProjection.unprojectUnit(p.x, p.y, frame.basis, dir))
        val dec = Math.toDegrees(asin(dir[2].coerceIn(-1.0, 1.0)))
        val ra = Math.toDegrees(atan2(dir[1], dir[0]))
        return SkyFrame.horizonOf(ra, dec, lat, lon, at).altitudeDeg
    }

    @Test
    fun `with the atmosphere off, project is projectUnit on the basis, bit for bit`() {
        val frame = SkyFrame.of(view, lat, lon, at, refracting = false)
        for (alt in listOf(-10.0, -2.0, 0.0, 0.3, 5.0, 30.0, 60.0, 89.0)) {
            for (az in listOf(140.0, 180.0, 220.0)) {
                val v = catalogueUnit(alt, az)
                val direct = SkyProjection.projectUnit(v[0], v[1], v[2], frame.basis)
                val via = frame.project(v[0], v[1], v[2])
                assertEquals(direct.x, via.x, 0.0)
                assertEquals(direct.y, via.y, 0.0)
                assertEquals(direct.visible, via.visible)
            }
        }
    }

    @Test
    fun `with the atmosphere on, a star truly on the horizon is drawn at its apparent altitude`() {
        val on = SkyFrame.of(view, lat, lon, at, refracting = true)
        val off = SkyFrame.of(view, lat, lon, at, refracting = false)
        for (az in listOf(150.0, 180.0, 210.0)) {
            for (trueAlt in listOf(0.0, 2.0, 10.0, 45.0)) {
                val v = catalogueUnit(trueAlt, az)
                val bent = on.project(v[0], v[1], v[2])
                assertTrue(bent.visible)
                val expected = Refraction.apparentOf(trueAlt)
                // Read back in angle: the drawn direction's true altitude IS the apparent altitude.
                // The table is within 0.065″ of the exact form; a tenth of an arcsecond is the bar.
                assertEquals("true $trueAlt at az $az", expected, drawnAltitude(on, bent), 0.1 / 3600.0)
                // And on screen: exactly where an unrefracted frame would draw a star that truly
                // sat at the apparent altitude. At an 80° field one unit is 40°, so a tenth of an
                // arcsecond is under a millionth of a unit.
                val w = catalogueUnit(expected, az)
                val straight = off.project(w[0], w[1], w[2])
                assertEquals(straight.x, bent.x, 2e-6)
                assertEquals(straight.y, bent.y, 2e-6)
            }
        }
    }

    @Test
    fun `nothing moves at the zenith or below the taper`() {
        val on = SkyFrame.of(view, lat, lon, at, refracting = true)
        for ((alt, az) in listOf(89.95 to 180.0, -5.0 to 180.0, -10.0 to 150.0)) {
            val v = catalogueUnit(alt, az)
            val direct = SkyProjection.projectUnit(v[0], v[1], v[2], on.basis)
            val via = on.project(v[0], v[1], v[2])
            assertEquals(direct.x, via.x, 0.0)
            assertEquals(direct.y, via.y, 0.0)
        }
    }

    @Test
    fun `the pointing frame bends the same sky the same way`() {
        // A handset aimed due south, twenty degrees up, held upright: forward and screen-up in
        // east/north/up, checked against SkyPointing's own reading of them rather than assumed.
        val alt = Math.toRadians(20.0)
        val forward = doubleArrayOf(0.0, -cos(alt), sin(alt))
        val up = doubleArrayOf(0.0, sin(alt), cos(alt))
        assertEquals(180.0, SkyPointing.azimuthOf(forward), 1e-9)
        assertEquals(20.0, SkyPointing.altitudeOf(forward), 1e-9)
        val on = SkyFrame.ofPointing(forward, up, 80.0, lat, lon, at, refracting = true)
        val v = catalogueUnit(0.0, 180.0)
        val bent = on.project(v[0], v[1], v[2])
        assertTrue(bent.visible)
        assertEquals(Refraction.apparentOf(0.0), drawnAltitude(on, bent), 0.1 / 3600.0)
    }

    @Test
    fun `above-the-horizon follows the drawn line, not the geometric one`() {
        val on = SkyFrame.of(view, lat, lon, at, refracting = true)
        val off = SkyFrame.of(view, lat, lon, at, refracting = false)
        val justUnder = sin(Math.toRadians(-0.3))
        // Truly 0.3° under the horizon, drawn 0.2° above it: up, to the eye, and dimming it would
        // put a dimmed dot above the line it is supposed to be under.
        assertTrue(on.aboveHorizon(justUnder))
        assertFalse(off.aboveHorizon(justUnder))
        val wellUnder = sin(Math.toRadians(-1.0))
        assertFalse(on.aboveHorizon(wellUnder))
        assertFalse(off.aboveHorizon(wellUnder))
        val justOver = sin(Math.toRadians(0.1))
        assertTrue(on.aboveHorizon(justOver))
        assertTrue(off.aboveHorizon(justOver))
        // The geometric frame's line is exactly the geometric horizon.
        assertTrue(off.aboveHorizon(0.0))
    }

    @Test
    fun `a refracting frame asks a cull to look further out by exactly the largest bend`() {
        val on = SkyFrame.of(view, lat, lon, at, refracting = true)
        val off = SkyFrame.of(view, lat, lon, at, refracting = false)
        assertEquals(Refraction.MAX_BEND_DEG, on.cullMarginDeg, 0.0)
        assertEquals(0.0, off.cullMarginDeg, 0.0)
        // And it really is the lift a run at the top of the taper receives.
        val v = catalogueUnit(Refraction.TAPER_TOP_DEG, 180.0)
        val bent = on.project(v[0], v[1], v[2])
        assertEquals(
            Refraction.TAPER_TOP_DEG + on.cullMarginDeg, drawnAltitude(on, bent), 0.1 / 3600.0,
        )
    }
}
