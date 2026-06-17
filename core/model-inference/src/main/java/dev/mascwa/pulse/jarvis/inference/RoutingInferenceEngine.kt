package dev.mascwa.pulse.jarvis.inference

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single engine the rest of the app depends on. It prefers the real LLM — now loaded in a
 * **separate process** via [IsolatedInferenceEngine], so a native model-load crash can't take down
 * the app — and transparently falls back to the deterministic [EchoInferenceEngine] persona core
 * otherwise, so the console always works.
 *
 * The isolated engine is warmed lazily by [ensureReady]; call it again after a download completes.
 */
class RoutingInferenceEngine(
    context: Context,
    private val modelManager: ModelManager,
    private val fallback: LocalInferenceEngine = EchoInferenceEngine(),
    private val maxTokens: Int = 1024,
    /** Live chat-template choice + model URL, forwarded to the isolated engine's prompt builder. */
    private val promptConfig: suspend () -> PromptConfig = { PromptConfig() },
    /** Preferred inference backend (0=auto/1=GPU/2=CPU), read at load time. */
    private val backendProvider: suspend () -> Int = { 0 },
    /** Called when generation crashes natively on a non-CPU backend (app switches to CPU). */
    private val onNativeCrash: suspend () -> Unit = {},
) : LocalInferenceEngine {

    private val appContext = context.applicationContext
    private val isolated = IsolatedInferenceEngine(appContext, modelManager, maxTokens, promptConfig, backendProvider, onNativeCrash)

    private val _state = MutableStateFlow<EngineState>(EngineState.Unavailable)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    // Once a load fails (e.g. the model is too heavy and the :inference process aborts), don't keep
    // re-spawning a crashing process every time the console opens — hold the error until the user
    // re-provisions or deletes the model (which calls reset()).
    @Volatile private var loadFailed = false

    /** True when the real LLM is loaded and serving (vs. the persona fallback). Reflects LIVE
     *  process state, so if the :inference process is killed this flips back to false. */
    val isModelActive: Boolean get() = isolated.isReady

    override suspend fun ensureReady() {
        // Fast path only while the isolated process is genuinely alive + loaded.
        if (isolated.isReady) {
            _state.value = EngineState.Ready
            return
        }
        if (!modelManager.isModelPresent) {
            _state.value = EngineState.Unavailable
            return
        }
        if (loadFailed) return // keep the existing Error; reset() clears this on re-provision/delete
        _state.value = EngineState.Preparing
        // The isolated engine binds the :inference process and loads the model there. A native
        // abort surfaces as EngineState.Error (not an app crash); we mirror its result.
        isolated.ensureReady()
        val result = isolated.state.value
        _state.value = result
        loadFailed = result is EngineState.Error
    }

    override fun generate(
        prompt: String,
        history: List<ChatTurn>,
        system: String?,
    ): Flow<String> =
        // Route by LIVE readiness: if the process died mid-session, fall back to the persona core
        // rather than emitting an "offline" stub for every turn.
        if (isolated.isReady) isolated.generate(prompt, history, system) else fallback.generate(prompt, history, system)

    /** Drop the loaded model (e.g. after the user deletes it) and revert to the core. */
    fun reset() {
        loadFailed = false
        isolated.close()
        _state.value = EngineState.Unavailable
    }

    override fun close() {
        isolated.close()
        fallback.close()
    }
}
