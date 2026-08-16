package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.DayAhead
import dev.mascwa.pulse.data.oracle.DayAheadEngine
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.di.AppContainer

/**
 * The rest of the day, as already projected.
 *
 * The planner composes the calendar, where each commitment is, how long the journey between them
 * takes and what the weather will be doing when you set off — and it was reachable only from the
 * ADVISORIES screen and the notification board. Asked "what does my day look like?", the console had
 * nothing but the raw calendar, which is the one part of this the user can already see for themselves.
 *
 * Read-only; it plans, it never edits a calendar.
 */
class DayTool(
    private val container: AppContainer,
    private val settings: SettingsRepository,
) : JarvisTool {
    override val name = "day"
    override val usage =
        "day — the rest of the day: when to leave for each commitment, any two that are too close " +
            "together to make, and the clear stretches in between"

    override suspend fun run(arg: String): String {
        val s = runCatching { settings.current() }.getOrNull()
            ?: return "I couldn't read the settings needed to plan the day."
        val beats = runCatching { DayAheadEngine.plan(container, s) }.getOrDefault(emptyList())
        if (beats.isEmpty()) {
            return "Nothing in the calendar for the rest of today. " +
                "If that looks wrong, the calendar permission may not be granted."
        }
        return buildString {
            append("The rest of your day:\n")
            beats.forEach { b ->
                append("\n").append(DayAheadEngine.clock(b.atMs)).append("  ").append(mark(b.kind))
                append(" ").append(b.title)
                if (b.detail.isNotBlank()) append("\n        ").append(b.detail)
                // A departure resting on a straight-line guess is a weaker claim than one from a road
                // route, and flattening the two is how a wrong departure time gets stated as fact.
                if (b.confidence == DayAhead.Confidence.ROUGH) append("  (rough estimate)")
            }
        }
    }

    private fun mark(k: DayAhead.BeatKind): String = when (k) {
        DayAhead.BeatKind.DEPART -> "LEAVE  "
        DayAhead.BeatKind.CONFLICT -> "CLASH  "
        DayAhead.BeatKind.EVENT -> "·      "
        DayAhead.BeatKind.FOCUS -> "FREE   "
        DayAhead.BeatKind.DAY_END -> "END    "
    }
}
