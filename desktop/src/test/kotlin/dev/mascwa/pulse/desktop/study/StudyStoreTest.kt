package dev.mascwa.pulse.desktop.study

import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.telemetry.DailyLesson
import dev.mascwa.pulse.desktop.telemetry.Recall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The store, exercised against the **real bundled library** rather than a fixture.
 *
 * This is the half of the study feature that CI can only compile on Android — the store there is
 * DataStore-backed and needs a device. Here it is a plain file, so the whole loop (compose a path,
 * teach a real guide, be asked, answer, come back later, survive a restart) genuinely runs. Every
 * defect it catches is one the Android side would only have found on the Pixel.
 */
class StudyStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val library = LibraryRepository(Json { ignoreUnknownKeys = true })

    private fun store() = StudyStore(library, path = tmp.root.toPath().resolve("study.json"))

    // ---- the path ------------------------------------------------------------------------------------

    @Test
    fun enrollingComposesARealPathThroughTheBundledLibrary() = runBlocking {
        val s = store()
        assertNull("nothing enrolled yet", s.syllabus())
        s.enroll("first aid and emergencies")
        val path = s.syllabus()!!
        assertFalse(path.isEmpty)
        assertEquals("first aid and emergencies", path.goal)
        // Every step must be a guide that actually exists and opens.
        path.steps.forEach { assertNotNull("${it.guideId} is not in the library", library.guide(it.guideId)) }
    }

    @Test
    fun everySuggestedGoalTheLibrarySupportsComposesSomething() = runBlocking {
        val goals = store().suggestedGoals()
        assertTrue("no goal is supported by the bundled library", goals.isNotEmpty())
        goals.forEach { goal ->
            val s = store()
            s.enroll(goal)
            assertFalse("suggested goal '$goal' composes nothing", s.syllabus()!!.isEmpty)
        }
    }

    @Test
    fun enrollingSomewhereElseDropsTheOldPathsProgress() = runBlocking {
        val s = store()
        s.enroll("first aid and emergencies")
        val first = s.syllabus()!!.steps.first().guideId
        s.markRead(first)
        assertEquals(setOf(first), s.completedIds())
        s.enroll("stargazing and astronomy")
        assertTrue("progress belonged to the old path", s.completedIds().isEmpty())
        // Re-enrolling in the SAME goal keeps it.
        s.markRead(s.syllabus()!!.steps.first().guideId)
        val kept = s.completedIds()
        s.enroll("Stargazing And Astronomy")
        assertEquals(kept, s.completedIds())
    }

    // ---- being taught, and asked ------------------------------------------------------------------------

    @Test
    fun teachingARealGuideProducesAnswerableQuestionsDueNow() = runBlocking {
        val s = store()
        val guide = library.index().first { it.id == "first-aid" || it.headings.size >= 4 }
        val made = s.teach(guide.id)
        assertTrue("no question could be made from ${guide.id}", made.isNotEmpty())
        assertTrue(made.size <= StudyStore.MAX_QUESTIONS_PER_LESSON)
        assertTrue(made.all { it.prompt.isNotBlank() && it.answer.isNotBlank() })
        assertEquals(made.size, s.dueCount())
    }

    /** Re-teaching must never reset a schedule that has already been earned. */
    @Test
    fun reTeachingKeepsTheProgressAQuestionAlreadyHas() = runBlocking {
        val s = store()
        val id = library.index().first().id
        val made = s.teach(id)
        val first = made.first().id
        val after = s.grade(first, Recall.Grade.GOOD)!!
        assertTrue(after.reps == 1)

        s.teach(id)
        val same = s.items.value.first { it.question.id == first }
        assertEquals("re-teaching reset the card", 1, same.card.reps)
        assertEquals(after.dueAtMs, same.card.dueAtMs)
    }

    @Test
    fun answeringMovesTheCardOutOfTheQueueAndSchedulesItsReturn() = runBlocking {
        val s = store()
        s.teach(library.index().first().id)
        val before = s.dueCount()
        val q = s.due(limit = 1).single()
        val next = s.grade(q.question.id, Recall.Grade.GOOD)!!
        assertEquals(before - 1, s.dueCount())
        assertTrue("must come back later, not now", next.dueAtMs > System.currentTimeMillis())
        assertEquals(Recall.FIRST_DAYS, next.intervalDays, 1e-9)
    }

    @Test
    fun forgettingBringsItStraightBack() = runBlocking {
        val s = store()
        s.teach(library.index().first().id)
        val q = s.due(limit = 1).single()
        s.grade(q.question.id, Recall.Grade.FORGOT)
        // A lapse returns within the day — so at a day from now it is due again.
        assertTrue(s.dueCount(System.currentTimeMillis() + 86_400_000L) >= 1)
    }

    @Test
    fun gradingSomethingThatIsNotHeldIsANoOpRatherThanACrash() = runBlocking {
        val s = store()
        assertNull(s.grade("no-such-question", Recall.Grade.GOOD))
    }

    // ---- today --------------------------------------------------------------------------------------------

    @Test
    fun aDueReviewOutranksAFreshLesson() = runBlocking {
        val s = store()
        s.teach(library.index().first().id)
        assertEquals(DailyLesson.Kind.REVIEW, s.today()!!.kind)
    }

    @Test
    fun anEnrolledPathIsWhatIsOfferedWhenNothingIsDue() = runBlocking {
        val s = store()
        s.enroll("home repair and maintenance")
        val lesson = s.today()!!
        assertEquals(DailyLesson.Kind.SYLLABUS, lesson.kind)
        assertEquals(s.syllabus()!!.steps.first().guideId, lesson.guideId)
        assertTrue(lesson.reason.contains("home repair and maintenance"))
    }

    @Test
    fun withNothingEnrolledSomethingUnreadIsStillOffered() = runBlocking {
        val lesson = store().today()!!
        assertEquals(DailyLesson.Kind.ROTATION, lesson.kind)
        assertNotNull(library.guide(lesson.guideId))
    }

    @Test
    fun theRotationDoesNotRepeatWhatItHasAlreadyOffered() = runBlocking {
        val s = store()
        val seen = mutableSetOf<String>()
        repeat(6) { day ->
            val lesson = s.today(dayIndex = day)!!
            assertTrue("offered ${lesson.guideId} twice", seen.add(lesson.guideId))
            s.markRead(lesson.guideId)
        }
    }

    // ---- persistence ----------------------------------------------------------------------------------------

    /**
     * The whole point of a schedule is that it outlives the session. A store that loses its deck on
     * close is a quiz, not spaced repetition.
     */
    @Test
    fun theDeckAndThePathSurviveARestart() = runBlocking {
        val first = store()
        first.enroll("understanding the weather")
        val taught = first.teach(first.syllabus()!!.steps.first().guideId)
        first.grade(taught.first().id, Recall.Grade.EASY)
        first.flushNow()

        val second = store()
        assertEquals("understanding the weather", second.syllabus()!!.goal)
        assertEquals(taught.size, second.items.value.size)
        val restored = second.items.value.first { it.question.id == taught.first().id }
        assertEquals(1, restored.card.reps)
        assertTrue(restored.card.ease > Recall.START_EASE)
        assertEquals(taught.first().prompt, restored.question.prompt)
    }

    @Test
    fun clearingLeavesNothingBehindOnDiskOrInMemory() = runBlocking {
        val s = store()
        s.enroll("first aid and emergencies")
        s.teach(s.syllabus()!!.steps.first().guideId)
        s.flushNow()
        s.clear()
        assertEquals("", s.goal.value)
        assertTrue(s.items.value.isEmpty())
        assertEquals(0, s.dueCount())
        assertNull(store().syllabus())
    }

    /** A corrupt file must not erase what a later write would otherwise save — nor crash the app. */
    @Test
    fun anUnreadableFileIsToleratedRatherThanFatal() = runBlocking {
        val file = tmp.root.toPath().resolve("study.json")
        java.nio.file.Files.writeString(file, "{ this is not json")
        val s = StudyStore(library, path = file)
        assertEquals("", s.goal.value)
        s.enroll("stargazing and astronomy")
        assertNotNull(s.syllabus())
    }
}
