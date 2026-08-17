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
     * Pace decides the grade an objective answer earns — instant is EASY, laboured is HARD, and a
     * miss is a lapse.
     *
     * ⚠️ **What it does NOT decide is the first interval.** [Recall.review] uses a fixed `FIRST_DAYS`
     * for a first successful review whatever the grade, because a card answered once has no evidence
     * behind its ease yet. The difference shows up in the *ease*, and only compounds into the
     * schedule from the third review — which is what
     * `aHintedAnswerIsStillCorrectButComesBackSooner` measures.
     *
     * ⚠️ This test previously asserted `quick.dueAtMs >= laboured.dueAtMs`, which was wrong twice
     * over. Both intervals are one day, so the comparison was really between two separate
     * `System.currentTimeMillis()` reads — it passed only while the clock did not tick between the
     * two calls, and failed on a loaded CI runner where it did. The clock is pinned here so the
     * assertion is about the schedule rather than about how busy the machine is.
     */
    @Test
    fun howLongAnAnswerTookChangesWhenItComesBack() = runBlocking {
        val s = store()
        val now = 1_700_000_000_000L
        s.enroll("first aid and emergencies")
        val made = s.teach(s.syllabus()!!.steps.first().guideId, nowMs = now)
        val quick = s.answer(made[0].id, correct = true, elapsedMs = 2_000, nowMs = now)!!
        val laboured = s.answer(made[1].id, correct = true, elapsedMs = 50_000, nowMs = now)!!
        val wrong = s.answer(made[2].id, correct = false, elapsedMs = 5_000, nowMs = now)!!

        // A first success is a fixed gap either way — assert that, rather than a difference that
        // does not exist yet.
        assertEquals(Recall.FIRST_DAYS, quick.intervalDays, 0.001)
        assertEquals(Recall.FIRST_DAYS, laboured.intervalDays, 0.001)
        assertEquals(quick.dueAtMs, laboured.dueAtMs)

        // The pace IS recorded, in the ease, and that is what diverges the schedule later.
        assertTrue(
            "instant should not be judged harder than laboured (${quick.ease} vs ${laboured.ease})",
            quick.ease > laboured.ease,
        )
        assertEquals(1, wrong.lapses)
        assertTrue("a miss must come back sooner than a success", wrong.dueAtMs < quick.dueAtMs)
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

    // ---- the course map and bounded sets --------------------------------------------------------------

    /**
     * The course map over a real path: every step present, none dropped, and the percentage moving as
     * real work goes in.
     *
     * ⚠️ The bar starting at zero and *staying* there is the failure this pins. Points-weighting exists
     * so a week of genuine work shows, and a regression to counting only finished skills would leave a
     * learner looking at 0% for a fortnight — which is the fastest way to make somebody stop.
     */
    @Test
    fun theCourseMapCoversTheWholePathAndMovesAsWorkGoesIn() = runBlocking {
        val s = store()
        assertNull("nothing enrolled yet", s.course())
        s.enroll("first aid and emergencies")
        val path = s.syllabus()!!

        val fresh = s.course()!!
        assertEquals(path.steps.size, fresh.total)
        assertEquals(path.steps.map { it.guideId }, fresh.skills.map { it.guideId })
        assertEquals("untouched work cannot already be under way", 0, fresh.percent)
        assertNotNull("a fresh course must still recommend a first step", fresh.nextUp())

        // Teach and answer one skill: the bar must leave zero.
        val target = path.steps.first().guideId
        s.teach(target)
        repeat(4) {
            val card = s.due(limit = 20).firstOrNull { it.question.guideId == target } ?: return@repeat
            s.answer(card.question.id, correct = true)
        }
        val worked = s.course()!!
        assertTrue("real work inside a skill did not move the bar", worked.percent > 0)
        assertTrue(worked.started >= 1)
        assertTrue("the taught skill holds no cards", worked.skills.first { it.guideId == target }.cards > 0)
    }

    /** Practice teaches on demand: "work at this" must work on a guide only ever read. */
    @Test
    fun practiceOnAnUntaughtSkillTeachesItFirst() = runBlocking {
        val s = store()
        s.enroll("first aid and emergencies")
        val skill = s.course()!!.skills.first()
        assertEquals("nothing should be held yet", 0, skill.cards)

        val set = s.practice(skill.guideId, skill.title)!!
        assertTrue(set.questionIds.isNotEmpty())
        assertTrue("a set must be smaller than a chore", set.size <= 5)
        assertTrue("a pass mark of everything makes one slip erase the attempt", set.passMark < set.size)
        // Every id must be askable, or the session strands on its first question.
        set.questionIds.forEach { assertNotNull("$it cannot be asked", s.askFor(it)) }
    }

    /**
     * A unit test mixes several skills; a challenge spans the course weakest-first.
     *
     * ⚠️ The interleaving is the point, not decoration: ten questions from one guide in a row let
     * context carry you, which is exactly what a test is meant to remove.
     */
    @Test
    fun aUnitTestMixesSkillsAndAChallengeSpansTheCourse() = runBlocking {
        val s = store()
        s.enroll("first aid and emergencies")
        val course = s.course()!!
        val unit = course.units().first { it.first != "Other" }
        // Teach at least two skills in the unit so there is something to interleave.
        unit.second.take(3).forEach { s.teach(it.guideId) }

        val test = s.unitTest(unit.first)!!
        assertTrue(test.questionIds.size >= 3)
        assertEquals(test.questionIds.size, test.questionIds.distinct().size)
        val guides = test.questionIds.mapNotNull { id -> s.items.value.firstOrNull { it.card.id == id } }
            .map { it.question.guideId }
        if (unit.second.take(3).size > 1) {
            assertTrue("a mixed set drew from a single guide", guides.distinct().size > 1)
        }

        val challenge = s.challenge()!!
        assertTrue(challenge.questionIds.isNotEmpty())
        challenge.questionIds.forEach { assertNotNull(s.askFor(it)) }
    }

    /** Nothing to draw from means no set at all — never an empty one that strands on question one. */
    @Test
    fun aSetIsRefusedRatherThanReturnedEmpty() = runBlocking {
        val s = store()
        assertNull("no course, no unit test", s.unitTest("First Aid"))
        assertNull("no course, no challenge", s.challenge())
        s.enroll("first aid and emergencies")
        assertNull("a unit nobody is enrolled in", s.unitTest("Nonexistent Category"))
        assertNull("nothing taught yet, so nothing to challenge", s.challenge())
    }

    /**
     * A hinted right answer is still right, but never earns the top grade.
     *
     * ⚠️ This is what stops help from quietly inflating the record: the accuracy figure keeps meaning
     * "did you get there", while the *schedule* brings a hinted card back soon instead of pushing it
     * out as though it were known.
     *
     * ⚠️ **Three answers, not one, and the reason is the whole test.** [Recall.review] uses fixed gaps
     * for the first two successful reviews — `FIRST_DAYS` then `SECOND_DAYS` — so a once-answered card
     * lands on the same interval whatever it was graded, and a single-answer version of this test
     * passes no matter what [Hints.gradeFor] does. The divergence is in the ease, which compounds:
     *
     *   cold, answered fast (EASY×3): 1.0 → 3.0 → 3.0 × 2.8 × 1.3 = 10.92, ease 2.8
     *   hinted (capped to HARD×3):    1.0 → 3.0 → 3.0 × 2.05 × 0.6 = 3.69, ease 2.05
     */
    @Test
    fun aHintedAnswerIsStillCorrectButComesBackSooner() = runBlocking {
        val day = 86_400_000L
        val start = 1_700_000_000_000L

        suspend fun threeAnswers(hintsTaken: Int): Double {
            val s = store()
            s.enroll("first aid and emergencies")
            val guide = s.syllabus()!!.steps.first().guideId
            s.teach(guide, nowMs = start)
            val id = s.due(nowMs = start, limit = 1).first().question.id
            var last = 0.0
            var at = start
            repeat(3) { round ->
                // Fast enough that Recall.gradeFor says EASY, so the cap is the only thing that can
                // pull it down to HARD.
                last = s.answer(id, correct = true, elapsedMs = 1_000L, nowMs = at, hintsTaken = hintsTaken)!!
                    .intervalDays
                at += (last * day).toLong() + day * (round + 1)
            }
            return last
        }

        val cold = threeAnswers(hintsTaken = 0)
        val hinted = threeAnswers(hintsTaken = 2)

        assertEquals("cold: 3.0 × 2.8 × 1.3", 10.92, cold, 0.01)
        assertEquals("hinted: 3.0 × 2.05 × 0.6", 3.69, hinted, 0.01)
        assertTrue(
            "help must not schedule a card as though it were known cold",
            hinted < cold,
        )

        // And the record still says every one of them was got right — correct stays correct.
        val s = store()
        s.enroll("first aid and emergencies")
        val guide = s.syllabus()!!.steps.first().guideId
        s.teach(guide)
        s.answer(s.due(limit = 1).first().question.id, correct = true, elapsedMs = 1_000L, hintsTaken = 3)
        assertEquals(1, s.progress().correct)
        assertEquals(0, s.progress().incorrect)
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
