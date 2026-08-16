// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/DailyLessonTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import dev.mascwa.pulse.desktop.telemetry.DailyLesson.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's whole value is its priority order, so that is what these hold: a due review outranks
 * everything, an enrolled path outranks a guess, something you have to do outranks something you
 * once said you liked, and nothing is offered under a reason that is not true.
 */
class DailyLessonTest {

    private fun e(id: String, title: String, category: String, summary: String) =
        GuideSearch.Entry(id, title, category, summary)

    private val library = listOf(
        e("knots", "Knots & Cordage", "Skills", "Tying rope, bends, hitches and whipping"),
        e("bowline", "The Bowline and Its Variants", "Skills", "A loop that does not slip"),
        e("astronomy", "Navigating by the Stars", "Astronomy", "Polaris, the southern cross and latitude"),
        e("boiler", "Boiler Service and Maintenance", "Home & Repair", "Pressure, thermostats and an annual check"),
        // Mentions sourdough in the body only — the case the strict bar exists to refuse.
        e("bread", "Baking Bread", "Cooking — Techniques", "Flour, yeast, sourdough starters and the oven"),
        e("football", "Association Football Rules", "Sports & Fitness", "Offside, fouls and the laws of the game"),
    )

    private fun ctx(
        due: Int = 0,
        syllabus: Curriculum.Syllabus? = null,
        completed: Set<String> = emptySet(),
        tasks: List<String> = emptyList(),
        interests: List<String> = emptyList(),
        taught: Set<String> = emptySet(),
        day: Int = 0,
    ) = DailyLesson.StudyContext(
        dayIndex = day,
        dueCount = due,
        syllabus = syllabus,
        completed = completed,
        pendingTasks = tasks,
        interests = interests,
        library = library,
        taught = taught,
    )

    private val path = Curriculum.compose("knots and rope", library, emptyMap())

    // ---- the priority order ------------------------------------------------------------------------

    /** Being shown something once is not learning. Yesterday's material comes first, always. */
    @Test
    fun aDueReviewOutranksEverythingElse() {
        val l = DailyLesson.pick(ctx(due = 3, syllabus = path, tasks = listOf("boiler service"), interests = listOf("astronomy")))
        assertNotNull(l)
        assertEquals(Kind.REVIEW, l!!.kind)
        assertEquals(3, l.dueCount)
        assertEquals("3 questions to answer", l.headline)
    }

    @Test
    fun theReviewHeadlineIsWrittenForOneAsWellAsMany() {
        assertEquals("1 question to answer", DailyLesson.pick(ctx(due = 1))!!.headline)
    }

    @Test
    fun anEnrolledPathOutranksAGuess() {
        val l = DailyLesson.pick(ctx(syllabus = path, tasks = listOf("boiler service"), interests = listOf("astronomy")))
        assertEquals(Kind.SYLLABUS, l!!.kind)
        assertEquals(path.steps.first().guideId, l.guideId)
        assertTrue(l.reason, l.reason.startsWith("step 1 of "))
        assertTrue(l.reason.endsWith("knots and rope"))
    }

    @Test
    fun aFinishedPathStepsAsideRatherThanRepeatingItself() {
        val done = path.steps.map { it.guideId }.toSet()
        val l = DailyLesson.pick(ctx(syllabus = path, completed = done, interests = listOf("astronomy")))
        assertEquals(Kind.INTEREST, l!!.kind)
        assertEquals("astronomy", l.guideId)
    }

    /** What you have to do beats what you once said you liked. */
    @Test
    fun aRealTaskOutranksAStatedInterest() {
        val l = DailyLesson.pick(ctx(tasks = listOf("boiler service"), interests = listOf("astronomy")))
        assertEquals(Kind.TASK, l!!.kind)
        assertEquals("boiler", l.guideId)
        assertEquals("\"boiler service\" is on your list", l.reason)
    }

    @Test
    fun anInterestIsUsedWhenThereIsNothingOnTheList() {
        val l = DailyLesson.pick(ctx(interests = listOf("astronomy")))
        assertEquals(Kind.INTEREST, l!!.kind)
        assertEquals("astronomy", l.guideId)
        assertEquals("you follow astronomy", l.reason)
    }

    // ---- never a reason that is not true ----------------------------------------------------------------

    /**
     * The ranker always returns its closest match, so an ungated pick would caption a football guide
     * "because *call the dentist* is on your list". Nothing is better than a false reason.
     */
    @Test
    fun anAnchorTheLibraryCannotSupportIsNotDressedUp() {
        val l = DailyLesson.pick(ctx(tasks = listOf("call the dentist about my appointment")))
        // Falls through to the honest fallback rather than claiming a guide is about dentistry.
        assertEquals(Kind.ROTATION, l!!.kind)
        assertFalse(l.reason.contains("dentist"))
    }

    @Test
    fun anAnchorIsTriedInOrderAndTheFirstThatLandsWins() {
        val l = DailyLesson.pick(ctx(tasks = listOf("call the dentist", "bowline")))
        assertEquals(Kind.TASK, l!!.kind)
        assertEquals("bowline", l.guideId)
    }

    /**
     * ⚠️ The subject being absent from the library is exactly how an anchor is meant to fail, and
     * preferring the rarest word the library *does* know — which looks more accommodating — is much
     * worse. Over the real 581-guide library it keys on the leftover verb and produces "*service the
     * boiler before winter* is on your list" above **Severe Weather: Storms, Tornadoes and
     * Hurricanes**. A brand name in an otherwise answerable phrase is dropped by the same rule; that
     * trade is deliberate, because silence is the right failure here.
     */
    @Test
    fun aPhraseWhoseSubjectTheLibraryLacksIsDroppedRatherThanKeyedOnItsVerb() {
        val l = DailyLesson.pick(ctx(tasks = listOf("call the plumber about the immersion")))
        assertEquals(Kind.ROTATION, l!!.kind)
        assertFalse(l.reason.contains("plumber"))
    }

    /**
     * ⚠️ A guide that merely *mentions* the subject is not about it, and this is the strictest of the
     * three rules because a lesson asserts the connection nobody asked for. On the real library the
     * looser bar offers **Archaeological Excavation** for photography (a site-photography section)
     * and **Finding and Mapping Oven Hot Spots** for cycling (thermal cycling).
     */
    @Test
    fun aPassingMentionInTheBodyIsNotEnoughToBeOffered() {
        val l = DailyLesson.pick(ctx(interests = listOf("sourdough")))
        assertEquals(Kind.ROTATION, l!!.kind)
        assertFalse("only the summary mentions it", l.guideId == "bread")
    }

    @Test
    fun aGuideAlreadyTaughtIsNotOfferedAgain() {
        val l = DailyLesson.pick(ctx(interests = listOf("astronomy"), taught = setOf("astronomy")))
        assertNotNull(l)
        assertFalse("already taught", l!!.guideId == "astronomy")
    }

    // ---- the fallback ----------------------------------------------------------------------------------

    @Test
    fun withNothingToAnchorToSomethingUnreadIsStillOffered() {
        val l = DailyLesson.pick(ctx())
        assertEquals(Kind.ROTATION, l!!.kind)
        assertTrue(l.guideId.isNotBlank())
        assertEquals("something from the library you have not read", l.reason)
    }

    /**
     * Walking the id-sorted list in order teaches the library alphabetically. A coprime stride is the
     * fix, and this is what it is for: consecutive days must not be neighbours.
     */
    @Test
    fun theRotationStridesRatherThanWalkingAlphabetically() {
        val ids = (0..5).map { DailyLesson.pick(ctx(day = it))!!.guideId }
        assertEquals("every day should differ over one cycle", ids.size, ids.distinct().size)
        val sorted = library.map { it.id }.sorted()
        val positions = ids.map { sorted.indexOf(it) }
        assertFalse("consecutive days landed on neighbours: $positions", positions.zipWithNext().all { (a, b) -> b == a + 1 })
    }

    @Test
    fun theRotationIsStableForAGivenDay() {
        assertEquals(DailyLesson.pick(ctx(day = 9))!!.guideId, DailyLesson.pick(ctx(day = 9))!!.guideId)
    }

    /** A negative day index is a clock the caller got wrong, not a crash. */
    @Test
    fun anOutOfRangeDayStillLandsInsideTheLibrary() {
        assertNotNull(DailyLesson.pick(ctx(day = -7)))
        assertNotNull(DailyLesson.pick(ctx(day = Int.MAX_VALUE)))
        assertNotNull(DailyLesson.pick(ctx(day = Int.MIN_VALUE)))
    }

    // ---- nothing to offer --------------------------------------------------------------------------------

    @Test
    fun anEmptyLibraryOffersNothingRatherThanAnEmptyLesson() {
        assertNull(DailyLesson.pick(DailyLesson.StudyContext()))
    }

    @Test
    fun aLibraryEntirelyTaughtOffersNothingNew() {
        assertNull(DailyLesson.pick(ctx(taught = library.map { it.id }.toSet())))
    }

    /** But a review is still offered when everything has been read — that is the point of reviews. */
    @Test
    fun everythingReadStillLeavesTheReviewSession() {
        val l = DailyLesson.pick(ctx(due = 2, taught = library.map { it.id }.toSet()))
        assertEquals(Kind.REVIEW, l!!.kind)
    }

    // ---- the board line ------------------------------------------------------------------------------------

    @Test
    fun theBoardLineIsOneShortLineForEachKind() {
        assertEquals("2 questions to answer", DailyLesson.pick(ctx(due = 2))!!.boardLine)
        val read = DailyLesson.pick(ctx(interests = listOf("astronomy")))!!.boardLine
        assertEquals("Read: Navigating by the Stars", read)
        assertFalse("a board row is never two sentences", read.trimEnd('.').contains(". "))
    }
}
