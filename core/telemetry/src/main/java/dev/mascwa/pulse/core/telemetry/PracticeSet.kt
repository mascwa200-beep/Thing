package dev.mascwa.pulse.core.telemetry

import kotlin.math.roundToInt

/**
 * Practising one skill until you have it, rather than answering whatever the queue hands you.
 *
 * The review queue is scheduling: it asks the right question on the right day, across everything you
 * have ever learned. That is the wrong shape for *learning something now* — for that you want a short,
 * bounded set on a single skill, with a visible finish line and a verdict at the end. Khan Academy's
 * whole inner loop is this, and the app had no equivalent: you could be asked about a guide, but never
 * sit down and work at one.
 *
 * Three kinds, matching what the material can support:
 * - [Kind.PRACTICE] — one skill, a handful of questions, a pass mark.
 * - [Kind.UNIT_TEST] — a mixed set across a whole category, drawn from skills already started.
 * - [Kind.CHALLENGE] — the same across an entire course, weighted toward what is weakest.
 *
 * Pure: the caller supplies the candidate questions and the randomness, so CI holds the selection,
 * the pass mark and the verdict.
 */
object PracticeSet {

    enum class Kind {
        /** One guide, worked until it holds. */
        PRACTICE,

        /** A category, mixed — Khan's unit test. */
        UNIT_TEST,

        /** A whole course, weighted toward the weak spots. */
        CHALLENGE,
    }

    /** A bounded set of questions with a finish line. */
    data class Session(
        val kind: Kind,
        val title: String,
        val questionIds: List<String>,
        /** How many must be right to pass. */
        val passMark: Int,
    ) {
        val size: Int get() = questionIds.size
        val isEmpty: Boolean get() = questionIds.isEmpty()

        fun describe(): String = when (kind) {
            Kind.PRACTICE -> "$size questions · $passMark to pass"
            Kind.UNIT_TEST -> "Unit test · $size questions · $passMark to pass"
            Kind.CHALLENGE -> "Course challenge · $size questions · $passMark to pass"
        }
    }

    /** How a finished set went. */
    data class Result(
        val kind: Kind,
        val correct: Int,
        val total: Int,
        val passMark: Int,
    ) {
        val passed: Boolean get() = correct >= passMark
        val percent: Int get() = if (total == 0) 0 else ((correct.toDouble() / total) * 100).roundToInt()

        /**
         * The verdict, said the way a tutor would.
         *
         * ⚠️ A miss is never phrased as a failure. The learner has just spent real effort and the only
         * useful next instruction is what to do about it — "you have not passed" is a grade, "two more
         * to go" is teaching.
         */
        fun verdict(): String = when {
            total == 0 -> "Nothing to do."
            correct == total -> "Every one. That skill is yours."
            passed -> "Passed — $correct of $total."
            correct == 0 -> "None yet. Read it through once and come straight back."
            else -> "$correct of $total. ${passMark - correct} more and it's a pass — go again."
        }
    }

    /**
     * A practice set on one skill.
     *
     * Deliberately capped small. A twenty-question drill on a single guide is a chore, and the point is
     * a finish line you can see from the start.
     */
    fun practice(
        guideTitle: String,
        questionIds: List<String>,
        size: Int = PRACTICE_SIZE,
    ): Session? {
        val picked = questionIds.distinct().take(size.coerceAtLeast(1))
        if (picked.size < MIN_SET) return null
        return Session(Kind.PRACTICE, guideTitle, picked, passMark(picked.size))
    }

    /**
     * A mixed set across several skills, interleaved so consecutive questions come from different
     * guides where possible.
     *
     * ⚠️ The interleaving is the point, not decoration. Answering ten questions from one guide in a row
     * lets context carry you; mixing them forces each answer to be retrieved on its own, which is the
     * whole reason a unit test tells you more than the practice that preceded it.
     *
     * @param byGuide candidate question ids grouped by guide id.
     */
    fun mixed(
        kind: Kind,
        title: String,
        byGuide: Map<String, List<String>>,
        size: Int = UNIT_SIZE,
    ): Session? {
        val lanes = byGuide.values.map { it.distinct().toMutableList() }.filter { it.isNotEmpty() }
        if (lanes.isEmpty()) return null
        val out = ArrayList<String>(size)
        // Round-robin across guides: one from each in turn until full or everything is used.
        var progressed = true
        while (out.size < size && progressed) {
            progressed = false
            for (lane in lanes) {
                if (out.size >= size) break
                if (lane.isNotEmpty()) {
                    out += lane.removeAt(0)
                    progressed = true
                }
            }
        }
        if (out.size < MIN_SET) return null
        return Session(kind, title, out, passMark(out.size))
    }

    /**
     * The pass mark for a set of [n] questions — a clear majority, never all of them.
     *
     * Demanding perfection on a short set makes one slip erase the whole attempt, which teaches
     * caution rather than the material.
     */
    fun passMark(n: Int): Int = when {
        n <= 0 -> 0
        else -> ((n * PASS_FRACTION).roundToInt()).coerceIn(1, n)
    }

    /**
     * Which skills a course challenge should draw from, weakest first.
     *
     * Only skills with something to ask — a challenge over material never taught would be a quiz on
     * pages the learner has not seen, which is a test of the app rather than of them.
     */
    fun challengeOrder(skills: List<CourseMastery.Skill>): List<CourseMastery.Skill> =
        skills.filter { it.cards > 0 }
            .sortedWith(compareBy({ it.level.ordinal }, { it.position }))

    /** One skill, worked at until it holds. */
    const val PRACTICE_SIZE = 5

    /** A unit test is longer than practice and still one sitting. */
    const val UNIT_SIZE = 10

    /** Below this there is no set worth calling a set. */
    const val MIN_SET = 3

    /** A clear majority. */
    const val PASS_FRACTION = 0.8
}
