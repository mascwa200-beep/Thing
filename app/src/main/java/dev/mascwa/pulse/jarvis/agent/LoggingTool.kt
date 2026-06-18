package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.usage.UsageRepository

/**
 * Transparent decorator that records each tool invocation to the on-device activity log, then
 * delegates. Wrapping the tool list (rather than editing the orchestrator) keeps the loop untouched
 * and means EVERY tool — base, self-edit and self-coding — is logged in one place. Logs the tool name
 * only, never the argument (which can carry user content); name + outcome are enough to see what the
 * assistant did.
 */
class LoggingTool(
    private val delegate: JarvisTool,
    private val repo: UsageRepository,
) : JarvisTool {
    override val name: String get() = delegate.name
    override val usage: String get() = delegate.usage

    override suspend fun run(arg: String): String {
        val result = delegate.run(arg)
        val ok = !result.startsWith("Tool error") &&
            !result.contains("failed", ignoreCase = true)
        repo.log("tool", "${delegate.name}: ${if (ok) "ok" else "error"}")
        return result
    }
}
