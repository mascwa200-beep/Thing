package dev.mascwa.pulse.jarvis.inference

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Real on-device LLM backed by MediaPipe LLM Inference. The model (.task / .bin) is
 * read from local storage — there is no network call and no Google Play Services
 * dependency, which keeps it viable on a de-Googled / GrapheneOS device.
 *
 * Construction loads and warms the model, which is expensive and blocking, so callers
 * must build it off the main thread (see [RoutingInferenceEngine.ensureReady]).
 */
class MediaPipeInferenceEngine(
    context: Context,
    modelPath: String,
    maxTokens: Int = 1024,
) : LocalInferenceEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.Ready)
    override val state: StateFlow<EngineState> = _state

    private val llm: LlmInference = LlmInference.createFromOptions(
        context.applicationContext,
        LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .build(),
    )

    override fun generate(
        prompt: String,
        history: List<ChatTurn>,
        system: String?,
    ): Flow<String> = flow {
        // The single-shot API returns the whole response; re-stream it word-by-word so
        // the console keeps its live "typing" cadence.
        val response = llm.generateResponse(buildPrompt(prompt, history, system))
        response.split(' ').forEachIndexed { i, word ->
            emit(if (i == 0) word else " $word")
        }
    }.flowOn(Dispatchers.Default)

    override fun close() {
        runCatching { llm.close() }
    }

    /** Flatten the persona prompt + recent turns + the new message into one transcript. */
    private fun buildPrompt(prompt: String, history: List<ChatTurn>, system: String?): String =
        buildString {
            if (!system.isNullOrBlank()) append(system.trim()).append("\n\n")
            history.takeLast(8).forEach { turn ->
                val who = if (turn.role.equals("user", ignoreCase = true)) "User" else "J.A.R.V.I.S."
                append(who).append(": ").append(turn.text.trim()).append('\n')
            }
            append("User: ").append(prompt.trim()).append('\n')
            append("J.A.R.V.I.S.: ")
        }
}
