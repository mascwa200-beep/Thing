package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {

    @Test fun eachStatMapsToAGesture() {
        Special.entries.forEach { assertTrue(Gestures.forStat(it) in GestureType.entries) }
        assertEquals(GestureType.SHAKE, Gestures.forStat(Special.STRENGTH))
        assertEquals(GestureType.FLICK, Gestures.forStat(Special.AGILITY))
        assertEquals(GestureType.HOLD, Gestures.forStat(Special.PERCEPTION))
    }

    @Test fun performanceRollSpansTheDie() {
        assertEquals(1, Gestures.performanceRoll(0f))
        assertEquals(SpecialGame.DIE, Gestures.performanceRoll(1f))
        assertEquals(1, Gestures.performanceRoll(-5f))   // clamped
        assertEquals(SpecialGame.DIE, Gestures.performanceRoll(2f)) // clamped
    }

    @Test fun performanceRollIsMonotonic() {
        var prev = 0
        var p = 0f
        while (p <= 1f) {
            val r = Gestures.performanceRoll(p)
            assertTrue("roll must not decrease as performance rises", r >= prev)
            assertTrue(r in 1..SpecialGame.DIE)
            prev = r
            p += 0.05f
        }
    }
}
