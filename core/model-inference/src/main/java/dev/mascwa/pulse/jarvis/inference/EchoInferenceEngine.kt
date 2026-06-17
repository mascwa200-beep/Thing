package dev.mascwa.pulse.jarvis.inference

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Deterministic, fully-offline persona engine. It is always [EngineState.Ready],
 * needs no model download, and serves two purposes:
 *  1. lets the console work before the MediaPipe LLM is provisioned, and
 *  2. is the graceful fallback whenever the real model is unavailable
 *     (e.g. on a network-stripped device that never downloaded it).
 *
 * It is a templated responder, not a generative model — it never fabricates
 * facts, it just acknowledges, reflects context, and stays in character.
 */
class EchoInferenceEngine : LocalInferenceEngine {

    private val _state = MutableStateFlow<EngineState>(EngineState.Ready)
    override val state: StateFlow<EngineState> = _state

    override fun generate(
        prompt: String,
        history: List<ChatTurn>,
        system: String?,
    ): Flow<String> = flow {
        val reply = JarvisPersona.reply(prompt.trim())
        // Stream word-by-word so the console renders a live "typing" effect.
        val words = reply.split(' ')
        words.forEachIndexed { i, w ->
            emit(if (i == 0) w else " $w")
            delay(18)
        }
    }
}

/** Small deadpan-assistant persona used by [EchoInferenceEngine]. */
internal object JarvisPersona {

    fun reply(input: String): String {
        if (input.isBlank()) return "Standing by, sir."
        val lower = input.lowercase()
        return when {
            lower.containsAny("hello", "hi ", "hey", "good morning", "good evening") ->
                "Online and at your service, sir."
            lower.containsAny("who are you", "your name", "what are you") ->
                "J.A.R.V.I.S. — your on-device assistant. Everything you say stays on this phone."
            lower.containsAny("thank", "thanks", "cheers") ->
                "Always, sir."
            lower.containsAny("status", "report", "how are you", "systems") ->
                "All subsystems nominal. The reasoning model isn't loaded yet — I'm on the offline persona core."
            lower.containsAny("battery", "power", "charge") ->
                "Open the Telemetry console and I'll read live battery, thermal and motion off the hardware."
            lower.containsAny("lockdown", "lock down", "secure") ->
                "Lockdown wipes the clipboard and drops to a silent profile. Network and Bluetooth are Settings-only on this OS — I'll deep-link you."
            lower.containsAny("model", "download", "llm", "offline model") ->
                "Point me at a model in Setup and I'll pull it to on-device storage. Until then, I answer from the persona core."
            lower.endsWith("?") ->
                "I'd need the reasoning model for that, sir — provision it in Setup and ask again."
            else ->
                "Noted. Load the on-device model in Setup and I can reason in full, sir."
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }
}
