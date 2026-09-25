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

    /**
     * The name follows the marker: what the air takes comes off the headroom the catalogue
     * magnitude earned, exactly as the star label pass re-checks `m0 + extinction`. The figures
     * are Andromeda at the drawn horizon under a 60° field — the case a review found drawing
     * "Andromeda Galaxy" at full strength beside a marker at half a percent alpha.
     */
    @Test
    fun `a name is withheld when the air has taken the headroom the catalogue magnitude earned`() {
        val layer = DeepSkyLayer(listOf(entry(3.44)))
        val limit = 10.98
        // At the zenith, seven and a half magnitudes of headroom against a bar of four: named.
        assertTrue(layer.labelled(0, limit))
        assertTrue(layer.labelled(0, limit, 0.0))
        // At the drawn horizon the air takes 5.07, leaving 2.47: the marker is drawn (3.44 + 5.07
        // is inside the cut) and the name is not.
        assertTrue("the marker must still be drawn for the case to be the one under test", layer.visible(0, limit, 60.0, 5.07))
        assertFalse(layer.labelled(0, limit, 5.07))
        // And the default is the vacuum, bit for bit.
        assertEquals(layer.labelled(0, limit), layer.labelled(0, limit, 0.0))
        // An object with no measured brightness is named for its size, whatever the air takes.
        val sized = DeepSkyLayer(listOf(entry(null, majorArcmin = 329.0)))
        assertTrue(sized.labelled(0, limit, 5.0))
    }

    @Test
    fun `an object with no measured brightness is judged by its size, whatever the air takes`() {
        // The Hyades: no magnitude, 329 arcminutes across, drawn for its size at a wide field.
        val layer = DeepSkyLayer(listOf(entry(null, majorArcmin = 329.0)))
        assertTrue(layer.visible(0, 8.5, 60.0))
        assertTrue("five magnitudes of air change nothing about a size", layer.visible(0, 8.5, 60.0, 5.0))
    }
}
