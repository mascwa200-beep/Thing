package dev.mascwa.pulse.core.telemetry

/**
 * The habit check-in brain — the "it'll ask if I've done it, and know if I'm lying" logic, as pure and
 * CI-testable rules. A [Habit] recurs on a cadence; [due] decides which habits are overdue and worth asking
 * about now; [resolve] takes the user's answer and cross-references [ActivitySensing] evidence to reach a
 * [CheckinOutcome] — the moment a claimed "yeah I showered" gets caught when the sensors never heard the
 * water. No Android types; the on-device layer schedules the prompts, feeds the evidence log, and applies
 * the need top-up on a truthful confirmation.
 */

/** A recurring self-care habit the app checks in on. [everyMs] is how often it's expected. */
data class Habit(
    val activity: RealActivity,
    val label: String,
    val question: String,
    val everyMs: Long,
)

/** Per-habit tracking: when it was last genuinely CONFIRMED (evidence or a truthful yes) and last asked. */
data class HabitState(val lastConfirmedMs: Long = 0L, val lastAskedMs: Long = 0L)

/** The verdict once the user answers a check-in, cross-referenced with what the sensors actually saw. */
enum class CheckinOutcome {
    /** Claimed done AND sensors corroborate → truthful; top up the matching need. */
    CONFIRMED,
    /** Claimed done but sensors saw nothing in the window → caught out; no top-up. */
    CAUGHT_LIE,
    /** Claimed done, evidence only weak/ambiguous → provisional trust-but-verify (a soft top-up). */
    UNVERIFIED,
    /** Answered honestly "not yet" → a nudge to go do it; no top-up. */
    HONEST_NO,
}

object HabitCheckin {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /** Don't re-ask a habit within this window even if still overdue — no nagging every minute. */
    const val MIN_ASK_GAP_MS = 2 * HOUR

    /** A sensible starting catalog; the on-device layer can let the owner tune cadences later. */
    val DEFAULTS: List<Habit> = listOf(
        Habit(RealActivity.SHOWER, "Shower", "Have you showered today?", DAY),
        Habit(RealActivity.TOOTHBRUSH, "Brush teeth", "Brushed your teeth?", 12 * HOUR),
        Habit(RealActivity.EATING, "Eat", "Had a proper meal recently?", 6 * HOUR),
        Habit(RealActivity.DRINKING, "Hydrate", "Drunk any water lately?", 3 * HOUR),
    )

    /**
     * Which habits are due to be checked in on at [now]: overdue past their cadence since the last
     * confirmation, and not asked within [MIN_ASK_GAP_MS]. Most-overdue first. [states] maps a habit's
     * [RealActivity] name → its [HabitState] (missing = never done).
     */
    fun due(
        habits: List<Habit>,
        states: Map<String, HabitState>,
        now: Long,
        minAskGapMs: Long = MIN_ASK_GAP_MS,
    ): List<Habit> = habits
        .filter { h ->
            val s = states[h.activity.name] ?: HabitState()
            (now - s.lastConfirmedMs) >= h.everyMs && (now - s.lastAskedMs) >= minAskGapMs
        }
        .sortedByDescending { h -> now - (states[h.activity.name]?.lastConfirmedMs ?: 0L) }

    /**
     * Resolve the user's answer to a [habit] check-in against the [evidence] log since [sinceMs].
     * [claimedDone] = the user says they did it. A truthful yes is CONFIRMED; a yes the sensors can't back up
     * at all is a CAUGHT_LIE; a yes with only weak evidence is UNVERIFIED; a no is HONEST_NO.
     */
    fun resolve(
        habit: Habit,
        claimedDone: Boolean,
        evidence: List<ActivityEvidence>,
        sinceMs: Long,
    ): CheckinOutcome {
        if (!claimedDone) return CheckinOutcome.HONEST_NO
        return when (ActivitySensing.verifyClaim(habit.activity, evidence, sinceMs)) {
            ClaimVerdict.CONFIRMED -> CheckinOutcome.CONFIRMED
            ClaimVerdict.WEAK -> CheckinOutcome.UNVERIFIED
            ClaimVerdict.NONE -> CheckinOutcome.CAUGHT_LIE
        }
    }

    /** Whether an [outcome] should top up the habit's need (a truthful or provisionally-trusted completion). */
    fun topsUpNeed(outcome: CheckinOutcome): Boolean =
        outcome == CheckinOutcome.CONFIRMED || outcome == CheckinOutcome.UNVERIFIED

    /** Whether this [outcome] is a completion worth stamping as the new lastConfirmed time. */
    fun confirms(outcome: CheckinOutcome): Boolean = topsUpNeed(outcome)
}
