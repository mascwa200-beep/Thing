package dev.mascwa.pulse.jarvis.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One conversational turn fed back into the model as context. */
data class ChatTurn(val role: String, val text: String)

/** Lifecycle of a local model runtime. */
sealed interface EngineState {
    /** No model present yet (needs first-run download / setup). */
    data object Unavailable : EngineState
    data class Downloading(val pct: Int, val label: String = "") : EngineState
    data object Preparing : EngineState
    data object Ready : EngineState
    data class Error(val message: String) : EngineState
}

/**
 * Abstraction over an on-device text generator. The console, banter engine and
 * intent router depend only on this — so the deterministic [EchoInferenceEngine]
 * and the real process-isolated [IsolatedInferenceEngine] are fully interchangeable.
 */
interface LocalInferenceEngine {
    val state: StateFlow<EngineState>

    /**
     * Streams the response token-by-token. [history] is the recent context window
     * (oldest → newest); [system] is an optional system/persona prompt.
     */
    fun generate(
        prompt: String,
        history: List<ChatTurn> = emptyList(),
        system: String? = null,
    ): Flow<String>

    /** Prepare the runtime (load/warm the model). Safe to call repeatedly. */
    suspend fun ensureReady() {}

    fun close() {}
}
