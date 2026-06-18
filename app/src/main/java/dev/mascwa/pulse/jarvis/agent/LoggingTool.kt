package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.cerebellum.CerebellumStore
import dev.mascwa.pulse.data.usage.UsageRepository

/**
 * Transparent decorator around every tool. Two side-effects, then it delegates:
 *  1. records the invocation to the on-device activity log, and
 *  2. feeds the outcome to the virtual cerebellum as a *motor action* (so it learns each tool's
 *     reliability and how tool-use chains together).
 *
 * Wrapping the tool list (rather than editing the orchestrator) keeps the loop untouched and means
 * EVERY tool — base, self-edit and self-coding — is covered in one place. When [verbose] (detailed
 * logging on), the tool argument (which can carry user content) is logged too; either way
 * [UsageRepository.log] scrubs raw credentials. The cerebellum only ever sees the tool *name*.
 */
class LoggingTool(
    private val delegate: JarvisTool,
    private val repo: UsageRepository,
    private val cerebellum: CerebellumStore,
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
        cerebellum.observeAction(delegate.name, ok)
        return result
    }
}
