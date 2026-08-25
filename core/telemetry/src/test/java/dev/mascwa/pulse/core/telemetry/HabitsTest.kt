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

    private val DAY_MS = 86_400_000L
    private val TODAY = 1_700_000_000_000L / DAY_MS * DAY_MS
    private fun d(n: Int) = TODAY - n * DAY_MS

    /**
     * The calendar these fixtures live in — UTC, where every day really is 86,400,000 ms.
     *
     * ⚠️ That is exactly why the parameter exists rather than being assumed: on a real device it is
     * NOT true, and `theStreakSurvivesADaylightSavingTransition` below builds a grid where it is not.
     */
    private val evenDays: (Long) -> Long = { it - DAY_MS }

    // ------------------------------------------------------------------------------- the streak

    @Test
    fun nothingLoggedIsNoStreakRatherThanADivisionByZero() {
        val s = Habits.streak(emptySet(), TODAY, evenDays)
        assertEquals(0, s.current)
        assertEquals(0, s.longest)
        assertNull(s.lastMs)
        assertFalse(s.doneToday)
        assertNull(Habits.summary(s))
    }

    @Test
    fun aRunEndingTodayCountsToday() {
        // today, -1, -2 = three consecutive days ending today.
        val s = Habits.streak(setOf(TODAY, d(1), d(2)), TODAY, evenDays)
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
        val s = Habits.streak(setOf(d(1), d(2), d(3)), TODAY, evenDays)
        assertEquals(3, s.current)
        assertFalse("today is genuinely not logged", s.doneToday)
        assertEquals("3 days, up to yesterday.", Habits.summary(s))
    }

    /** But a whole day gone by with nothing in it does break it. */
    @Test
    fun aWholeMissedDayBreaksTheRun() {
        // Newest is two days ago: yesterday passed with nothing logged.
        val s = Habits.streak(setOf(d(2), d(3), d(4)), TODAY, evenDays)
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
        val s = Habits.streak(days, TODAY, evenDays)
        assertEquals(2, s.current)
        assertEquals(5, s.longest)
    }

    @Test
    fun oneDayReadsAsOneDay() {
        assertEquals("Today.", Habits.summary(Habits.streak(setOf(TODAY), TODAY, evenDays)))
        assertEquals("Yesterday.", Habits.summary(Habits.streak(setOf(d(1)), TODAY, evenDays)))
    }

    /** Out-of-order input is a set, and a set has no order. The rule must not depend on one. */
    @Test
    fun theInputOrderCannotChangeTheAnswer() {
        val a = Habits.streak(linkedSetOf(d(2), TODAY, d(1)), TODAY, evenDays)
        val b = Habits.streak(linkedSetOf(TODAY, d(1), d(2)), TODAY, evenDays)
        assertEquals(a, b)
        assertEquals(3, a.current)
    }

    /**
     * ⚠️ Silent at zero, not "0 days". Four habits each announcing a broken streak is a wall of
     * failure on the screen somebody opened to work out what to have for lunch.
     */
    @Test
    fun aBrokenStreakSaysNothingAtAll() {
        assertNull(Habits.summary(Habits.streak(setOf(d(9)), TODAY, evenDays)))
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

    // ----------------------------------------------------------------------- the uneven calendar

    /**
     * ⚠️ **The reason [Habits.streak] asks the caller what "the day before" means.**
     *
     * These day starts are the ones a real calendar produces around a clock change — the run below
     * spans a 23-hour day, so two of its consecutive days are 23 hours apart and none of the usual
     * `difference == 86,400,000` tests hold. Comparing against a constant splits the run there, and
     * worse, the "ended yesterday" test fails outright the following day, so a long streak reads
     * **zero** to somebody who logged yesterday and has not yet logged today.
     */
    private val short = 23 * 3_600_000L

    /** Day starts for five consecutive days where the middle one was only 23 hours long. */
    private fun unevenWeek(today: Long) = listOf(
        today - 24 * 3_600_000L - 24 * 3_600_000L - short - 24 * 3_600_000L, // d-4
        today - 24 * 3_600_000L - 24 * 3_600_000L - short,                   // d-3
        today - 24 * 3_600_000L - 24 * 3_600_000L,                           // d-2
        today - 24 * 3_600_000L,                                             // d-1
        today,                                                               // today
    )

    /** The caller's calendar: the day before each of the above is the previous entry. */
    private fun unevenDayBefore(days: List<Long>): (Long) -> Long = { d ->
        val i = days.indexOf(d)
        if (i > 0) days[i - 1] else d - DAY_MS
    }

    @Test
    fun theStreakSurvivesADaylightSavingTransition() {
        val days = unevenWeek(TODAY)
        val s = Habits.streak(days.toSet(), TODAY, unevenDayBefore(days))
        assertEquals("five consecutive days is five", 5, s.current)
        assertEquals(5, s.longest)

        // And the arithmetic this replaced splits it: only the two 24-hour gaps at the end survive,
        // so the run reads three rather than five.
        val byStride = Habits.streak(days.toSet(), TODAY, evenDays)
        assertEquals("the constant-difference rule loses the days before the change", 3, byStride.current)
    }

    @Test
    fun aRunEndingYesterdayIsStillCurrentAcrossATransition() {
        // Nothing logged today. Yesterday is 23 hours before today, not 24.
        val today = TODAY
        val days = listOf(today - short - 24 * 3_600_000L, today - short)
        val s = Habits.streak(days.toSet(), today, unevenDayBefore(days + today))
        assertEquals("logged yesterday, so the run is still alive", 2, s.current)
        assertFalse(s.doneToday)

        // The constant-difference rule cannot even see yesterday, so it reports the streak broken.
        assertEquals(0, Habits.streak(days.toSet(), today, evenDays).current)
    }
}
