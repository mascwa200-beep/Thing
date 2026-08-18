// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/HintsTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HintsTest {

    private fun item(options: Int = 4) = QuizBuilder.QuizItem(
        questionId = "q1",
        prompt = "Hold a rolling boil for ______.",
        choices = (0 until options).map {
            QuizBuilder.Choice("${it + 1} minutes", correct = it == 1)
        },
        format = QuizBuilder.Format.STANDARD,
        guideId = "water",
        guideTitle = "Water Purification",
        heading = "Boiling",
        explanation = "Hold a rolling boil for 2 minutes.",
    )

    @Test
    fun theLadderNudgesThenNarrowsThenTeaches() {
        val h = Hints.forQuiz(item())
        assertEquals(3, h.size)
        assertTrue(h[0].text, h[0].text.startsWith("Rule out"))
        assertTrue(h[1].text, h[1].text.contains("Boiling"))
        assertTrue(h[2].isAnswer)
        assertTrue(h[2].text, h[2].text.contains("2 minutes"))
        // Only the last rung is the answer.
        assertEquals(1, h.count { it.isAnswer })
    }

    /**
     * ⚠️ A two-option item must not be reduced to one. That is not a hint, it is the answer wearing a
     * hint's clothes.
     */
    @Test
    fun aTwoOptionItemIsNeverNarrowedToASingleChoice() {
        val h = Hints.forQuiz(item(options = 2))
        assertTrue(h.none { it.text.startsWith("Rule out") })
        assertTrue(h.any { it.isAnswer })
    }

    /**
     * ⚠️ Correct stays correct — the accuracy figure must keep meaning "did you get there". What a hint
     * changes is the GRADE the schedule reads, so a hinted card comes back soon rather than being
     * pushed out as though it were known.
     */
    @Test
    fun aHintedRightAnswerIsStillRightButNeverEarnsTheTopGrade() {
        // Cold and instant: the long gap.
        assertEquals(Recall.Grade.EASY, Hints.gradeFor(correct = true, elapsedMs = 3_000, hintsTaken = 0))
        // Same speed, one hint: capped.
        assertEquals(Recall.Grade.HARD, Hints.gradeFor(correct = true, elapsedMs = 3_000, hintsTaken = 1))
        assertEquals(Recall.Grade.HARD, Hints.gradeFor(correct = true, elapsedMs = 15_000, hintsTaken = 2))
        // Wrong is wrong however many hints were taken — hints cannot rescue a miss.
        assertEquals(Recall.Grade.FORGOT, Hints.gradeFor(correct = false, elapsedMs = 3_000, hintsTaken = 3))
        // Already laboured: no change, it was not going to be EASY anyway.
        assertEquals(Recall.Grade.HARD, Hints.gradeFor(correct = true, elapsedMs = 60_000, hintsTaken = 1))
    }

    /**
     * Being shown the answer must not clear a unit test. Earlier rungs still count — being nudged and
     * then getting it is learning working exactly as intended.
     */
    @Test
    fun readingTheAnswerOffTheLastHintDoesNotCountTowardAPass() {
        val h = Hints.forQuiz(item())
        assertTrue(Hints.countsTowardPass(correct = true, hints = h, hintsTaken = 0))
        assertTrue(Hints.countsTowardPass(correct = true, hints = h, hintsTaken = 2))
        assertTrue(!Hints.countsTowardPass(correct = true, hints = h, hintsTaken = 3))
        assertTrue(!Hints.countsTowardPass(correct = false, hints = h, hintsTaken = 0))
    }
}
