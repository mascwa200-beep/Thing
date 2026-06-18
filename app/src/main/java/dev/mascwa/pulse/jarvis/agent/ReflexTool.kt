package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.Cerebellum
import dev.mascwa.pulse.core.telemetry.Skill
import dev.mascwa.pulse.data.cerebellum.CerebellumStore
import kotlin.math.roundToInt

/**
 * J.A.R.V.I.S.'s window into its own virtual cerebellum — its procedural memory of which actions and
 * routines reliably work. Consultative by design: it reports skills, reflexes and forward-model
 * predictions; it never acts on its own, so the deliberate loop stays in control.
 *
 * Usage:
 *  - `reflex` — a readout of the most-practiced motor skills + automatic reflexes.
 *  - `reflex after <tool>` — what action usually follows <tool> (learned sequence coordination).
 *  - `reflex predict <tool>` — the forward-model reliability estimate for using <tool>.
 */
class ReflexTool(
    private val store: CerebellumStore,
) : JarvisTool {
    override val name = "reflex"
    override val usage =
        "reflex [after <tool> | predict <tool>] — consult your procedural memory (practiced skills, reflexes, reliability)"

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        val state = store.snapshot()
        when {
            a.startsWith("after ", ignoreCase = true) -> {
                val prev = a.removePrefix("after ").removePrefix("After ").trim()
                val skills = Cerebellum.skillsFor(state, "after:$prev")
                if (skills.isEmpty()) "No sequence learned yet after \"$prev\"."
                else "After \"$prev\" you usually:\n" + skills.take(5).joinToString("\n") { line(it) }
            }
            a.startsWith("predict ", ignoreCase = true) -> {
                val action = a.removePrefix("predict ").removePrefix("Predict ").trim()
                val p = Cerebellum.predict(state, "tool", action)
                buildString {
                    append("Forward model for \"$action\": ${pct(p.successProbability)} likely to succeed")
                    append(if (p.practiced) " (practiced)." else " (little practice — estimate is provisional).")
                    if (p.deviation) append(" Note: your reflex here is \"${p.reflexAction}\".")
                }
            }
            else -> {
                if (state.skills.isEmpty()) return@runCatching "No skills learned yet — I act deliberately for now."
                val top = Cerebellum.topSkills(state, 8)
                val reflexes = state.skills.filter { Cerebellum.isAutomatic(it) }
                    .sortedByDescending { it.reliability }
                buildString {
                    append("Most-practiced motor skills:\n")
                    append(top.joinToString("\n") { line(it) })
                    if (reflexes.isNotEmpty()) {
                        append("\n\nAutomatic reflexes:\n")
                        append(reflexes.take(6).joinToString("\n") { "• ${describe(it.cue)} → ${it.action} (${pct(it.reliability)})" })
                    }
                }
            }
        }
    }.getOrElse { "Cerebellum read failed: ${it.message}" }

    private fun line(s: Skill): String =
        "• ${describe(s.cue)} → ${s.action}: ${pct(s.reliability)} over ${s.attempts} rep${if (s.attempts == 1) "" else "s"}"

    private fun describe(cue: String): String = when {
        cue == "tool" -> "tool"
        cue.startsWith("after:") -> "after ${cue.removePrefix("after:")}"
        cue.startsWith("req:") -> "request \"${cue.removePrefix("req:")}\""
        else -> cue
    }

    private fun pct(v: Double): String = "${(v * 100).roundToInt()}%"
}
