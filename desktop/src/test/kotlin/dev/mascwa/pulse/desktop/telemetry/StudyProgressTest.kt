// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/StudyProgressTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyProgressTest {

    private val day = 86_400_000L
    private val minute = 60_000L

    /** Day 0 is the epoch; every fixture is expressed in whole days so the arithmetic is readable. */
    private val dayOf: (Long) -> Int = { (it / day).toInt() }

    private fun attempt(atDay: Int, correct: Boolean, guide: String = "water", n: Int = 0) =
        StudyProgress.Attempt(
            questionId = "$guide:$atDay:$n",
            guideId = guide,
            correct = correct,
            atMs = atDay * day + n * minute,
        )

    // ---- time ------------------------------------------------------------------------------------

    /**
     * The load-bearing rule of the whole file. A study screen left open overnight would otherwise
     * report eight hours of diligent work, and a figure that flatters is worse than no figure.
     */
    @Test
    fun aScreenLeftOpenIsCreditedForTheWorkDoneInItAndNotForTheHours() {
        val overnight = StudyProgress.Session(
            startedAtMs = 0,
            endedAtMs = 8 * 60 * minute,
            attempts = 4,
        )
        val allowance = StudyProgress.OPEN_ALLOWANCE_MS + 4 * StudyProgress.PER_ANSWER_MS
        assertEquals(allowance, StudyProgress.creditedMs(overnight))
        assertTrue(StudyProgress.creditedMs(overnight) < overnight.openMs)
    }

    /** A genuinely busy stretch is credited in full — the cap must not clip real work. */
    @Test
    fun aBusySessionIsCreditedInFull() {
        val real = StudyProgress.Session(startedAtMs = 0, endedAtMs = 12 * minute, attempts = 10)
        assertEquals(12 * minute, StudyProgress.creditedMs(real))
    }

    /** Reading is quiet by nature, so it is allowed to be quiet — up to a point. */
    @Test
    fun readingGetsItsOwnAllowance() {
        val read = StudyProgress.Session(0, 15 * minute, attempts = 0, kind = StudyProgress.SessionKind.READING)
        assertEquals(15 * minute, StudyProgress.creditedMs(read))

        val abandoned = StudyProgress.Session(0, 6 * 60 * minute, attempts = 0, kind = StudyProgress.SessionKind.READING)
        assertEquals(StudyProgress.READING_ALLOWANCE_MS, StudyProgress.creditedMs(abandoned))
    }

    /** A clock jump backwards must not credit negative time and quietly reduce the total. */
    @Test
    fun anEndBeforeItsStartCreditsNothing() {
        assertEquals(0L, StudyProgress.creditedMs(StudyProgress.Session(5 * minute, 0, attempts = 3)))
    }

    @Test
    fun durationsReadAsDurations() {
        assertEquals("under a minute", StudyProgress.describeStudied(20_000))
        assertEquals("45m", StudyProgress.describeStudied(45 * minute))
        assertEquals("2h 5m", StudyProgress.describeStudied(125 * minute))
    }

    // ---- days ------------------------------------------------------------------------------------

    /**
     * ⚠️ A streak anchored strictly on today reports zero from midnight until you next open the app —
     * punishing you for a day that has not happened yet. Yesterday counts as the anchor.
     */
    @Test
    fun aStreakSurvivesTheDayNotHavingHappenedYet() {
        assertEquals(3, StudyProgress.streak(setOf(8, 9, 10), todayIndex = 10))
        assertEquals(3, StudyProgress.streak(setOf(8, 9, 10), todayIndex = 11))
        // Two clear days without study is a broken streak, not a forgiving one.
        assertEquals(0, StudyProgress.streak(setOf(8, 9, 10), todayIndex = 12))
    }

    @Test
    fun aGapBreaksTheStreakAtTheGap() {
        assertEquals(2, StudyProgress.streak(setOf(1, 2, 3, 9, 10), todayIndex = 10))
        assertEquals(0, StudyProgress.streak(emptySet(), todayIndex = 10))
    }

    // ---- the whole picture -------------------------------------------------------------------------

    @Test
    fun theSnapshotCountsWhatWasAnsweredAndHowItWent() {
        val attempts = listOf(
            attempt(1, true, n = 0),
            attempt(1, false, n = 1),
            attempt(2, true, n = 0),
            attempt(3, true, n = 0),
        )
        val sessions = listOf(
            StudyProgress.Session(1 * day, 1 * day + 5 * minute, attempts = 2),
            StudyProgress.Session(2 * day, 2 * day + 3 * minute, attempts = 1),
            StudyProgress.Session(3 * day, 3 * day + 3 * minute, attempts = 1),
        )
        val s = StudyProgress.summarise(attempts, sessions, todayIndex = 3, dayOf = dayOf)

        assertEquals(4, s.answered)
        assertEquals(3, s.correct)
        assertEquals(1, s.incorrect)
        assertEquals(0.75, s.accuracy, 1e-9)
        assertEquals(3, s.activeDays)
        assertEquals(3, s.streakDays)
        assertEquals(11 * minute, s.studiedMs)
        // The last session ended three minutes after the last answer, and closing the screen is the
        // later evidence of having studied — so it is the session's end, not the attempt's instant.
        assertEquals(3 * day + 3 * minute, s.lastStudiedAtMs)
        assertTrue(s.describeRatio(), s.describeRatio().contains("75%"))
    }

    /** Reading counts as having studied that day, even with nothing answered. */
    @Test
    fun aDaySpentOnlyReadingStillCounts() {
        val sessions = listOf(
            StudyProgress.Session(5 * day, 5 * day + 10 * minute, attempts = 0, kind = StudyProgress.SessionKind.READING),
        )
        val s = StudyProgress.summarise(emptyList(), sessions, todayIndex = 5, dayOf = dayOf)
        assertEquals(1, s.activeDays)
        assertEquals(1, s.streakDays)
        assertEquals(0, s.answered)
        assertTrue(s.hasHistory)
    }

    @Test
    fun aBlankRecordSaysSoRatherThanReportingZeroPerCent() {
        val s = StudyProgress.summarise(emptyList(), emptyList(), todayIndex = 5, dayOf = dayOf)
        assertEquals(0, s.answered)
        assertEquals(0, s.streakDays)
        assertEquals(0L, s.lastStudiedAtMs)
        assertTrue(!s.hasHistory)
        assertEquals("nothing answered yet", s.describeRatio())
        assertNull(s.trend())
    }

    /** A two-answer sample swinging the verdict would make the line noise. */
    @Test
    fun theTrendStaysQuietUntilThereIsEnoughRecentEvidence() {
        val thin = StudyProgress.summarise(
            listOf(attempt(1, true), attempt(1, false, n = 1)),
            emptyList(),
            todayIndex = 1,
            dayOf = dayOf,
        )
        assertNull(thin.trend())

        // A long bad history, then a clean recent run: recent accuracy well above lifetime.
        val history = (0 until 30).map { attempt(1, correct = false, n = it) } +
            (0 until StudyProgress.RECENT_WINDOW).map { attempt(2, correct = true, n = it) }
        val improving = StudyProgress.summarise(history, emptyList(), todayIndex = 2, dayOf = dayOf)
        assertEquals("improving", improving.trend())
    }

    // ---- per guide ----------------------------------------------------------------------------------

    /**
     * Without an evidence bar a single wrong answer on something barely touched tops the list forever,
     * and the refresher built on this would chase noise instead of weakness.
     */
    @Test
    fun theWeakestListIgnoresGuidesWithTooLittleEvidence() {
        val attempts = listOf(
            attempt(1, false, guide = "barely"),
            attempt(1, true, guide = "shaky", n = 1),
            attempt(1, false, guide = "shaky", n = 2),
            attempt(1, false, guide = "shaky", n = 3),
            attempt(1, false, guide = "shaky", n = 4),
        ) + (0 until 6).map { attempt(1, correct = true, guide = "strong", n = 10 + it) }

        val weak = StudyProgress.weakest(attempts)
        assertEquals(listOf("shaky"), weak.map { it.guideId })
        assertEquals(0.25, weak.first().accuracy, 1e-9)
    }

    @Test
    fun aGuideAnsweredPerfectlyIsNotWeak() {
        val attempts = (0 until 8).map { attempt(1, correct = true, guide = "water", n = it) }
        assertTrue(StudyProgress.weakest(attempts).isEmpty())
    }

    /**
     * Accuracy alone would call a guide mastered the day it was taught; intervals alone would call it
     * mastered for four confident wrong answers. Mastery needs both.
     */
    @Test
    fun masteryNeedsBothTheAnswersAndTheSchedule() {
        val learned = List(3) {
            Recall.Card(id = "q$it", dueAtMs = 0, intervalDays = 40.0, reps = 5)
        }
        val fresh = List(3) { Recall.Card(id = "q$it", dueAtMs = 0) }
        val allRight = (0 until 10).map { attempt(1, correct = true, n = it) }
        val mostlyWrong = (0 until 10).map { attempt(1, correct = it < 3, n = it) }

        assertEquals(StudyProgress.Level.MASTERED, StudyProgress.mastery("water", allRight, learned).level)
        // Same perfect answers, but nothing has been left long enough to have been remembered.
        assertEquals(StudyProgress.Level.LEARNING, StudyProgress.mastery("water", allRight, fresh).level)
        // Same stretched schedule, but the answers say otherwise.
        assertEquals(StudyProgress.Level.SHAKY, StudyProgress.mastery("water", mostlyWrong, learned).level)
    }

    @Test
    fun aGuideNotYetAskedIsNotJudged() {
        assertEquals(StudyProgress.Level.UNSEEN, StudyProgress.mastery("water", emptyList(), emptyList()).level)
        val introduced = StudyProgress.mastery(
            "water",
            listOf(attempt(1, true)),
            listOf(Recall.Card("q", dueAtMs = 0)),
        )
        assertEquals(StudyProgress.Level.INTRODUCED, introduced.level)
        assertTrue(introduced.describe(), introduced.describe().startsWith("Just introduced"))
    }

    /** Only this guide's answers may count towards it. */
    @Test
    fun masteryLooksAtOneGuideOnly() {
        val mixed = (0 until 6).map { attempt(1, correct = true, guide = "other", n = it) } +
            (0 until 6).map { attempt(1, correct = false, guide = "water", n = 10 + it) }
        val m = StudyProgress.mastery("water", mixed, emptyList())
        assertEquals(6, m.answered)
        assertEquals(0, m.correct)
        assertEquals(StudyProgress.Level.SHAKY, m.level)
    }
}
