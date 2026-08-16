package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.data.oracle.OracleEngine
import dev.mascwa.pulse.data.oracle.OracleLearningStore
import dev.mascwa.pulse.di.AppContainer

/**
 * Lets the computer consult its own predictive cortex.
 *
 * The Oracle fuses time, location, movement, calendar, tasks, interests, weather, markets,
 * emergencies, space weather, device state, usage rhythm and the ambient read into a ranked list of
 * what to do now — and until this existed the assistant, which has a tool for nearly everything
 * else, could not ask it a single question. "What should I be doing?" had to be answered by
 * reasoning from scratch over tools that each see one domain, when a purpose-built cross-domain
 * answer was already computed and sitting one call away.
 *
 * Read-only. The Oracle proposes; acting is still the user's, through the routes each insight names.
 */
class OracleTool(
    private val container: AppContainer,
    private val learning: OracleLearningStore,
) : JarvisTool {

    override val name = "oracle"

    override val usage =
        "oracle [learned] — the ranked list of what matters right now, fused across every signal the " +
            "device has (why each fired, and where to act on it); pass 'learned' for which advisories " +
            "the user actually acts on"

    override suspend fun run(arg: String): String = runCatching {
        if (arg.trim().equals("learned", ignoreCase = true)) {
            return@runCatching learning.summary()
        }
        val settings = container.settingsRepository.current()
        val insights = OracleEngine.read(container, settings)
        if (insights.isEmpty()) return@runCatching "All quiet — nothing needs the user right now."
        buildString {
            append(Oracle.briefing(insights))
            append("\n")
            insights.take(MAX_LISTED).forEach { i ->
                append("\n- [")
                append(i.urgency.name)
                append("] ")
                append(i.title)
                if (i.detail.isNotBlank()) append(" — ").append(i.detail)
                // The sources are the point of the thing: an advisory whose reasoning is opaque is
                // just an instruction, and the assistant should be able to say what combined to
                // produce it rather than asserting it.
                if (i.sources.isNotEmpty()) append(" (from ").append(i.sources.joinToString(", ")).append(")")
                i.actionRoute?.takeIf { it.isNotBlank() }?.let { append(" [open: ").append(it).append("]") }
            }
        }
    }.getOrElse { "Couldn't read the Oracle right now." }

    private companion object {
        /** Enough to reason over, short enough that one tool result does not crowd out the turn. */
        const val MAX_LISTED = 6
    }
}
