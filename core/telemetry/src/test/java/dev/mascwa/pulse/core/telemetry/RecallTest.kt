package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.Recall.Grade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule, which is the whole difference between being told something and learning it.
 *
 * Every expected value below is computed from the shipped constants rather than recalled, and the
 * arithmetic is written out where it is not obvious.
 */
class RecallTest {

    private val t0 = 1_700_000_000_000L
    private fun days(c: Recall.Card, from: Long = t0) = (c.dueAtMs - from) / Recall.DAY_MS

    private fun drill(grade: Grade, times: Int, start: Recall.Card = Recall.newCard("q", t0)): Recall.Card {
        var c = start
        var now = t0
        repeat(times) {
            c = Recall.review(c, grade, now)
            now = c.dueAtMs
        }
        return c
    }

    // ---- the ordinary path -------------------------------------------------------------------------

    @Test
    fun aNewCardIsDueImmediatelyBecauseYouWereJustTaughtIt() {
        val c = Recall.newCard("q", t0)
        assertEquals(t0, c.dueAtMs)
        assertEquals(0, c.reps)
        assertEquals(Recall.START_EASE, c.ease, 1e-9)
        assertTrue(Recall.due(listOf(c), t0).isNotEmpty())
    }

    /**
     * The first two gaps are fixed, not ease-driven: a card reviewed once has no evidence behind its
     * ease, and letting it jump straight to days is how spaced repetition loses things early.
     */
    @Test
    fun theFirstTwoSuccessesUseFixedShortGaps() {
        val first = Recall.review(Recall.newCard("q", t0), Grade.GOOD, t0)
        assertEquals(Recall.FIRST_DAYS, first.intervalDays, 1e-9)
        assertEquals(1.0, days(first), 1e-6)

        val second = Recall.review(first, Grade.GOOD, first.dueAtMs)
        assertEquals(Recall.SECOND_DAYS, second.intervalDays, 1e-9)
    }

    /** From the third success the gap is interval × ease. 3 × 2.5 = 7.5 days. */
    @Test
    fun fromTheThirdSuccessTheGapMultipliesByEase() {
        val third = drill(Grade.GOOD, 3)
        assertEquals(3.0 * Recall.START_EASE, third.intervalDays, 1e-9)
        assertEquals(3, third.reps)
        assertEquals(Recall.START_EASE, third.ease, 1e-9)
    }

    @Test
    fun theGapGrowsMonotonicallyWhileYouKeepKnowingIt() {
        var c = Recall.newCard("q", t0)
        var now = t0
        var previous = 0.0
        repeat(8) {
            c = Recall.review(c, Grade.GOOD, now)
            now = c.dueAtMs
            assertTrue("interval shrank: $previous -> ${c.intervalDays}", c.intervalDays >= previous)
            previous = c.intervalDays
        }
    }

    // ---- grades differ ---------------------------------------------------------------------------------

    @Test
    fun hardGrowsSlowerThanGoodAndEasyGrowsFaster() {
        val base = drill(Grade.GOOD, 2)
        val hard = Recall.review(base, Grade.HARD, base.dueAtMs).intervalDays
        val good = Recall.review(base, Grade.GOOD, base.dueAtMs).intervalDays
        val easy = Recall.review(base, Grade.EASY, base.dueAtMs).intervalDays
        assertTrue("hard $hard should be under good $good", hard < good)
        assertTrue("easy $easy should exceed good $good", easy > good)
    }

    @Test
    fun repeatedHardAnswersPushTheEaseDownAndRepeatedEasyOnesUp() {
        assertTrue(drill(Grade.HARD, 6).ease < Recall.START_EASE)
        assertTrue(drill(Grade.EASY, 6).ease > Recall.START_EASE)
    }

    @Test
    fun theEaseIsClampedAtBothEnds() {
        assertEquals(Recall.MIN_EASE, drill(Grade.HARD, 40).ease, 1e-9)
        assertEquals(Recall.MAX_EASE, drill(Grade.EASY, 40).ease, 1e-9)
    }

    // ---- forgetting -------------------------------------------------------------------------------------

    @Test
    fun forgettingBringsItBackTheSameDayAndCountsAsALapse() {
        val mature = drill(Grade.GOOD, 5)
        val lapsed = Recall.review(mature, Grade.FORGOT, mature.dueAtMs)
        assertEquals(Recall.LAPSE_DAYS, lapsed.intervalDays, 1e-9)
        assertTrue("must return within the day", days(lapsed, mature.dueAtMs) < 1.0)
        assertEquals(0, lapsed.reps)
        assertEquals(mature.lapses + 1, lapsed.lapses)
    }

    /**
     * A lapse softens the ease rather than flooring it. Forgetting one thing once says less about the
     * card than SM-2 assumes, and flooring makes one bad day poison a card for months.
     */
    @Test
    fun aSingleLapseDoesNotDestroyTheEase() {
        val mature = drill(Grade.GOOD, 5)
        val lapsed = Recall.review(mature, Grade.FORGOT, mature.dueAtMs)
        assertEquals(mature.ease - Recall.EASE_LAPSE, lapsed.ease, 1e-9)
        assertTrue("ease must stay well above the floor after one lapse", lapsed.ease > Recall.MIN_EASE)
    }

    @Test
    fun recoveryAfterALapseStartsFromTheShortGapsAgain() {
        val lapsed = Recall.review(drill(Grade.GOOD, 5), Grade.FORGOT, t0)
        val back = Recall.review(lapsed, Grade.GOOD, lapsed.dueAtMs)
        assertEquals(Recall.FIRST_DAYS, back.intervalDays, 1e-9)
        assertEquals(1, back.reps)
    }

    // ---- the cap ------------------------------------------------------------------------------------------

    /**
     * Uncapped SM-2 sends a well-known card years out, which on a phone someone reinstalls every few
     * months means it silently never returns. Nothing waits longer than the ceiling.
     */
    @Test
    fun nothingIsEverScheduledBeyondTheCeiling() {
        val nearlyThere = drill(Grade.EASY, 29)
        val veteran = Recall.review(nearlyThere, Grade.EASY, nearlyThere.dueAtMs)
        assertEquals(Recall.MAX_INTERVAL_DAYS, veteran.intervalDays, 1e-9)
        // Measured from the review, not from t0: drill advances the clock each time, so a distance
        // from t0 is the sum of thirty gaps rather than the last one.
        assertTrue(days(veteran, nearlyThere.dueAtMs) <= Recall.MAX_INTERVAL_DAYS + 1)
    }

    // ---- the queue ------------------------------------------------------------------------------------------

    @Test
    fun theQueueIsMostOverdueFirstAndBounded() {
        val cards = (1..20).map { Recall.Card(id = "q$it", dueAtMs = t0 - it * 1_000L) }
        val due = Recall.due(cards, t0, limit = 5)
        assertEquals(5, due.size)
        // q20 is the most overdue.
        assertEquals(listOf("q20", "q19", "q18", "q17", "q16"), due.map { it.id })
        assertEquals(20, Recall.dueCount(cards, t0))
    }

    @Test
    fun cardsDueLaterAreNotInTheQueue() {
        val future = Recall.Card(id = "later", dueAtMs = t0 + 1)
        assertTrue(Recall.due(listOf(future), t0).isEmpty())
        assertEquals(0, Recall.dueCount(listOf(future), t0))
        // Exactly due counts as due — otherwise a card can sit one millisecond short forever.
        assertEquals(1, Recall.dueCount(listOf(future.copy(dueAtMs = t0)), t0))
    }

    // ---- knowing when it is known ------------------------------------------------------------------------------

    @Test
    fun learnedMeansSeveralSuccessesAtALongGapAndIsNeverRetirement() {
        assertFalse(Recall.isLearned(Recall.newCard("q", t0)))
        assertFalse(Recall.isLearned(drill(Grade.GOOD, 2)))
        val veteran = drill(Grade.GOOD, 6)
        assertTrue(Recall.isLearned(veteran))
        // Still scheduled: a fact you stop being asked is a fact you will eventually lose.
        assertTrue(veteran.dueAtMs > t0)
    }

    /**
     * An objectively-marked answer is right or wrong; how long it took is the only signal left for how
     * comfortably. Right but laboured is HARD — a fact you had to reconstruct is not one you know yet.
     */
    @Test
    fun anObjectiveAnswerEarnsItsGradeFromCorrectnessAndPace() {
        assertEquals(Grade.FORGOT, Recall.gradeFor(correct = false, elapsedMs = 2_000))
        // Wrong stays wrong however fast it was.
        assertEquals(Grade.FORGOT, Recall.gradeFor(correct = false, elapsedMs = 60_000))
        assertEquals(Grade.EASY, Recall.gradeFor(correct = true, elapsedMs = 3_000))
        assertEquals(Grade.GOOD, Recall.gradeFor(correct = true, elapsedMs = 15_000))
        assertEquals(Grade.HARD, Recall.gradeFor(correct = true, elapsedMs = 45_000))
    }

    /** With no timing to read, the schedule takes the answer at face value rather than inventing one. */
    @Test
    fun anUntimedCorrectAnswerIsSimplyGood() {
        assertEquals(Grade.GOOD, Recall.gradeFor(correct = true, elapsedMs = 0))
    }

    @Test
    fun theGapIsDescribedInWordsAPersonWouldUse() {
        assertEquals("later today", Recall.describeInterval(Recall.LAPSE_DAYS))
        assertEquals("tomorrow", Recall.describeInterval(1.0))
        assertEquals("in 3 days", Recall.describeInterval(3.0))
        assertEquals("in 2 months", Recall.describeInterval(60.0))
        assertEquals("in a year", Recall.describeInterval(400.0))
    }
}
