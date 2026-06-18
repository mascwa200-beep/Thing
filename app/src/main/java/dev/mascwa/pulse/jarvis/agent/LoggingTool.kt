package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.usage.UsageRepository

/**
 * Transparent decorator that records each tool invocation to the on-device activity log, then
 * delegates. Wrapping the tool list (rather than editing the orchestrator) keeps the loop untouched
 * and means EVERY tool — base, self-edit and self-coding — is logged in one place.
 *
 * When [verbose] (detailed logging on), the tool argument (which can carry user content) is logged too;
 * otherwise just the name + outcome. Either way [UsageRepository.log] scrubs raw credentials.
 */
class LoggingTool(
    private val delegate: JarvisTool,
    private val repo: UsageRepository,
    private val verbose: Boolean = false,
) : JarvisTool {
    override val name: String get() = delegate.name
    override val usage: String get() = delegate.usage

    override suspend fun run(arg: String): String {
        val result = delegate.run(arg)
        val ok = !result.startsWith("Tool error") &&
            !result.contains("failed", ignoreCase = true)
        val outcome = if (ok) "ok" else "error"
        val label = if (verbose && arg.isNotBlank()) {
            "${delegate.name}(${arg.trim().take(100)}): $outcome"
        } else {
            "${delegate.name}: $outcome"
        }
        repo.log("tool", label)
        return result
    }
}
