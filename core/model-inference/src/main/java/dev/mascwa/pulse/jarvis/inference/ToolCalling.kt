package dev.mascwa.pulse.jarvis.inference

/**
 * Native (OpenAI-style) function-calling capability, implemented by engines that can actually call tools
 * — currently the cloud brain. The agent loop uses this when available so the model emits structured
 * tool calls instead of an unreliable text protocol; on-device engines don't implement it and fall back
 * to the text-ReAct path.
 */

/** A tool offered to the model: [name] is the function name, [description] is its human usage string.
 *  Every tool takes a single string `arg` (matching `JarvisTool.run(arg)`). */
data class ToolSpec(val name: String, val description: String)

/** The model's request to call a tool. */
data class ToolInvocation(val id: String, val name: String, val arg: String)

/** A message in a tool-aware conversation. [role] is one of system/user/assistant/tool. For an assistant
 *  turn that requested tools, [toolCalls] is set; for a tool result, [toolCallId] + [name] identify it. */
data class ToolMessage(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val name: String? = null,
    val toolCalls: List<ToolInvocation> = emptyList(),
)

/** One assistant turn from a tool-capable model: either [toolCalls] to run, or final [text]. */
data class AssistantTurn(val text: String, val toolCalls: List<ToolInvocation> = emptyList())

interface ToolCallingEngine {
    /** True when this engine can do native function-calling right now (e.g. a cloud key is set). */
    suspend fun supportsTools(): Boolean

    /** One tool-aware completion: returns the model's tool calls to run, or its final text. */
    suspend fun chatWithTools(messages: List<ToolMessage>, tools: List<ToolSpec>): AssistantTurn
}
