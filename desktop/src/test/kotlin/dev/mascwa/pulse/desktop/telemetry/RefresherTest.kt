// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/RefresherTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefresherTest {

    private val day = 86_400_000L
    private val now = 1_000 * day

    private fun item(
        guide: String,
        n: Int,
        dueDaysAgo: Double,
        intervalDays: Double = 3.0,
        reps: Int = 3,
    ) = Refresher.Item(
        guideId = guide,
        guideTitle = guide.replaceFirstChar { it.uppercase() },
        card = Recall.Card(
            id = "$guide:$n",
            dueAtMs = now - (dueDaysAgo * day).toLong(),
            intervalDays = intervalDays,
            reps = reps,
        ),
    )

    private fun attempts(guide: String, right: Int, wrong: Int) =
        (0 until right).map {
            StudyProgress.Attempt("$guide:r$it", guide, correct = true, atMs = now - day)
        } + (0 until wrong).map {
            StudyProgress.Attempt("$guide:w$it", guide, correct = false, atMs = now - day)
        }

    // ---- how long you have been gone -------------------------------------------------------------

    @Test
    fun theBandsAreTheOnesThatChangeWhatShouldHappen() {
        assertEquals(Refresher.Layoff.NONE, Refresher.layoff(now - day, now))
        assertEquals(Refresher.Layoff.SHORT, Refresher.layoff(now - 4 * day, now))
        assertEquals(Refresher.Layoff.LONG, Refresher.layoff(now - 10 * day, now))
        assertEquals(Refresher.Layoff.COLD, Refresher.layoff(now - 90 * day, now))
    }

    /** Never having studied is not an absence — there is nothing to come back to. */
    @Test
    fun neverHavingStudiedIsNotALayoff() {
        assertEquals(Refresher.Layoff.NONE, Refresher.layoff(0L, now))
    }

    @Test
    fun spansReadAsSpansNotAsAges() {
        assertEquals("A day", Refresher.describeAway(day))
        assertEquals("5 days", Refresher.describeAway(5 * day))
        assertEquals("3 weeks", Refresher.describeAway(21 * day))
        assertEquals("3 months", Refresher.describeAway(90 * day))
    }

    // ---- the cap, which is the point ---------------------------------------------------------------

    /**
     * ⚠️ The failure this whole file exists to prevent. Two hundred cards fall due over a month away;
     * plain spaced repetition hands over all two hundred, and that pile is the most common reason a
     * review habit is abandoned.
     */
    @Test
    fun aMonthAwayDoesNotProduceAWallOfCards() {
        val items = (0 until 200).map { item("g${it % 40}", it, dueDaysAgo = 30.0) }
        val plan = Refresher.plan(items, emptyList(), lastStudiedAtMs = now - 40 * day, nowMs = now)
        assertNotNull(plan)
        assertEquals(Refresher.Layoff.COLD, plan!!.layoff)
        assertEquals(Refresher.COLD_STEPS, plan.steps.size)
        assertEquals(200, plan.dueTotal)
        assertEquals(200 - Refresher.COLD_STEPS, plan.heldBack)
        // And it says so, rather than quietly hiding 194 cards.
        assertTrue(plan.note(), plan.note().contains("200"))
    }

    /** A plan that is eight cards from one guide reads as redoing a chapter, not as getting back up. */
    @Test
    fun aPlanInterleavesRatherThanRerunningOneGuide() {
        val items = (0 until 40).map { item("water", it, dueDaysAgo = 12.0) }
        val plan = Refresher.plan(items, emptyList(), lastStudiedAtMs = now - 12 * day, nowMs = now)!!
        assertEquals(Refresher.MAX_PER_GUIDE, plan.steps.size)
        assertTrue(plan.steps.all { it.item.guideId == "water" })
    }

    @Test
    fun aShortBreakGetsAShortWayBack() {
        val items = (0 until 30).map { item("g${it % 10}", it, dueDaysAgo = 4.0) }
        val plan = Refresher.plan(items, emptyList(), lastStudiedAtMs = now - 4 * day, nowMs = now)!!
        assertEquals(Refresher.Layoff.SHORT, plan.layoff)
        assertEquals(Refresher.SHORT_STEPS, plan.steps.size)
    }

    // ---- what is offered, and in what order ----------------------------------------------------------

    /**
     * Starting a cold return on your worst material is how a return becomes a last visit. The plan opens
     * with something the record says you can do.
     */
    @Test
    fun aLongAbsenceOpensWithSomethingYouKnow() {
        val items = listOf(
            item("strong", 0, dueDaysAgo = 5.0, intervalDays = 20.0),
            item("weak", 0, dueDaysAgo = 20.0),
            item("weak", 1, dueDaysAgo = 20.0),
        )
        val record = attempts("strong", right = 9, wrong = 1) + attempts("weak", right = 1, wrong = 7)
        val plan = Refresher.plan(items, record, lastStudiedAtMs = now - 20 * day, nowMs = now)!!

        assertEquals(Refresher.Reason.WARMUP, plan.steps.first().reason)
        assertEquals("strong", plan.steps.first().item.guideId)
        // Then the material that has been going badly.
        assertEquals(Refresher.Reason.WEAK, plan.steps[1].reason)
        assertEquals("weak", plan.steps[1].item.guideId)
    }

    /** A few days off needs no easing in; going straight at the weak material is the right answer. */
    @Test
    fun aShortBreakDoesNotGetAWarmUp() {
        val items = listOf(
            item("strong", 0, dueDaysAgo = 1.0, intervalDays = 20.0),
            item("weak", 0, dueDaysAgo = 1.0),
        )
        val record = attempts("strong", right = 9, wrong = 1) + attempts("weak", right = 1, wrong = 7)
        val plan = Refresher.plan(items, record, lastStudiedAtMs = now - 3 * day, nowMs = now)!!
        assertTrue(plan.steps.none { it.reason == Refresher.Reason.WARMUP })
        assertEquals("weak", plan.steps.first().item.guideId)
    }

    /** An invented warm-up that turns out to be hard is worse than no warm-up. */
    @Test
    fun noWarmUpIsOfferedWithoutAGuideGoodEnoughToTrust() {
        val items = listOf(item("water", 0, dueDaysAgo = 20.0), item("fire", 0, dueDaysAgo = 20.0))
        val record = attempts("water", right = 2, wrong = 6) + attempts("fire", right = 3, wrong = 5)
        val plan = Refresher.plan(items, record, lastStudiedAtMs = now - 20 * day, nowMs = now)!!
        assertTrue(plan.steps.none { it.reason == Refresher.Reason.WARMUP })
    }

    /**
     * What the absence itself cost. A card left three times its own gap is a different problem from one
     * a day overdue, and saying which is which is why the reason is shown at all.
     */
    @Test
    fun aCardLeftFarLongerThanItsOwnGapIsCalledDecayed() {
        // Due 30 days ago on a 3-day interval: ten times the gap it was scheduled for.
        assertTrue(Refresher.decayed(Recall.Card("a", now - 30 * day, intervalDays = 3.0), now))
        // Due yesterday on a 30-day interval: overdue, but nothing has decayed.
        assertTrue(!Refresher.decayed(Recall.Card("b", now - day, intervalDays = 30.0), now))
        // A card never reviewed has no gap to have exceeded.
        assertTrue(!Refresher.decayed(Recall.Card("c", now - 30 * day), now))
    }

    @Test
    fun decayedMaterialComesBeforeOrdinaryBacklog() {
        val items = listOf(
            // Overdue by a day against a long interval — ordinary backlog.
            item("fresh", 0, dueDaysAgo = 1.0, intervalDays = 40.0),
            // Overdue by twenty days against a two-day interval — genuinely gone.
            item("gone", 0, dueDaysAgo = 20.0, intervalDays = 2.0),
        )
        val plan = Refresher.plan(items, emptyList(), lastStudiedAtMs = now - 20 * day, nowMs = now)!!
        assertEquals(Refresher.Reason.DECAYED, plan.steps.first().reason)
        assertEquals("gone", plan.steps.first().item.guideId)
        assertEquals(Refresher.Reason.OVERDUE, plan.steps[1].reason)
    }

    @Test
    fun everyStepAppearsOnlyOnce() {
        val items = (0 until 12).map { item("g${it % 4}", it, dueDaysAgo = 15.0) }
        val record = attempts("g0", right = 1, wrong = 7) + attempts("g1", right = 8, wrong = 1)
        val plan = Refresher.plan(items, record, lastStudiedAtMs = now - 15 * day, nowMs = now)!!
        assertEquals(plan.steps.size, plan.steps.map { it.item.card.id }.distinct().size)
    }

    // ---- when not to offer one -----------------------------------------------------------------------

    @Test
    fun noAbsenceMeansNoPlan() {
        val items = listOf(item("water", 0, dueDaysAgo = 1.0))
        assertNull(Refresher.plan(items, emptyList(), lastStudiedAtMs = now - day, nowMs = now))
    }

    /**
     * Nothing due means the ordinary screen is right. Deliberately does not invent something to re-read:
     * [DailyLesson] already decides that, and two things choosing it would eventually disagree.
     */
    @Test
    fun nothingDueMeansNoPlan() {
        val items = listOf(item("water", 0, dueDaysAgo = -5.0))
        assertNull(Refresher.plan(items, emptyList(), lastStudiedAtMs = now - 30 * day, nowMs = now))
    }
}
