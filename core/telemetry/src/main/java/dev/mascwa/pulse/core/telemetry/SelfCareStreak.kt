package dev.mascwa.pulse.core.telemetry

/**
 * Self-care STREAKS — the consistency layer over the habit check-ins. Confirming a real-life habit (showered,
 * brushed, ate, hydrated) on consecutive wasteland days builds a streak; a missed day breaks it. Streaks feed
 * a small, earned in-game buff, so keeping your real routine is rewarded — the life-sim's "bleed into reality"
 * carrot. Pure + deterministic (day numbers injected, no clock) so CI gates it.
 */

/** A per-habit streak: consecutive days kept ([current]), the best ever ([longest]), and the last day credited. */
data class Streak(
    val current: Int = 0,
    val longest: Int = 0,
    /** [GameClock] day number of the last confirmation, or -1 if never confirmed. */
    val lastDay: Int = -1,
)

object SelfCareStreak {
    /** A streak counts as "kept" (not stale) if its last confirmation was today or yesterday. */
    const val STALE_AFTER_DAYS = 1

    /** The current streak reaches these lengths for the escalating [wellKeptBonus]. */
    const val BONUS_TIER_1 = 3
    const val BONUS_TIER_2 = 7

    /**
     * Credit a confirmation of a habit on [day] (a [GameClock] day number ≥ 0). Same-day repeat = no change;
     * the very next day extends the streak; a gap of 2+ days resets it to 1. An out-of-order or negative day
     * is ignored. [Streak.longest] is monotonic (never decreases).
     */
    fun record(prev: Streak, day: Int): Streak {
        if (day < 0) return prev
        if (prev.lastDay == day) return prev            // already credited today
        if (day < prev.lastDay) return prev             // out-of-order — ignore
        val next = when {
            prev.lastDay < 0 -> 1                        // first ever
            day == prev.lastDay + 1 -> prev.current + 1  // consecutive day → extend
            else -> 1                                    // a gap of 2+ days → reset to 1
        }
        return Streak(current = next, longest = maxOf(prev.longest, next), lastDay = day)
    }

    /**
     * The streak as it reads [today] (a [GameClock] day number): the stored [Streak.current] if the last
     * confirmation is recent (today or yesterday), else 0 — a streak you let lapse is broken even before the
     * next confirmation overwrites it.
     */
    fun currentAsOf(s: Streak, today: Int): Int =
        if (s.lastDay in 0..today && today - s.lastDay <= STALE_AFTER_DAYS) s.current else 0

    /**
     * The overall "well-kept" self-care buff from a set of live streaks (one per habit), as a small
     * non-negative integer: +1 once your best live streak reaches [BONUS_TIER_1] days, +2 at [BONUS_TIER_2].
     * Stale streaks don't count (measured against [today]). Feeds the game as an earned bonus.
     */
    fun wellKeptBonus(streaks: Collection<Streak>, today: Int): Int {
        val best = streaks.maxOfOrNull { currentAsOf(it, today) } ?: 0
        return when {
            best >= BONUS_TIER_2 -> 2
            best >= BONUS_TIER_1 -> 1
            else -> 0
        }
    }

    /** A one-line readout of the best live streak, for the UI/tool ("3-day streak · best 9"). Empty if none. */
    fun describe(streaks: Collection<Streak>, today: Int): String {
        val live = streaks.map { currentAsOf(it, today) }.maxOrNull() ?: 0
        val best = streaks.maxOfOrNull { it.longest } ?: 0
        if (live <= 0 && best <= 0) return ""
        return if (live > 0) "$live-day self-care streak · best $best" else "streak lapsed · best $best"
    }
}
