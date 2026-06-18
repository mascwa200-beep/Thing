package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavGuidanceTest {

    @Test
    fun relativeBearingIsZeroWhenFacingTarget() {
        assertEquals(0.0, NavGuidance.relativeBearing(90.0, 90.0), 1e-9)
        assertEquals(0.0, NavGuidance.relativeBearing(0.0, 360.0), 1e-9)
    }

    @Test
    fun relativeBearingPositiveMeansTurnRight() {
        // Facing north (0°), target at 90° (east) → turn 90° right (+).
        assertEquals(90.0, NavGuidance.relativeBearing(90.0, 0.0), 1e-9)
        // Facing north, target at 270° (west) → shortest turn is 90° left (-).
        assertEquals(-90.0, NavGuidance.relativeBearing(270.0, 0.0), 1e-9)
    }

    @Test
    fun relativeBearingWrapsAcrossNorth() {
        // Facing 350°, target at 10° → 20° right, not -340°.
        assertEquals(20.0, NavGuidance.relativeBearing(10.0, 350.0), 1e-9)
        // Facing 10°, target at 350° → 20° left.
        assertEquals(-20.0, NavGuidance.relativeBearing(350.0, 10.0), 1e-9)
    }

    @Test
    fun relativeBearingHandlesUnnormalisedInputs() {
        // Negative / >360 inputs are wrapped before the diff is taken.
        assertEquals(90.0, NavGuidance.relativeBearing(450.0, 0.0), 1e-9)
        assertEquals(0.0, NavGuidance.relativeBearing(-90.0, 270.0), 1e-9)
    }

    @Test
    fun arrowPointsAheadWhenTargetIsInFront() {
        assertEquals("↑", NavGuidance.relativeArrow(0.0, 0.0))
        assertEquals("↑", NavGuidance.relativeArrow(355.0, 0.0)) // ~ahead, snaps up
    }

    @Test
    fun arrowReflectsRelativeDirection() {
        assertEquals("→", NavGuidance.relativeArrow(90.0, 0.0))  // target to the right
        assertEquals("←", NavGuidance.relativeArrow(270.0, 0.0)) // target to the left
        assertEquals("↓", NavGuidance.relativeArrow(180.0, 0.0)) // target behind
        assertEquals("↗", NavGuidance.relativeArrow(45.0, 0.0))
        assertEquals("↘", NavGuidance.relativeArrow(135.0, 0.0))
    }

    @Test
    fun arrowAccountsForCurrentHeading() {
        // Facing east (90°); a target due north (0°) is now on your left.
        assertEquals("←", NavGuidance.relativeArrow(0.0, 90.0))
    }

    @Test
    fun nullHeadingFallsBackToAbsoluteCompassArrow() {
        // No heading fix → arrow as if facing north (absolute bearing).
        assertEquals("→", NavGuidance.relativeArrow(90.0, null))
        assertEquals("↑", NavGuidance.relativeArrow(2.0, null))
    }

    @Test
    fun turnHintDescribesTheTurn() {
        assertEquals("ahead", NavGuidance.turnHint(3.0, 0.0))
        assertEquals("behind", NavGuidance.turnHint(180.0, 0.0))
        assertEquals("90° right", NavGuidance.turnHint(90.0, 0.0))
        assertEquals("90° left", NavGuidance.turnHint(270.0, 0.0))
    }

    @Test
    fun turnHintIsNullWithoutHeading() {
        assertNull(NavGuidance.turnHint(123.0, null))
    }

    @Test
    fun arrowIndexNeverOutOfBounds() {
        // Sweep all bearing/heading combos to prove the modulo math can't throw.
        var h = 0.0
        while (h < 360.0) {
            var b = 0.0
            while (b < 360.0) {
                val arrow = NavGuidance.relativeArrow(b, h)
                assertTrue(arrow.isNotEmpty())
                b += 7.5
            }
            h += 11.0
        }
    }
}
