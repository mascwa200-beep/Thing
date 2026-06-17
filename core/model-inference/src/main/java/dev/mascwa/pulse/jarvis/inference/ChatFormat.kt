package dev.mascwa.pulse.jarvis.inference

/**
 * How a flat conversation is wrapped into the control tokens a given model was trained on.
 *
 * MediaPipe's `LlmInference.generateResponse()` does **not** apply a chat template — it tokenizes
 * the string verbatim — so an instruct model only behaves if we emit its exact turn markers.
 * Feeding Qwen2.5 a generic "User:/J.A.R.V.I.S.:" transcript (instead of ChatML) is what made the
 * replies come out garbled.
 */
enum class ChatFormat(val label: String) {
    /** Pick the template from the model URL/family (Qwen → ChatML, Gemma → start_of_turn turns). */
    AUTO("Auto"),

    /** Qwen and most modern instruct models: `<|im_start|>role … <|im_end|>`. */
    CHATML("ChatML"),

    /** Gemma: `<start_of_turn>user|model … <end_of_turn>`; no dedicated system role. */
    GEMMA("Gemma"),

    /** Phi-4 (mini): `<|system|>…<|end|><|user|>…<|end|><|assistant|>` with no newlines. */
    PHI("Phi"),

    /**
     * Legacy plain transcript ("User:/J.A.R.V.I.S.:"). A safe fallback for a model that already
     * embeds its own template (so we don't double-wrap) or an unrecognised family.
     */
    PLAIN("Plain"),
    ;

    companion object {
        /**
         * Resolve [AUTO] to a concrete family from the model [modelUrl]; concrete values pass
         * through unchanged. Unknown/blank URLs fall back to [CHATML] — the most common modern
         * instruct template (the user can override to [PLAIN] in Setup if a model needs it).
         */
        fun resolve(format: ChatFormat, modelUrl: String?): ChatFormat = when (format) {
            AUTO -> when {
                modelUrl == null -> CHATML
                modelUrl.contains("gemma", ignoreCase = true) -> GEMMA
                modelUrl.contains("phi", ignoreCase = true) -> PHI
                else -> CHATML
            }
            else -> format
        }
    }
}

/** What [IsolatedInferenceEngine] needs to format a prompt for the currently-provisioned model. */
data class PromptConfig(
    val format: ChatFormat = ChatFormat.AUTO,
    val modelUrl: String? = null,
)

/**
 * Render the persona prompt + recent turns + the new message into a single model-ready string,
 * using [format]'s control tokens. [format] should already be resolved to a concrete family
 * (see [ChatFormat.resolve]); [ChatFormat.AUTO] is treated as [ChatFormat.CHATML] defensively.
 *
 * [history] is oldest → newest; only the last [maxTurns] are kept. Roles follow [ChatTurn.role]:
 * "user" is the user, anything else (e.g. "jarvis") is the assistant/model.
 */
fun renderPrompt(
    format: ChatFormat,
    prompt: String,
    history: List<ChatTurn>,
    system: String?,
    maxTurns: Int = 8,
): String {
    val turns = history.takeLast(maxTurns)
    val sys = system?.trim().orEmpty()
    val user = prompt.trim()
    return when (format) {
        ChatFormat.PLAIN -> plain(user, turns, sys)
        ChatFormat.GEMMA -> gemma(user, turns, sys)
        ChatFormat.PHI -> phi(user, turns, sys)
        // AUTO shouldn't reach here, but default to ChatML rather than the broken transcript.
        ChatFormat.CHATML, ChatFormat.AUTO -> chatml(user, turns, sys)
    }
}

private fun isUser(turn: ChatTurn) = turn.role.equals("user", ignoreCase = true)

/** ChatML (Qwen et al.): explicit system/user/assistant turns, ending on an open assistant turn. */
private fun chatml(user: String, history: List<ChatTurn>, system: String): String = buildString {
    if (system.isNotBlank()) {
        append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
    }
    history.forEach { turn ->
        val role = if (isUser(turn)) "user" else "assistant"
        append("<|im_start|>").append(role).append('\n').append(turn.text.trim()).append("<|im_end|>\n")
    }
    append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
    append("<|im_start|>assistant\n")
}

/** Gemma: user/model turns with no system role, so the persona is folded into the first user turn. */
private fun gemma(user: String, history: List<ChatTurn>, system: String): String {
    val seq = history.map { isUser(it) to it.text.trim() } + (true to user)
    val firstUserIdx = seq.indexOfFirst { it.first }
    return buildString {
        seq.forEachIndexed { i, (userTurn, text) ->
            val tag = if (userTurn) "user" else "model"
            val body = if (i == firstUserIdx && system.isNotBlank()) "$system\n\n$text" else text
            append("<start_of_turn>").append(tag).append('\n').append(body).append("<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }
}

/** Phi-4 (mini): system/user/assistant turns delimited by `<|…|>` + `<|end|>`, no newlines. */
private fun phi(user: String, history: List<ChatTurn>, system: String): String = buildString {
    if (system.isNotBlank()) append("<|system|>").append(system).append("<|end|>")
    history.forEach { turn ->
        val role = if (isUser(turn)) "user" else "assistant"
        append("<|").append(role).append("|>").append(turn.text.trim()).append("<|end|>")
    }
    append("<|user|>").append(user).append("<|end|>")
    append("<|assistant|>")
}

/** Legacy transcript — kept so a model that already templates internally isn't double-wrapped. */
private fun plain(user: String, history: List<ChatTurn>, system: String): String = buildString {
    if (system.isNotBlank()) append(system).append("\n\n")
    history.forEach { turn ->
        val who = if (isUser(turn)) "User" else "J.A.R.V.I.S."
        append(who).append(": ").append(turn.text.trim()).append('\n')
    }
    append("User: ").append(user).append('\n')
    append("J.A.R.V.I.S.: ")
}
