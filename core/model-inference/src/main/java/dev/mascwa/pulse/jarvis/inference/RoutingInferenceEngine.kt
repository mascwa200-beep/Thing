package dev.mascwa.pulse.jarvis.inference

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The single engine the rest of the app depends on. It prefers the real MediaPipe LLM
 * once a model has been provisioned on disk and transparently falls back to the
 * deterministic [EchoInferenceEngine] persona core otherwise — so the console always
 * works, with or without a downloaded model.
 *
 * The MediaPipe runtime is loaded lazily by [ensureReady]; call it again after a
 * download completes to switch the live engine over without restarting the app.
 */
class RoutingInferenceEngine(
    context: Context,
    private val modelManager: ModelManager,
    private val fallback: LocalInferenceEngine = EchoInferenceEngine(),
    private val maxTokens: Int = 1024,
) : LocalInferenceEngine {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<EngineState>(EngineState.Unavailable)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var delegate: MediaPipeInferenceEngine? = null

    /** True when the real LLM is loaded and serving (vs. the persona fallback). */
    val isModelActive: Boolean get() = delegate != null

    override suspend fun ensureReady() {
        if (delegate != null) {
            _state.value = EngineState.Ready
            return
        }
        if (!modelManager.isModelPresent) {
            _state.value = EngineState.Unavailable
            return
        }
        _state.value = EngineState.Preparing
        withContext(Dispatchers.Default) {
            runCatching { MediaPipeInferenceEngine(appContext, modelManager.modelPath(), maxTokens) }
        }.onSuccess {
            delegate = it
            _state.value = EngineState.Ready
        }.onFailure {
            _state.value = EngineState.Error(it.message ?: "Model failed to load.")
        }
    }

    override fun generate(
        prompt: String,
        history: List<ChatTurn>,
        system: String?,
    ): Flow<String> = (delegate ?: fallback).generate(prompt, history, system)

    /** Drop the loaded model (e.g. after the user deletes it) and revert to the core. */
    fun reset() {
        delegate?.close()
        delegate = null
        _state.value = EngineState.Unavailable
    }

    override fun close() {
        delegate?.close()
        fallback.close()
    }
}
