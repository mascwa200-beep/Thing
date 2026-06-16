package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A small ReAct-style loop that lets the on-device model use [tools] and durable [memory] to answer.
 * It is deliberately bounded and robust to malformed output: the few-billion-parameter on-device
 * model is best-effort at tool use, so anything that isn't a recognizable `TOOL <name> <arg>` line is
 * treated as the final answer. Each step is another full on-device inference, so it is slow.
 */
class AgentOrchestrator(
    private val engine: LocalInferenceEngine,
    private val memory: JarvisMemory,
    private val tools: List<JarvisTool>,
) {
    enum class Kind { THINKING, TOOL, FINAL }
    data class Step(val kind: Kind, val text: String)

    /** Drives the loop, emitting progress [Step]s; the terminal step is [Kind.FINAL]. */
    fun run(query: String, persona: String): Flow<Step> = flow {
        val recalled = runCatching { memory.recall(query) }.getOrDefault(emptyList())
        val system = buildSystem(persona, recalled.map { it.noteText })
        val scratch = StringBuilder(query.trim())
        var step = 0
        while (step < MAX_STEPS) {
            step++
            emit(Step(Kind.THINKING, "reasoning…"))
            val out = collect(engine.generate(scratch.toString(), emptyList(), system)).trim()
            val call = parseToolCall(out)
            if (call == null) {
                emit(Step(Kind.FINAL, stripFinal(out)))
                return@flow
            }
            val (toolName, arg) = call
            emit(Step(Kind.TOOL, if (arg.isBlank()) toolName else "$toolName $arg"))
            val tool = tools.firstOrNull { it.name.equals(toolName, ignoreCase = true) }
            val obs = if (tool == null) {
                "Unknown tool '$toolName'. Available: ${tools.joinToString { it.name }}."
            } else {
                runCatching { tool.run(arg) }.getOrElse { "Tool error: ${it.message}" }
            }
            scratch.append("\nTOOL ").append(toolName).append(' ').append(arg)
                .append("\nOBSERVATION: ").append(obs.take(MAX_OBS)).append('\n')
        }
        // Iterations exhausted — force a final answer from what we have.
        scratch.append("\nProvide your FINAL answer now using what you have.")
        val out = collect(engine.generate(scratch.toString(), emptyList(), system)).trim()
        emit(Step(Kind.FINAL, stripFinal(out).ifBlank { "I couldn't complete that with the tools available." }))
    }

    private suspend fun collect(flow: Flow<String>): String {
        val sb = StringBuilder()
        flow.collect { sb.append(it) }
        return sb.toString()
    }

    /** First `TOOL <name> <arg>` line, or null (which the caller treats as a final answer). */
    private fun parseToolCall(out: String): Pair<String, String>? {
        val m = TOOL_RE.find(out) ?: return null
        val name = m.groupValues[1].trim()
        if (name.equals("final", ignoreCase = true)) return null
        return name to m.groupValues[2].trim()
    }

    private fun stripFinal(out: String): String =
        out.replace(Regex("(?im)^\\s*FINAL:\\s*"), "").trim()

    private fun buildSystem(persona: String, memoryNotes: List<String>): String = buildString {
        append(persona.trim()).append("\n\n")
        append("You can use tools to get real, current information. To use one, reply with a single line:\n")
        append("TOOL <name> <argument>\n")
        append("Tools:\n")
        tools.forEach { append("- ").append(it.usage).append('\n') }
        append("After each tool you'll see 'OBSERVATION: ...'. ")
        append("When you have enough, reply: FINAL: <answer>. ")
        append("Answer directly (no tool) when you already know. Keep it brief.")
        if (memoryNotes.isNotEmpty()) {
            append("\n\nRelevant memory:\n")
            memoryNotes.forEach { append("- ").append(it).append('\n') }
        }
    }

    private companion object {
        const val MAX_STEPS = 4
        const val MAX_OBS = 1500
        val TOOL_RE = Regex("(?im)^\\s*TOOL\\s+(\\w+)\\s*(.*)$")
    }
}
