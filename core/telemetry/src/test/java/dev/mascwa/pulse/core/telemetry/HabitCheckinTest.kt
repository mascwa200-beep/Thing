package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The check-in scheduler + the lie-catcher's decision logic. */
class HabitCheckinTest {

    private val HOUR = 3_600_000L
    private val DAY = 24 * HOUR
    private val shower = Habit(RealActivity.SHOWER, "Shower", "Showered?", DAY)

    @Test fun overdueHabitIsDueAndFreshOneIsNot() {
        val now = 10 * DAY
        val states = mapOf(
            RealActivity.SHOWER.name to HabitState(lastConfirmedMs = now - 2 * DAY), // overdue (cadence 1d)
            RealActivity.TOOTHBRUSH.name to HabitState(lastConfirmedMs = now - HOUR),  // fresh
        )
        val due = HabitCheckin.due(HabitCheckin.DEFAULTS, states, now)
        assertTrue(due.any { it.activity == RealActivity.SHOWER })
        assertFalse(due.any { it.activity == RealActivity.TOOTHBRUSH })
    }

    @Test fun aRecentlyAskedHabitIsNotReAsked() {
        val now = 10 * DAY
        val states = mapOf(
            RealActivity.SHOWER.name to HabitState(lastConfirmedMs = 0, lastAskedMs = now - 30 * 60_000L), // asked 30m ago
        )
        assertFalse(HabitCheckin.due(listOf(shower), states, now).any { it.activity == RealActivity.SHOWER })
    }

    @Test fun truthfulClaimWithEvidenceIsConfirmed() {
        val ev = listOf(ActivityEvidence(RealActivity.SHOWER, 0.8f, atMs = 500))
        assertEquals(CheckinOutcome.CONFIRMED, HabitCheckin.resolve(shower, claimedDone = true, ev, sinceMs = 0))
    }

    @Test fun claimWithoutAnyEvidenceIsCaughtAsALie() {
        assertEquals(CheckinOutcome.CAUGHT_LIE, HabitCheckin.resolve(shower, claimedDone = true, emptyList(), sinceMs = 0))
    }

    @Test fun weakEvidenceIsProvisionalNotAConfirmedTruthNorALie() {
        val ev = listOf(ActivityEvidence(RealActivity.SHOWER, 0.4f, atMs = 500))
        assertEquals(CheckinOutcome.UNVERIFIED, HabitCheckin.resolve(shower, claimedDone = true, ev, sinceMs = 0))
    }

    @Test fun sayingNoIsHonest() {
        val ev = listOf(ActivityEvidence(RealActivity.SHOWER, 0.9f, atMs = 500))
        assertEquals(CheckinOutcome.HONEST_NO, HabitCheckin.resolve(shower, claimedDone = false, ev, sinceMs = 0))
    }

    @Test fun onlyTruthfulOrProvisionalOutcomesTopUpAndConfirm() {
        assertTrue(HabitCheckin.topsUpNeed(CheckinOutcome.CONFIRMED))
        assertTrue(HabitCheckin.topsUpNeed(CheckinOutcome.UNVERIFIED))
        assertFalse(HabitCheckin.topsUpNeed(CheckinOutcome.CAUGHT_LIE))
        assertFalse(HabitCheckin.topsUpNeed(CheckinOutcome.HONEST_NO))
        assertTrue(HabitCheckin.confirms(CheckinOutcome.CONFIRMED))
        assertFalse(HabitCheckin.confirms(CheckinOutcome.CAUGHT_LIE))
    }
}
