package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfcode.SelfCoder
import dev.mascwa.pulse.data.selfedit.PendingAction

/**
 * Lets J.A.R.V.I.S. propose a change to its OWN source as a GitHub PR. Only offered to the model when
 * self-coding is enabled in Settings. The change is STAGED for the user to approve — UNLESS autonomous
 * self-coding is on ([autonomous]), in which case it opens the PR itself via [autoApply] (which routes
 * through `ApprovalGate.apply` → `SelfCoder.commit`, so protected paths are still refused). Either way CI
 * must compile it and the install stays user-controlled.
 *
 * Arg: just the goal in plain language (J.A.R.V.I.S. works out which file) — or `<file path> | <goal>` to
 * target a specific file.
 */
class ProposeCodeChangeTool(
    private val selfCoder: SelfCoder,
    private val autonomous: suspend () -> Boolean = { false },
    private val autoApply: suspend (PendingAction) -> String = { "" },
) : JarvisTool {
    override val name = "selfcode"
    override val usage =
        "selfcode <goal>  (or  selfcode <file path> | <goal>) — propose a change to (or a brand-new file " +
            "in) your own app code; you pick or name the file. Normally the user approves before any PR " +
            "opens; with autonomous self-coding on, you open it yourself (CI + install still gate it)"

    override suspend fun run(arg: String): String {
        val a = arg.trim()
        if (a.isBlank()) return "Tell me what to change."
        val (pathHint, goal) = if ("|" in a) {
            val parts = a.split("|", limit = 2).map { it.trim() }
            parts.getOrElse(0) { "" }.ifBlank { null } to parts.getOrElse(1) { "" }
        } else {
            null to a
        }
        if (goal.isBlank()) return "Tell me what to change."
        return runCatching {
            val result = selfCoder.stage(goal, pathHint)
            val action = result.action
            if (result.ok && action != null && autonomous()) {
                val shipped = runCatching { autoApply(action) }.getOrElse { "couldn't open the PR: ${it.message}" }
                "${result.message}\n\nAutonomous self-coding is ON — I went ahead: $shipped"
            } else {
                result.message
            }
        }.getOrElse { "Self-code failed: ${it.message}" }
    }
}
