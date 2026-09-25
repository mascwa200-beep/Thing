package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.SkyBrightness
import dev.mascwa.pulse.core.telemetry.SkyProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame hands the renderer the air's extinction, only with the atmosphere on, and only above
 * the drawn horizon — the same line it splits the above and below batches on.
 *
 * ⚠️ The "atmosphere off" case is the load-bearing one: it is what makes a chart with the switch
 * off byte-for-byte the chart before extinction existed, because `m + 0.0` is exact.
 */
class SkyFrameExtinctionTest {

    private val lat = 42.7875
    private val lon = -86.1089
    private val at = 1_790_128_800_000L
    private val view = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 20.0, fovDeg = 80.0)

    private val sines = listOf(
        1.0, 0.99, 0.7, 0.5, 0.17, 0.05, 0.01, 0.0, -0.005,
        SkyBrightness.SIN_DRAWN_HORIZON, -0.02, -0.5, -1.0,
    )

    @Test
    fun `with the atmosphere off the air takes nothing, at every altitude`() {
        val frame = SkyFrame.of(view, lat, lon, at, refracting = false)
        for (s in sines) {
            assertEquals("extinction with the air off, at sine $s", 0.0, frame.extinctionMag(s), 0.0)
            assertEquals("transmission with the air off, at sine $s", 1.0, frame.transmission(s), 0.0)
        }
    }

    @Test
    fun `with the atmosphere on the frame answers the shared table, and nothing below the drawn horizon`() {
        val frame = SkyFrame.of(view, lat, lon, at, refracting = true)
        for (s in sines) {
            if (s >= SkyBrightness.SIN_DRAWN_HORIZON) {
                assertEquals(SkyBrightness.fastExtinctionMag(s), frame.extinctionMag(s), 0.0)
                assertEquals(SkyBrightness.fastTransmission(s), frame.transmission(s), 0.0)
            } else {
                assertEquals("below the drawn horizon at sine $s", 0.0, frame.extinctionMag(s), 0.0)
                assertEquals("below the drawn horizon at sine $s", 1.0, frame.transmission(s), 0.0)
            }
        }
        // The zenith is exactly zero, so the brightest thing overhead is drawn exactly as it was.
        assertEquals(0.0, frame.extinctionMag(1.0), 0.0)
        // And something a few degrees up genuinely loses light (sine 0.05 is 2.87° up; measured
        // 1.785 magnitudes and 0.193 transmitted at k = 0.13).
        assertTrue(frame.extinctionMag(0.05) > 1.5)
        assertTrue(frame.transmission(0.05) < 0.25)
    }

    @Test
    fun `the extinction step sits exactly on the batch boundary`() {
        val frame = SkyFrame.of(view, lat, lon, at, refracting = true)
        val edge = SkyBrightness.SIN_DRAWN_HORIZON
        // Just above the drawn horizon: in the "above" batch, and the air takes its 5.07 magnitudes.
        assertTrue(frame.aboveHorizon(edge + 1e-9))
        assertTrue(frame.extinctionMag(edge + 1e-9) > 5.0)
        // Just below: in the "below" batch, dimmed by alpha instead, and the air takes nothing.
        assertFalse(frame.aboveHorizon(edge - 1e-9))
        assertEquals(0.0, frame.extinctionMag(edge - 1e-9), 0.0)
        // The general rule, over the whole sweep: nothing in the below batch is ever extincted.
        for (s in sines) {
            if (!frame.aboveHorizon(s)) assertEquals("sine $s", 0.0, frame.extinctionMag(s), 0.0)
        }
    }
}
