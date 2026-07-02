package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the AR camera-projection maths. */
class ArProjectionTest {

    @Test fun relativeBearingIsSignedAndWraps() {
        assertEquals(0.0, ArProjection.relativeBearing(90.0, 90.0), 1e-9)
        assertEquals(30.0, ArProjection.relativeBearing(90.0, 120.0), 1e-9)   // to the right
        assertEquals(-30.0, ArProjection.relativeBearing(90.0, 60.0), 1e-9)   // to the left
        // Wraparound: facing 350°, a thing at 10° is 20° to your right (not -340°).
        assertEquals(20.0, ArProjection.relativeBearing(350.0, 10.0), 1e-9)
        assertEquals(-20.0, ArProjection.relativeBearing(10.0, 350.0), 1e-9)
    }

    @Test fun inViewRespectsFov() {
        // 60° FOV → ±30° visible.
        assertTrue(ArProjection.inView(0.0, 25.0, 60.0))
        assertTrue(ArProjection.inView(0.0, 335.0, 60.0)) // -25°
        assertFalse(ArProjection.inView(0.0, 40.0, 60.0)) // beyond +30°
        assertFalse(ArProjection.inView(0.0, 180.0, 60.0)) // behind you
    }

    @Test fun screenXCentresAheadAndSpansEdges() {
        assertEquals(0.5, ArProjection.screenX(0.0, 0.0, 60.0), 1e-9)     // dead ahead → centre
        assertEquals(1.0, ArProjection.screenX(0.0, 30.0, 60.0), 1e-9)    // right FOV edge
        assertEquals(0.0, ArProjection.screenX(0.0, 330.0, 60.0), 1e-9)   // left FOV edge (-30°)
        // A thing half-way to the right edge lands at 0.75.
        assertEquals(0.75, ArProjection.screenX(0.0, 15.0, 60.0), 1e-9)
    }

    @Test fun sizeShrinksWithDistanceAndClamps() {
        assertEquals(1.0, ArProjection.sizeForDistance(10.0), 1e-9)     // very near → full
        assertEquals(0.35, ArProjection.sizeForDistance(5000.0), 1e-9)  // very far → floor
        val near = ArProjection.sizeForDistance(100.0)
        val far = ArProjection.sizeForDistance(1500.0)
        assertTrue("nearer reads bigger", near > far)
        assertTrue(near in 0.35..1.0 && far in 0.35..1.0)
    }
}
