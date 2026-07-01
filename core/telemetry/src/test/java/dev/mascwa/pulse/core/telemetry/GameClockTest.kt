package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClockTest {

    private val start = 1_000_000_000_000L
    private val d = GameClock.DAY_MS

    @Test fun dayNumberStartsAtOne() {
        assertEquals(1, GameClock.dayNumber(start, start))
        assertEquals(1, GameClock.dayNumber(start, start + d - 1))
    }

    @Test fun dayNumberIncrementsEachDay() {
        assertEquals(2, GameClock.dayNumber(start, start + d))
        assertEquals(4, GameClock.dayNumber(start, start + 3 * d + 100))
    }

    @Test fun dayNumberIsDefensive() {
        assertEquals(1, GameClock.dayNumber(0L, start))          // no start recorded
        assertEquals(1, GameClock.dayNumber(start, start - 5000)) // clock went backwards
    }

    @Test fun daysSurvivedCounts() {
        assertEquals(0, GameClock.daysSurvived(start, start))
        assertEquals(2, GameClock.daysSurvived(start, start + 2 * d))
    }

    @Test fun phaseMapsHours() {
        assertEquals(DayPhase.DAWN, GameClock.phase(6))
        assertEquals(DayPhase.DAY, GameClock.phase(12))
        assertEquals(DayPhase.DUSK, GameClock.phase(19))
        assertEquals(DayPhase.NIGHT, GameClock.phase(23))
        assertEquals(DayPhase.NIGHT, GameClock.phase(2))
    }

    @Test fun phaseWrapsDefensively() {
        assertEquals(GameClock.phase(0), GameClock.phase(24))
        assertEquals(GameClock.phase(23), GameClock.phase(-1))
    }

    @Test fun bannerReads() {
        assertEquals("DAY 1 · DUSK", GameClock.banner(start, start, 19))
        assertEquals("DAY 3 · DAYLIGHT", GameClock.banner(start, start + 2 * d, 12))
    }

    @Test fun isNewDayDetectsRollover() {
        assertFalse(GameClock.isNewDay(0L, start + d, start))     // never checked before
        assertTrue(GameClock.isNewDay(start, start + d, start))    // day 1 → day 2
        assertFalse(GameClock.isNewDay(start + 100, start + 200, start)) // same day
    }
}
