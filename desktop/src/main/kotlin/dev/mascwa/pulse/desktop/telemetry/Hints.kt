// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Hints.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

/**
 * Getting unstuck without being handed the answer.
 *
 * A question you cannot do has exactly two outcomes today: guess, or give up. Both teach nothing, and
 * both are recorded as a wrong answer that says more about the hint being missing than about the
 * learner. Khan Academy's answer is a hint ladder — nudge, then narrow, then show — where each rung
 * costs you a little credit and the last one is the worked answer itself.
 *
 * ⚠️ **The taking of a hint is recorded, and it must be.** A question answered on the third hint is not
 * the same evidence as one answered cold, and letting it count the same would quietly inflate every
 * accuracy figure in [StudyProgress] until they meant nothing. The honest treatment is the one used
 * here: a hinted correct answer still counts as correct — the learner did get there — but it never
 * earns the top grade, because a fact you needed help with is not one you know yet.
 *
 * Nothing here invents content. Every rung is derived from the question and its own material, for the
 * same reason [StudyQuestions] never writes prose: a model fluent enough to compose a plausible hint is
 * exactly what must not be teaching you facts.
 */
object Hints {

    /** One rung of the ladder. */
    data class Hint(val step: Int, val text: String, val isAnswer: Boolean = false)

    /**
     * The ladder for a multiple-choice item.
     *
     * Rung 1 narrows the field by half — a real reduction the learner can reason with, and the only one
     * that leaves any thinking to do. Rung 2 names where to look. Rung 3 is the answer with its source
     * sentence, because somebody still stuck after two hints needs to be taught, not tested further.
     *
     * @param eliminate how many wrong options rung 1 should strike out. Half, rounded down, so a
     *   two-option item is never reduced to a single choice — that is not a hint, it is the answer
     *   wearing a hint's clothes.
     */
    fun forQuiz(item: QuizBuilder.QuizItem): List<Hint> {
        val wrong = item.choices.withIndex().filter { !it.value.correct }
        val eliminate = wrong.size / 2
        val out = ArrayList<Hint>(3)
        if (eliminate > 0) {
            val struck = wrong.take(eliminate).joinToString(", ") { letter(it.index) }
            out += Hint(out.size + 1, "Rule out $struck. Now look again.")
        }
        if (item.heading.isNotBlank()) {
            out += Hint(out.size + 1, "This is from \"${item.guideTitle} — ${item.heading}\". Picture that section.")
        }
        val answer = item.choices.firstOrNull { it.correct }?.text
        if (answer != null) {
            out += Hint(
                out.size + 1,
                buildString {
                    append("The answer is ").append(answer).append('.')
                    if (item.explanation.isNotBlank()) append("\n\n").append(item.explanation)
                },
                isAnswer = true,
            )
        }
        return out
    }

    /**
     * What an answer is worth after [hintsTaken] rungs.
     *
     * ⚠️ Correct stays correct — the accuracy figure must keep meaning "did you get there", and
     * rewriting a right answer into a wrong one because help was used would make the record lie in the
     * other direction. What changes is the *grade*, which is what the schedule reads: any hint at all
     * caps it at [Recall.Grade.HARD], so a hinted card comes back soon instead of being pushed out as
     * though it were known.
     */
    fun gradeFor(correct: Boolean, elapsedMs: Long, hintsTaken: Int): Recall.Grade {
        val ungated = Recall.gradeFor(correct, elapsedMs)
        if (!correct || hintsTaken <= 0) return ungated
        return if (ungated == Recall.Grade.EASY || ungated == Recall.Grade.GOOD) Recall.Grade.HARD else ungated
    }

    /**
     * Whether an answer counts toward a practice set's pass mark.
     *
     * Reaching the last rung means being shown the answer; counting that as a pass would let somebody
     * clear a unit test by pressing "hint" three times per question. Earlier rungs still count — being
     * nudged and then getting it is learning working exactly as intended.
     */
    fun countsTowardPass(correct: Boolean, hints: List<Hint>, hintsTaken: Int): Boolean {
        if (!correct) return false
        val shown = hints.take(hintsTaken)
        return shown.none { it.isAnswer }
    }

    /** A, B, C… for an option index. */
    private fun letter(index: Int): String = ('A' + index).toString()
}
