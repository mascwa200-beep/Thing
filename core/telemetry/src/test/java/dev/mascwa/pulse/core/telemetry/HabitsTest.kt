package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected number was worked out from the rule before the assertion was written, and the
 * reasoning is in the comment beside it.
 */
class HabitsTest {

    private val TODAY = 1_700_000_000_000L / Habits.DAY_MS * Habits.DAY_MS
    private fun d(n: Int) = TODAY - n * Habits.DAY_MS

    // ------------------------------------------------------------------------------- the streak

    @Test
    fun nothingLoggedIsNoStreakRatherThanADivisionByZero() {
        val s = Habits.streak(emptySet(), TODAY)
        assertEquals(0, s.current)
        assertEquals(0, s.longest)
        assertNull(s.lastMs)
        assertFalse(s.doneToday)
        assertNull(Habits.summary(s))
    }

    @Test
    fun aRunEndingTodayCountsToday() {
        // today, -1, -2 = three consecutive days ending today.
        val s = Habits.streak(setOf(TODAY, d(1), d(2)), TODAY)
        assertEquals(3, s.current)
        assertEquals(3, s.longest)
        assertTrue(s.doneToday)
        assertEquals("3 days, including today.", Habits.summary(s))
    }

    /**
     * ⚠️ THE LOAD-BEARING RULE. Today is not over. Breaking a streak at four in the afternoon because
     * dinner has not been logged yet would make the measure punish the clock rather than the person.
     */
    @Test
    fun aRunEndingYesterdayIsStillCurrent() {
        val s = Habits.streak(setOf(d(1), d(2), d(3)), TODAY)
        assertEquals(3, s.current)
        assertFalse("today is genuinely not logged", s.doneToday)
        assertEquals("3 days, up to yesterday.", Habits.summary(s))
    }

    /** But a whole day gone by with nothing in it does break it. */
    @Test
    fun aWholeMissedDayBreaksTheRun() {
        // Newest is two days ago: yesterday passed with nothing logged.
        val s = Habits.streak(setOf(d(2), d(3), d(4)), TODAY)
        assertEquals(0, s.current)
        // The run itself still happened, so the record of it stands.
        assertEquals(3, s.longest)
        assertNull(Habits.summary(s))
    }

    /**
     * ⚠️ The longest run is over the WHOLE history, not the trailing one. A person who logged for
     * three weeks, stopped, and has started again is not on a two-day best.
     */
    @Test
    fun theLongestRunIsNotTheCurrentOne() {
        // A 5-day run at days 20..16, a gap, then 2 days ending today.
        val days = (16..20).map { d(it) }.toSet() + setOf(TODAY, d(1))
        val s = Habits.streak(days, TODAY)
        assertEquals(2, s.current)
        assertEquals(5, s.longest)
    }

    @Test
    fun oneDayReadsAsOneDay() {
        assertEquals("Today.", Habits.summary(Habits.streak(setOf(TODAY), TODAY)))
        assertEquals("Yesterday.", Habits.summary(Habits.streak(setOf(d(1)), TODAY)))
    }

    /** Out-of-order input is a set, and a set has no order. The rule must not depend on one. */
    @Test
    fun theInputOrderCannotChangeTheAnswer() {
        val a = Habits.streak(linkedSetOf(d(2), TODAY, d(1)), TODAY)
        val b = Habits.streak(linkedSetOf(TODAY, d(1), d(2)), TODAY)
        assertEquals(a, b)
        assertEquals(3, a.current)
    }

    /**
     * ⚠️ Silent at zero, not "0 days". Four habits each announcing a broken streak is a wall of
     * failure on the screen somebody opened to work out what to have for lunch.
     */
    @Test
    fun aBrokenStreakSaysNothingAtAll() {
        assertNull(Habits.summary(Habits.streak(setOf(d(9)), TODAY)))
    }

    // -------------------------------------------------------------------------------- the steps

    /**
     * ⚠️ THE OTHER LOAD-BEARING RULE. The sensor counts from the last BOOT, not from midnight, so
     * the raw figure is not a daily total. Reading it as one gives a number that only ever grows.
     */
    @Test
    fun theFirstReadingOfADayBecomesItsBaselineAndZeroSteps() {
        val s = Habits.steps(null, raw = 812_345L, todayStartMs = TODAY)
        assertEquals(812_345L, s.baseline)
        assertEquals(0L, s.today)
        assertFalse(s.partial)
        // …and the next reading is the difference, not the raw value.
        val later = Habits.steps(s, raw = 815_000L, todayStartMs = TODAY)
        assertEquals(2_655L, later.today)   // 815000 - 812345
        assertEquals(812_345L, later.baseline)
    }

    @Test
    fun aNewDayRebaselinesToWhereTheCounterNowIs() {
        val yesterday = Habits.steps(null, raw = 800_000L, todayStartMs = d(1))
        val walked = Habits.steps(yesterday, raw = 809_000L, todayStartMs = d(1))
        assertEquals(9_000L, walked.today)

        val today = Habits.steps(walked, raw = 809_000L, todayStartMs = TODAY)
        assertEquals(809_000L, today.baseline)
        assertEquals(0L, today.today)
    }

    /**
     * ⚠️ A reading below the baseline can only mean the counter restarted, which means the device
     * did. Re-baseline to ZERO rather than to the new reading: the steps since the reboot are real
     * and countable, and only the ones before it are lost.
     */
    @Test
    fun aRebootKeepsTheStepsSinceItAndAdmitsTheRestAreGone() {
        val morning = Habits.steps(null, raw = 800_000L, todayStartMs = TODAY)
        val walked = Habits.steps(morning, raw = 806_000L, todayStartMs = TODAY)
        assertEquals(6_000L, walked.today)

        // Phone restarts; the counter begins again and now reads 400.
        val after = Habits.steps(walked, raw = 400L, todayStartMs = TODAY)
        assertEquals("the 6000 before the reboot are unrecoverable", 400L, after.today)
        assertEquals(0L, after.baseline)
        assertTrue(after.partial)

        // And it keeps counting from there rather than re-triggering on every reading.
        val more = Habits.steps(after, raw = 1_500L, todayStartMs = TODAY)
        assertEquals(1_500L, more.today)
    }

    /** A nonsense reading must not become a negative step count. */
    @Test
    fun aNegativeReadingIsRefusedRatherThanSubtracted() {
        val s = Habits.steps(null, raw = 5_000L, todayStartMs = TODAY)
        val bad = Habits.steps(s, raw = -1L, todayStartMs = TODAY)
        assertEquals("the last good reading stands", s, bad)
        // …and with no history at all it is still not negative.
        assertEquals(0L, Habits.steps(null, raw = -1L, todayStartMs = TODAY).today)
    }

    @Test
    fun aHandfulOfStepsIsNotWorthSaying() {
        val s = Habits.steps(null, raw = 1_000L, todayStartMs = TODAY)
        assertNull(Habits.describe(Habits.steps(s, raw = 1_050L, todayStartMs = TODAY)))
        assertNull(Habits.describe(null))
        assertEquals(
            "4000 steps today",
            Habits.describe(Habits.steps(s, raw = 5_000L, todayStartMs = TODAY)),
        )
    }

    @Test
    fun anIncompleteCountSaysSo() {
        val s = Habits.steps(null, raw = 9_000L, todayStartMs = TODAY)
        val walked = Habits.steps(s, raw = 12_000L, todayStartMs = TODAY)
        val after = Habits.steps(walked, raw = 2_000L, todayStartMs = TODAY)
        assertEquals("2000 steps since the phone restarted", Habits.describe(after))
    }

    /** Every habit says what keeping it buys, because a bare label is a chore list. */
    @Test
    fun everyHabitExplainsItself() {
        for (h in Habits.Habit.entries) {
            assertTrue(h.label.isNotBlank())
            assertTrue("${h.name} has no blurb", h.blurb.length > 20)
        }
    }
}
