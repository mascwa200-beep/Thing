package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfcode.SelfCoder

/**
 * Lets J.A.R.V.I.S. propose a change to its OWN source as a GitHub PR. Only offered to the model when
 * self-coding is enabled in Settings. The change is STAGED for the user to approve (it never opens a PR
 * on its own); CI then compiles it and merge (auto-on-green or manual) + the install stay user-controlled.
 *
 * Arg: just the goal in plain language (J.A.R.V.I.S. works out which file) — or `<file path> | <goal>` to
 * target a specific file.
 */
class ProposeCodeChangeTool(private val selfCoder: SelfCoder) : JarvisTool {
    override val name = "selfcode"
    override val usage =
        "selfcode <goal>  (or  selfcode <file path> | <goal>) — propose a change to (or a brand-new file " +
            "in) your own app code; you pick or name the file and the user approves it before any PR opens"

    override suspend fun run(arg: String): String {
        val a = arg.trim()
        if (a.isBlank()) return "Tell me what to change, sir."
        val (pathHint, goal) = if ("|" in a) {
            val parts = a.split("|", limit = 2).map { it.trim() }
            parts.getOrElse(0) { "" }.ifBlank { null } to parts.getOrElse(1) { "" }
        } else {
            null to a
        }
        if (goal.isBlank()) return "Tell me what to change, sir."
        return runCatching { selfCoder.stage(goal, pathHint).message }.getOrElse { "Self-code failed: ${it.message}" }
    }
}
