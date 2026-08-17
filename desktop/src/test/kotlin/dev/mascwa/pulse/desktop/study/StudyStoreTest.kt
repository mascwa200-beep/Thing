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

    // ---- being asked, and marked --------------------------------------------------------------------

    /**
     * The claim the whole quiz layer rests on, checked against the **real library** rather than a
     * fixture — which is the only way to know it holds for the material actually shipped.
     */
    @Test
    fun everyQuestionTheStoreAsksCanBeAnsweredWithExactlyOneDefensibleOption() = runBlocking {
        val s = store()
        var built = 0
        var openRecall = 0
        // A spread of subjects, so this is not one guide's luck.
        for (goal in listOf("first aid and emergencies", "cooking and food safety", "stargazing and astronomy")) {
            s.enroll(goal)
            for (step in s.syllabus()!!.steps.take(4)) s.teach(step.guideId)
        }
        repeat(30) {
            val ask = s.nextAsk() ?: return@repeat
            val quiz = ask.quiz
            if (quiz == null) {
                openRecall++
            } else {
                built++
                assertEquals("two defensible answers in ${quiz.prompt}", 1, quiz.choices.count { it.correct })
                assertTrue(quiz.correctIndex >= 0)
                // Duplicated option text makes one of two identical options unanswerable.
                assertEquals(quiz.choices.size, quiz.choices.map { it.text }.distinct().size)
                assertTrue("an empty option", quiz.choices.all { it.text.isNotBlank() })
            }
            // Answer it so the queue advances; correctness alternates so accuracy is not degenerate.
            s.answer(ask.item.question.id, correct = built % 2 == 0)
        }
        assertTrue("the real library produced no multiple choice at all", built > 0)
        // Generation practice must survive alongside recognition — see QuizBuilder.asksOpenRecall.
        assertTrue("open recall was displaced entirely", openRecall > 0)
        println("real library: $built multiple choice, $openRecall open recall")
    }

    /** Marking is objective now, so the record has to remember what actually happened. */
    @Test
    fun answeringRecordsWhetherItWasRightAndTheRecordSurvivesARestart() = runBlocking {
        val path = tmp.root.toPath().resolve("study.json")
        val first = StudyStore(library, path = path)
        first.enroll("first aid and emergencies")
        val made = first.teach(first.syllabus()!!.steps.first().guideId)
        assertTrue(made.size >= 3)
        first.answer(made[0].id, correct = true, elapsedMs = 4_000)
        first.answer(made[1].id, correct = false, elapsedMs = 12_000)
        first.answer(made[2].id, correct = true, elapsedMs = 40_000)
        first.flushNow()

        val reopened = StudyStore(library, path = path).progress()
        assertEquals(3, reopened.answered)
        assertEquals(2, reopened.correct)
        assertEquals(1, reopened.incorrect)
        assertTrue(reopened.describeRatio(), reopened.describeRatio().contains("67%"))
    }

    /**
     * Pace decides the grade an objective answer earns, so the three should not come back together —
     * instant is EASY, laboured is HARD, and the intervals differ accordingly.
     */
    @Test
    fun howLongAnAnswerTookChangesWhenItComesBack() = runBlocking {
        val s = store()
        s.enroll("first aid and emergencies")
        val made = s.teach(s.syllabus()!!.steps.first().guideId)
        val quick = s.answer(made[0].id, correct = true, elapsedMs = 2_000)!!
        val laboured = s.answer(made[1].id, correct = true, elapsedMs = 50_000)!!
        val wrong = s.answer(made[2].id, correct = false, elapsedMs = 5_000)!!
        assertTrue("instant should not come back sooner than laboured", quick.dueAtMs >= laboured.dueAtMs)
        assertEquals(1, wrong.lapses)
    }

    /** A self-graded answer says how it FELT; putting a right-or-wrong on it would invent a number. */
    @Test
    fun aSelfGradedAnswerLeavesTheAccuracyFigureAlone() = runBlocking {
        val s = store()
        s.enroll("first aid and emergencies")
        val made = s.teach(s.syllabus()!!.steps.first().guideId)
        s.grade(made.first().id, Recall.Grade.GOOD)
        val p = s.progress()
        assertEquals(0, p.answered)
        // It still counts as having studied — that is a different question from having been right.
        assertTrue(p.lastStudiedAtMs > 0L)
    }

    // ---- sittings ---------------------------------------------------------------------------------------

    /** Time credited from evidence of work, never from the span of an open window. */
    @Test
    fun aWindowLeftOpenBanksTheWorkDoneInItAndNotTheHours() = runBlocking {
        val s = store()
        val start = System.currentTimeMillis() - 8 * 60 * 60 * 1000L
        s.openSession(nowMs = start)
        s.enroll("first aid and emergencies")
        val made = s.teach(s.syllabus()!!.steps.first().guideId)
        s.answer(made.first().id, correct = true)
        s.closeSession()
        val studied = s.progress().studiedMs
        assertTrue("credited $studied ms for one answer", studied > 0)
        assertTrue("eight hours were credited to one answer", studied < 60 * 60 * 1000L)
    }

    /** A window opened and shut with nothing in it is not evidence of anything. */
    @Test
    fun anInstantSittingIsNotBanked() = runBlocking {
        val s = store()
        val now = System.currentTimeMillis()
        s.openSession(nowMs = now)
        s.closeSession(nowMs = now)
        assertEquals(0L, s.progress().studiedMs)
    }

    // ---- coming back -------------------------------------------------------------------------------------

    /** No absence, no plan — the ordinary screen is the right answer. */
    @Test
    fun thereIsNoWayBackWhenYouNeverLeft() = runBlocking {
        val s = store()
        s.enroll("first aid and emergencies")
        s.teach(s.syllabus()!!.steps.first().guideId)
        assertNull(s.refresher())
    }

    /**
     * A month away with a real deck: capped, ordered, and saying what it is holding aside. Exercised
     * through the store because the interesting part is the join between the deck and the history.
     */
    @Test
    fun aMonthAwayGetsACappedWayBackIntoRealMaterial() = runBlocking {
        val s = store()
        val longAgo = System.currentTimeMillis() - 45L * 86_400_000L
        s.enroll("first aid and emergencies")
        for (step in s.syllabus()!!.steps.take(6)) s.teach(step.guideId, nowMs = longAgo)
        // One answered attempt, long ago, so the store knows when you were last here.
        val first = s.due(nowMs = longAgo, limit = 1).first()
        s.answer(first.question.id, correct = true, nowMs = longAgo)

        val plan = s.refresher()!!
        assertTrue("everything was dumped at once", plan.steps.size <= 8)
        assertTrue(plan.dueTotal >= plan.steps.size)
        assertTrue("the plan is empty", plan.steps.isNotEmpty())
        // Every step must point at a card the store actually holds.
        val held = s.items.value.map { it.card.id }.toSet()
        plan.steps.forEach { assertTrue("${it.item.card.id} is not held", it.item.card.id in held) }
        // And each one must be askable.
        assertNotNull(s.askFor(plan.steps.first().item.card.id))
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
