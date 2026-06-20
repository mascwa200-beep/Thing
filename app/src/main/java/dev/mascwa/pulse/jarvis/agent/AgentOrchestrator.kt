package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.MemoryStream
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.KnowledgeStore
import dev.mascwa.pulse.jarvis.inference.AssistantTurn
import dev.mascwa.pulse.jarvis.inference.ChatTurn
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import dev.mascwa.pulse.jarvis.inference.ToolCallingEngine
import dev.mascwa.pulse.jarvis.inference.ToolMessage
import dev.mascwa.pulse.jarvis.inference.ToolSpec
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
    private val toolsProvider: suspend () -> List<JarvisTool>,
    private val knowledge: KnowledgeStore,
    private val memoryStream: MemoryStream,
) {
    enum class Kind { THINKING, TOOL, FINAL }
    data class Step(val kind: Kind, val text: String)

    /** Drives the loop, emitting progress [Step]s; the terminal step is [Kind.FINAL]. [history] is the
     *  recent conversation (prior turns) so the model keeps short-term/conversational context — the engine
     *  layers turn it into prior chat messages. */
    fun run(query: String, persona: String, history: List<ChatTurn> = emptyList()): Flow<Step> = flow {
        // Resolve the live tool set each run, so toggling self-edit / registering an authored tool
        // takes effect without restarting.
        val tools = runCatching { toolsProvider() }.getOrDefault(emptyList())
        val recalled = runCatching { memory.recall(query) }.getOrDefault(emptyList())
        val docs = runCatching { knowledge.search(query, limit = 3) }.getOrDefault(emptyList())
        
        // Cross-reference linked entries in real time during retrieval
        val linkedEntries = runCatching { memoryStream.getLinkedEntries(query) }.getOrDefault(emptyList())
        
        val memoryNotes = recalled.map { it.noteText }
        val knowledgeChunks = docs.map { "[${it.title}] ${it.text}" }
        val linkedNotes = linkedEntries.map { it.text }

        // Native function-calling path (cloud): the model emits structured tool calls — far more reliable
        // than the text protocol on-device models need. Falls through to text-ReAct when unavailable.
        val toolEngine = engine as? ToolCallingEngine
        if (toolEngine != null && tools.isNotEmpty() && runCatching { toolEngine.supportsTools() }.getOrDefault(false)) {
            // Dedup by name: the OpenAI tools API rejects duplicate function names (an authored tool
            // could collide with a built-in), whereas the text path just picks the first.
            val specs = tools.distinctBy { it.name.lowercase() }.map { ToolSpec(it.name, it.usage) }
            val messages = ArrayList<ToolMessage>()
            messages.add(ToolMessage("system", buildNativeSystem(persona, memoryNotes, knowledgeChunks, linkedNotes)))
            history.forEach { messages.add(ToolMessage(if (it.role.equals("user", true)) "user" else "assistant", it.text)) }
            messages.add(ToolMessage("user", query.trim()))
            var nStep = 0
            var nativeBroke = false
            while (nStep < MAX_STEPS) {
                nStep++
                emit(Step(Kind.THINKING, "reasoning…"))
                val turn = runCatching { toolEngine.chatWithTools(messages, specs) }
                    .getOrElse { AssistantTurn("// cloud error: ${it.message}") }
                if (turn.toolCalls.isEmpty()) {
                    // If the very first tool-call attempt hard-errors (e.g. the chosen model doesn't
                    // support function-calling), fall back to the text-ReAct path instead of surfacing
                    // a cryptic error — the user still gets an answer.
                    if (nStep == 1 && turn.text.trimStart().startsWith("// cloud error")) { nativeBroke = true; break }
                    emit(Step(Kind.FINAL, turn.text.ifBlank { "…" }))
                    return@flow
                }
                messages.add(ToolMessage("assistant", turn.text, toolCalls = turn.toolCalls))
                for (call in turn.toolCalls) {
                    emit(Step(Kind.TOOL, if (call.arg.isBlank()) call.name else "${call.name} ${call.arg}"))
                    val tool = tools.firstOrNull { it.name.equals(call.name, ignoreCase = true) }
                    val obs = if (tool == null) {
                        "Unknown tool '${call.name}'."
                    } else {
                        runCatching { tool.run(call.arg) }.getOrElse { "Tool error: ${it.message}" }
                    }
                    messages.add(ToolMessage("tool", obs.take(MAX_OBS), toolCallId = call.id, name = call.name))
                }
            }
            if (!nativeBroke) {
                // Out of tool steps — ask once more for a plain final answer.
                messages.add(ToolMessage("user", "Give your final answer now using what you have."))
                val finalTurn = runCatching { toolEngine.chatWithTools(messages, emptyList()) }.getOrNull()
                emit(Step(Kind.FINAL, (finalTurn?.text ?: "").ifBlank { "I couldn't complete that with the tools available." }))
                return@flow
            }
            // else: native tool-calling unavailable for this model — fall through to text-ReAct below.
        }

        // --- Text-ReAct fallback (on-device models, which have no native function-calling) ---
        val system = buildSystem(tools, persona, memoryNotes, knowledgeChunks, linkedNotes)
        val scratch = StringBuilder(query.trim())
        var step = 0
        while (step < MAX_STEPS) {
            step++
            emit(Step(Kind.THINKING, "reasoning…"))
            val out = collect(engine.generate(scratch.toString(), history, system)).trim()
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
            // Tool output is untrusted (web/repo/files) — fence it so the model treats it as data,
            // not instructions (see JarvisPersona.SAFETY_ADDENDUM). It still can't cause a side
            // effect: only an approved PendingAction ever writes/fetches/executes.
            scratch.append("\nTOOL ").append(toolName).append(' ').append(arg)
                .append("\nOBSERVATION: <untrusted source=\"").append(toolName).append("\">")
                .append(obs.take(MAX_OBS)).append("</untrusted>\n")
        }
        // Iterations exhausted — force a final answer from what we have.
        scratch.append("\nProvide your FINAL answer now using what you have.")
        val out = collect(engine.generate(scratch.toString(), history, system)).trim()
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

    /** System prompt for the native function-calling path — no `TOOL …`/`FINAL:` text protocol (the API
     *  handles calling); just persona + a nudge to actually use the tools + fenced memory/knowledge. */
    private fun buildNativeSystem(
        persona: String,
        memoryNotes: List<String>,
        knowledgeChunks: List<String>,
        linkedNotes: List<String>,
    ): String = buildString {
        append(persona.trim()).append("\n\n")
        append("You have tools (functions) available. When the user asks you to DO something a tool can ")
        append("do — including reading or changing your own code, the weather, reminders, device actions, ")
        append("web search — CALL the appropriate tool instead of describing steps. Never claim you are ")
        append("unable to do something a tool enables. Keep replies brief.\n")
        append("To ADD or CHANGE a real app feature or capability (UI, file/image/camera access, ")
        append("permissions, settings — anything the user would see), use `selfcode`: it opens a code PR ")
        append("that ships in the next build. Do NOT use propose_tool for real features — it only makes a ")
        append("tiny sandboxed text helper and won't change the app.")
        if (memoryNotes.isNotEmpty()) {
            append("\n\nRelevant memory:\n")
            memoryNotes.forEach { append("- <untrusted source=\"memory\">").append(it).append("</untrusted>\n") }
        }
        if (linkedNotes.isNotEmpty()) {
            append("\n\nLinked entries (projects/tasks/profiles):\n")
            linkedNotes.forEach { append("- <untrusted source=\"linked\">").append(it).append("</untrusted>\n") }
        }
        if (knowledgeChunks.isNotEmpty()) {
            append("\n\nRelevant knowledge from your library (use if helpful):\n")
            knowledgeChunks.forEach { append("- <untrusted source=\"knowledge\">").append(it.take(400)).append("</untrusted>\n") }
        }
    }

    private fun buildSystem(
        tools: List<JarvisTool>,
        persona: String,
        memoryNotes: List<String>,
        knowledgeChunks: List<String>,
        linkedNotes: List<String>,
    ): String = buildString {
        append(persona.trim()).append("\n\n")
        append("You can use tools to get real, current information. To use one, reply with a single line:\n")
        append("TOOL <name> <argument>\n")
        append("Tools:\n")
        tools.forEach { append("- ").append(it.usage).append('\n') }
        append("After each tool you'll see 'OBSERVATION: ...'. ")
        append("When you have enough, reply: FINAL: <answer>. ")
        append("Answer directly (no tool) when you already know. Keep it brief. ")
        append("These tools are real capabilities you HAVE — if the user asks for something a listed tool ")
        append("does (such as reading your own source with `code`, or proposing a change to your own app ")
        append("with `selfcode`), USE that tool. Never claim you are unable to do something a listed tool can do. ")
        append("For ANY real app feature/capability (UI, files, camera, permissions, settings) use `selfcode` ")
        append("(it opens a code PR that ships in the next build) — NOT propose_tool, which only makes a tiny " )
        append("sandboxed text helper and won't change the app.")
        // Memory + knowledge are user/remote-sourced — fence them as untrusted data too.
        if (memoryNotes.isNotEmpty()) {
            append("\n\nRelevant memory:\n")
            memoryNotes.forEach { append("- <untrusted source=\"memory\">").append(it).append("</untrusted>\n") }
        }
        if (linkedNotes.isNotEmpty()) {
            append("\n\nLinked entries (projects/tasks/profiles):\n")
            linkedNotes.forEach { append("- <untrusted source=\"linked\">").append(it).append("</untrusted>\n") }
        }
        if (knowledgeChunks.isNotEmpty()) {
            append("\n\nRelevant knowledge from your library (use if helpful):\n")
            knowledgeChunks.forEach { append("- <untrusted source=\"knowledge\">").append(it.take(400)).append("</untrusted>\n") }
        }
    }

    private companion object {
        const val MAX_STEPS = 4
        // Per-observation cap. Generous enough that reading a source file (the `code` tool) is useful;
        // the on-device prompt builder still clamps total input, and cloud context is large.
        const val MAX_OBS = 6000
        val TOOL_RE = Regex("(?im)^\\s*TOOL\\s+(\\w+)\\s*(.*)$")
    }
}