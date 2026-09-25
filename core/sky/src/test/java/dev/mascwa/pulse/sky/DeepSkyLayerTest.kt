package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.DeepSky
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deep-sky cull takes the air into account exactly as the star cull does: what the air takes
 * is added to the catalogue magnitude before the cut, and an object with no measured brightness
 * is judged by its size, which the air has no say in.
 */
class DeepSkyLayerTest {

    private fun entry(magnitude: Double?, majorArcmin: Double? = null) = DeepSky.Entry(
        id = "probe", kind = DeepSky.Kind.GALAXY,
        rightAscensionDeg = 10.0, declinationDeg = 20.0,
        magnitude = magnitude, band = if (magnitude == null) null else 'V',
        majorAxisArcmin = majorArcmin, minorAxisArcmin = null, positionAngleDeg = null,
        label = "probe",
    )

    @Test
    fun `the air is added to the catalogue magnitude before the cut, and nothing is added without it`() {
        val layer = DeepSkyLayer(listOf(entry(8.0)))
        // Half a magnitude inside the cut.
        assertTrue(layer.visible(0, 8.5, 60.0))
        assertTrue(layer.visible(0, 8.5, 60.0, 0.0))
        assertTrue(layer.visible(0, 8.5, 60.0, 0.49))
        assertFalse("dimmed past the cut", layer.visible(0, 8.5, 60.0, 0.51))
        // The default is the vacuum, bit for bit.
        assertEquals(layer.visible(0, 8.5, 60.0), layer.visible(0, 8.5, 60.0, 0.0))
    }

    @Test
    fun `an object with no measured brightness is judged by its size, whatever the air takes`() {
        // The Hyades: no magnitude, 329 arcminutes across, drawn for its size at a wide field.
        val layer = DeepSkyLayer(listOf(entry(null, majorArcmin = 329.0)))
        assertTrue(layer.visible(0, 8.5, 60.0))
        assertTrue("five magnitudes of air change nothing about a size", layer.visible(0, 8.5, 60.0, 5.0))
    }
}
