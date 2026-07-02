package dev.mascwa.pulse.core.telemetry

/**
 * Real calendar → wasteland agenda. Your actual upcoming appointments become time-boxed objectives the
 * game briefs you on, so the wasteland runs on your real schedule. Pure + deterministic → CI-testable; the
 * on-device layer reads the events (permission-gated) and this maps them to game framing. Event text stays
 * on-device — nothing here transmits or persists it.
 */

/** A minimal real calendar event — the data layer maps CalendarContract → this. */
data class CalEvent(
    val id: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean = false,
)

/** A wasteland-framed objective derived from a real upcoming calendar event. */
data class AgendaQuest(
    val id: Long,
    /** The real event title (shown to the operator; on-device only). */
    val title: String,
    /** A game-flavoured one-line briefing. */
    val briefing: String,
    val startMs: Long,
    /** Relative countdown to the start, e.g. "in 2h 15m" / "now" / "under way". */
    val countdown: String,
    /** Starts within the hour (or is running) → the UI highlights it. */
    val imminent: Boolean,
)

object CalendarQuests {
    /** How far ahead to pull events into the agenda. */
    const val HORIZON_MS = 48L * 3_600_000L
    /** Within this window (or already running) an objective is "imminent". */
    const val IMMINENT_MS = 60L * 60_000L
    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /**
     * Upcoming events → agenda quests: those still running or starting within [HORIZON_MS], soonest first,
     * capped at [max]. Deterministic given (events, nowMs). All-day events are kept (they're valid objectives).
     */
    fun compose(events: List<CalEvent>, nowMs: Long, max: Int = 3): List<AgendaQuest> =
        events
            .filter { it.endMs > nowMs && it.startMs <= nowMs + HORIZON_MS }
            .sortedBy { it.startMs }
            .take(max.coerceAtLeast(0))
            .map { e ->
                val delta = e.startMs - nowMs
                AgendaQuest(
                    id = e.id,
                    title = e.title.ifBlank { "Untitled" },
                    briefing = briefingFor(e.title, delta),
                    startMs = e.startMs,
                    countdown = describeCountdown(delta),
                    imminent = delta <= IMMINENT_MS, // already running (≤0) or within the hour
                )
            }

    /** A relative countdown to a start [deltaMs] from now. */
    fun describeCountdown(deltaMs: Long): String {
        if (deltaMs <= 0) return "under way"
        val mins = deltaMs / MIN_MS
        if (mins < 1) return "now"
        if (mins < 60) return "in ${mins}m"
        val h = deltaMs / HOUR_MS
        val m = (deltaMs % HOUR_MS) / MIN_MS
        if (h < 24) return if (m == 0L) "in ${h}h" else "in ${h}h ${m}m"
        val d = deltaMs / DAY_MS
        val rh = (deltaMs % DAY_MS) / HOUR_MS
        return if (rh == 0L) "in ${d}d" else "in ${d}d ${rh}h"
    }

    private fun briefingFor(title: String, deltaMs: Long): String {
        val t = title.ifBlank { "an appointment" }
        return when {
            deltaMs <= 0 -> "Objective under way: \"$t\". Don't let it slip."
            deltaMs <= IMMINENT_MS -> "Imminent: make \"$t\" — the clock's against you."
            else -> "On the docket: \"$t\". Plan your run."
        }
    }
}
