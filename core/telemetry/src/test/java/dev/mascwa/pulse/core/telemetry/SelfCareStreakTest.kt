package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfCareStreakTest {

    @Test fun firstConfirmationStartsStreakAtOne() {
        val s = SelfCareStreak.record(Streak(), day = 5)
        assertEquals(1, s.current)
        assertEquals(1, s.longest)
        assertEquals(5, s.lastDay)
    }

    @Test fun sameDayRepeatDoesNotChange() {
        val s1 = SelfCareStreak.record(Streak(), day = 5)
        val s2 = SelfCareStreak.record(s1, day = 5)
        assertEquals(s1, s2)
    }

    @Test fun consecutiveDaysExtend() {
        var s = SelfCareStreak.record(Streak(), 1)
        s = SelfCareStreak.record(s, 2)
        s = SelfCareStreak.record(s, 3)
        assertEquals(3, s.current)
        assertEquals(3, s.longest)
        assertEquals(3, s.lastDay)
    }

    @Test fun gapResetsCurrentButKeepsLongest() {
        var s = SelfCareStreak.record(Streak(), 1)
        s = SelfCareStreak.record(s, 2)
        s = SelfCareStreak.record(s, 3) // current 3, longest 3
        s = SelfCareStreak.record(s, 6) // gap → reset
        assertEquals(1, s.current)
        assertEquals(3, s.longest)
        assertEquals(6, s.lastDay)
    }

    @Test fun outOfOrderDayIgnored() {
        var s = SelfCareStreak.record(Streak(), 5)
        s = SelfCareStreak.record(s, 3) // earlier than lastDay → ignored
        assertEquals(1, s.current)
        assertEquals(5, s.lastDay)
    }

    @Test fun negativeDayIgnored() {
        val prev = Streak(current = 2, longest = 4, lastDay = 7)
        assertEquals(prev, SelfCareStreak.record(prev, -1))
    }

    @Test fun currentAsOfFreshVsStale() {
        val s = Streak(current = 4, longest = 4, lastDay = 10)
        assertEquals(4, SelfCareStreak.currentAsOf(s, 10)) // same day
        assertEquals(4, SelfCareStreak.currentAsOf(s, 11)) // yesterday → still live
        assertEquals(0, SelfCareStreak.currentAsOf(s, 12)) // 2 days stale → broken
    }

    @Test fun currentAsOfNeverConfirmed() {
        assertEquals(0, SelfCareStreak.currentAsOf(Streak(), 3))
    }

    @Test fun wellKeptBonusTiers() {
        val today = 10
        assertEquals(0, SelfCareStreak.wellKeptBonus(emptyList(), today))
        assertEquals(0, SelfCareStreak.wellKeptBonus(listOf(Streak(2, 2, 10)), today))
        assertEquals(1, SelfCareStreak.wellKeptBonus(listOf(Streak(3, 3, 10)), today))
        assertEquals(1, SelfCareStreak.wellKeptBonus(listOf(Streak(6, 6, 10)), today))
        assertEquals(2, SelfCareStreak.wellKeptBonus(listOf(Streak(7, 9, 10)), today))
    }

    @Test fun wellKeptBonusIgnoresStaleStreaks() {
        val today = 20
        // A big streak but last confirmed 5 days ago → stale → contributes 0.
        assertEquals(0, SelfCareStreak.wellKeptBonus(listOf(Streak(9, 9, 15)), today))
    }

    @Test fun wellKeptBonusTakesBestLiveStreak() {
        val today = 10
        val streaks = listOf(
            Streak(2, 2, 10),   // live, small
            Streak(8, 8, 10),   // live, big → tier 2
            Streak(9, 9, 3),    // stale → ignored
        )
        assertEquals(2, SelfCareStreak.wellKeptBonus(streaks, today))
    }

    @Test fun describeLiveLapsedAndEmpty() {
        val today = 10
        assertEquals("", SelfCareStreak.describe(emptyList(), today))
        assertEquals("4-day self-care streak · best 6", SelfCareStreak.describe(listOf(Streak(4, 6, 10)), today))
        assertEquals("streak lapsed · best 6", SelfCareStreak.describe(listOf(Streak(4, 6, 2)), today))
    }

    @Test fun bestAtRiskOnlyFlagsGraceDayStreaks() {
        val today = 10
        // Confirmed yesterday, meaningful → at risk today.
        assertEquals(5, SelfCareStreak.bestAtRisk(listOf(Streak(5, 5, 9)), today))
        // Already confirmed today → safe, not at risk.
        assertEquals(0, SelfCareStreak.bestAtRisk(listOf(Streak(5, 5, 10)), today))
        // Already lapsed (2+ days stale) → not "at risk", it's gone.
        assertEquals(0, SelfCareStreak.bestAtRisk(listOf(Streak(5, 5, 7)), today))
        // Below the min-streak floor → not worth a reminder.
        assertEquals(0, SelfCareStreak.bestAtRisk(listOf(Streak(2, 2, 9)), today))
        // Picks the biggest at-risk streak across habits (a safe one is ignored).
        assertEquals(8, SelfCareStreak.bestAtRisk(listOf(Streak(3, 3, 9), Streak(8, 8, 9), Streak(9, 9, 10)), today))
    }
}
