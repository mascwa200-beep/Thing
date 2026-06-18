package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfcode.SelfCoder

/**
 * Lets J.A.R.V.I.S. propose a change to its OWN source as a GitHub PR. Only offered to the model when
 * self-coding is enabled in Settings. CI gates compilation; merge (auto-on-green or manual) and the
 * install are user-controlled. Arg format: `<file path> | <goal>`.
 */
class ProposeCodeChangeTool(private val selfCoder: SelfCoder) : JarvisTool {
    override val name = "selfcode"
    override val usage = "selfcode <file path> | <goal> — open a GitHub PR changing your own app code"

    override suspend fun run(arg: String): String {
        val parts = arg.split("|").map { it.trim() }
        val path = parts.getOrElse(0) { "" }
        val goal = parts.getOrElse(1) { "" }
        if (path.isBlank() || goal.isBlank()) return "Format: selfcode <file path> | <goal>"
        return runCatching { selfCoder.propose(goal, path).message }.getOrElse { "Self-code failed: ${it.message}" }
    }
}
