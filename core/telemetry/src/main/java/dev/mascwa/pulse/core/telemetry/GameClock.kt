package dev.mascwa.pulse.core.telemetry

/** Wasteland time-of-day — Fallout-flavoured phases the life-sim reacts to (encounters, flavour, story beats). */
enum class DayPhase(val label: String) {
    DAWN("Dawn"),
    DAY("Daylight"),
    DUSK("Dusk"),
    NIGHT("Night"),
}

/**
 * Pure day/calendar reasoning for the life-sim — "which wasteland day is it, and what part of the day."
 * The Sims-but-you're-the-Sim clock: play spans real days, and the game tracks them so story beats,
 * daily objectives and flavour can advance with your actual life. All wall-clock inputs are injected
 * (start-of-play + now) so it stays deterministic and CI-testable; the on-device layer supplies the times.
 */
object GameClock {
    const val DAY_MS = 86_400_000L

    /**
     * Which day of survival it is — Day 1 is the day play began. [startMs] = when the operative was created,
     * [nowMs] = now. Defensive: a missing/future-dated start or a clock that went backwards reads as Day 1.
     */
    fun dayNumber(startMs: Long, nowMs: Long): Int {
        if (startMs <= 0L || nowMs < startMs) return 1
        return (((nowMs - startMs) / DAY_MS) + 1).toInt()
    }

    /** Whole days survived since play began (0 on Day 1). */
    fun daysSurvived(startMs: Long, nowMs: Long): Int = (dayNumber(startMs, nowMs) - 1).coerceAtLeast(0)

    /** The phase of day for an hour-of-day (0..23; wraps defensively). */
    fun phase(hourOfDay: Int): DayPhase = when (((hourOfDay % 24) + 24) % 24) {
        in 5..7 -> DayPhase.DAWN
        in 8..17 -> DayPhase.DAY
        in 18..20 -> DayPhase.DUSK
        else -> DayPhase.NIGHT
    }

    /** A short HUD banner: "DAY 3 · DUSK". */
    fun banner(startMs: Long, nowMs: Long, hourOfDay: Int): String =
        "DAY ${dayNumber(startMs, nowMs)} · ${phase(hourOfDay).label.uppercase()}"

    /**
     * True when [nowMs] falls on a later day of play than [lastMs] — i.e. a fresh wasteland day has turned
     * since we last checked (drives "a new day dawns" story/daily rollover). [lastMs] == 0 (never checked) is
     * not a rollover.
     */
    fun isNewDay(lastMs: Long, nowMs: Long, startMs: Long): Boolean =
        lastMs > 0L && dayNumber(startMs, nowMs) > dayNumber(startMs, lastMs)
}
