// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Recall.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * When to ask you something again.
 *
 * Being taught once is not learning; the gap between being told and being asked again is what
 * decides whether anything sticks. This is the SM-2 shape — the interval multiplies by an ease factor
 * that itself moves with how well you did — with two departures that matter on a phone rather than in
 * a research paper:
 *
 * - **The interval is capped.** Uncapped SM-2 sends a well-known card years out, which on a device
 *   someone reinstalls every few months means it silently never returns. A ceiling keeps everything
 *   in circulation.
 * - **A lapse does not reset the ease to the floor.** Forgetting one thing once says less about the
 *   card than SM-2 assumes, and hammering the ease makes a single bad day poison a card for months.
 *
 * Pure and deterministic: `now` is passed in, never read. That is what lets CI check the schedule.
 */
object Recall {

    /** How well the answer went. Four options, because three is too coarse and five is a quiz. */
    enum class Grade {
        /** Did not know it. The card comes back within the day. */
        FORGOT,

        /** Got there, but it was work. Grows, slowly. */
        HARD,

        /** Knew it. The ordinary path. */
        GOOD,

        /** Instant. Push it further out than usual. */
        EASY,
    }

    /**
     * One thing being learned.
     *
     * @param id the question's stable id, so history survives the corpus being rebuilt.
     * @param intervalDays the current gap. 0 means never reviewed.
     * @param ease the multiplier applied on a good answer; clamped to [MIN_EASE]..[MAX_EASE].
     * @param reps consecutive successful reviews; reset by a lapse.
     * @param lapses lifetime forgettings — the signal for "this one is genuinely hard".
     */
    data class Card(
        val id: String,
        val dueAtMs: Long,
        val intervalDays: Double = 0.0,
        val ease: Double = START_EASE,
        val reps: Int = 0,
        val lapses: Int = 0,
    )

    /** A brand-new card, due immediately — you have just been taught it, so it is asked today. */
    fun newCard(id: String, nowMs: Long): Card = Card(id = id, dueAtMs = nowMs)

    /**
     * Schedule [card] after answering [grade] at [nowMs].
     *
     * The first two successful reviews use fixed short gaps rather than the ease multiplier: a card
     * reviewed once has no evidence behind its ease yet, and letting it jump straight to days is how
     * spaced repetition loses things early.
     */
    fun review(card: Card, grade: Grade, nowMs: Long): Card {
        if (grade == Grade.FORGOT) {
            return card.copy(
                dueAtMs = nowMs + (LAPSE_DAYS * DAY_MS).roundToLong(),
                intervalDays = LAPSE_DAYS,
                // Softened, not floored: one bad day should not poison the card for months.
                ease = clampEase(card.ease - EASE_LAPSE),
                reps = 0,
                lapses = card.lapses + 1,
            )
        }

        val ease = clampEase(
            when (grade) {
                Grade.HARD -> card.ease - EASE_HARD
                Grade.GOOD -> card.ease
                Grade.EASY -> card.ease + EASE_EASY
                Grade.FORGOT -> card.ease // unreachable; the branch above returned
            },
        )
        val reps = card.reps + 1
        val next = when {
            reps == 1 -> FIRST_DAYS
            reps == 2 -> SECOND_DAYS
            else -> card.intervalDays * ease * gradeFactor(grade)
        }
        val capped = min(max(next, FIRST_DAYS), MAX_INTERVAL_DAYS)
        return card.copy(
            dueAtMs = nowMs + (capped * DAY_MS).roundToLong(),
            intervalDays = capped,
            ease = ease,
            reps = reps,
        )
    }

    /**
     * The grade an objectively-marked answer earns.
     *
     * Self-grading asks how it *felt*; a multiple-choice answer is right or wrong, and how long it took
     * is the only remaining signal for how comfortably. Instant and right earns the long gap; right but
     * laboured is [HARD], because a fact you had to reconstruct is not one you know yet.
     *
     * @param elapsedMs 0 when unknown — the schedule then takes the answer at face value rather than
     *   inferring confidence from a measurement it does not have.
     */
    fun gradeFor(correct: Boolean, elapsedMs: Long): Grade = when {
        !correct -> Grade.FORGOT
        elapsedMs <= 0L -> Grade.GOOD
        elapsedMs < QUICK_MS -> Grade.EASY
        elapsedMs > LABOURED_MS -> Grade.HARD
        else -> Grade.GOOD
    }

    /** Cards ready to be asked, most overdue first, capped at [limit]. */
    fun due(cards: List<Card>, nowMs: Long, limit: Int = DEFAULT_DUE_LIMIT): List<Card> =
        cards.filter { it.dueAtMs <= nowMs }.sortedBy { it.dueAtMs }.take(limit)

    /** How many are waiting, for an honest count on a screen without materialising the list. */
    fun dueCount(cards: List<Card>, nowMs: Long): Int = cards.count { it.dueAtMs <= nowMs }

    /**
     * A card that has been answered correctly enough times, at long enough gaps, to call learned.
     *
     * Not "finished" — it still comes back, just rarely. Nothing is ever retired, because a fact you
     * stop being asked is a fact you will eventually lose.
     */
    fun isLearned(card: Card): Boolean = card.reps >= LEARNED_REPS && card.intervalDays >= LEARNED_DAYS

    /** Plain-English gap, for showing what answering will do before the reader commits. */
    fun describeInterval(days: Double): String = when {
        days < 1.0 -> "later today"
        days < 1.5 -> "tomorrow"
        days < 30.0 -> "in ${days.roundToLong()} days"
        days < 365.0 -> "in ${(days / 30.0).roundToLong()} months"
        else -> "in a year"
    }

    private fun clampEase(e: Double): Double = min(max(e, MIN_EASE), MAX_EASE)

    /** HARD moves slower than GOOD even at the same ease; EASY jumps. */
    private fun gradeFactor(grade: Grade): Double = when (grade) {
        Grade.HARD -> HARD_FACTOR
        Grade.EASY -> EASY_FACTOR
        else -> 1.0
    }

    const val DAY_MS = 86_400_000.0

    const val START_EASE = 2.5
    const val MIN_EASE = 1.3
    const val MAX_EASE = 2.8
    const val EASE_HARD = 0.15
    const val EASE_EASY = 0.1
    const val EASE_LAPSE = 0.2

    /** A forgotten card comes back the same day — that is the point of noticing you forgot. */
    const val LAPSE_DAYS = 0.25
    const val FIRST_DAYS = 1.0
    const val SECOND_DAYS = 3.0

    const val HARD_FACTOR = 0.6
    const val EASY_FACTOR = 1.3

    /** Nothing waits longer than this. See the class note on reinstalls. */
    const val MAX_INTERVAL_DAYS = 180.0

    const val LEARNED_REPS = 4
    const val LEARNED_DAYS = 21.0

    /** One sitting's worth. A queue longer than this is a chore, and chores get abandoned. */
    const val DEFAULT_DUE_LIMIT = 10

    /** Answered this fast on a multiple choice, you knew it rather than worked it out. */
    const val QUICK_MS = 8_000L

    /** Long enough to have reconstructed the answer rather than recalled it. */
    const val LABOURED_MS = 30_000L
}
