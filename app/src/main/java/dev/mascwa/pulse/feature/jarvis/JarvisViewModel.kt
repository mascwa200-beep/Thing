package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.Speaker
import dev.mascwa.pulse.core.telemetry.BanterContextEngine
import dev.mascwa.pulse.core.telemetry.DeviceContext
import dev.mascwa.pulse.core.telemetry.DeviceContextProvider
import dev.mascwa.pulse.core.telemetry.IntentRouter
import dev.mascwa.pulse.core.telemetry.JarvisIntent
import dev.mascwa.pulse.jarvis.inference.ChatTurn
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class JarvisMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
)

class JarvisViewModel(
    private val memory: JarvisMemory,
    private val engine: LocalInferenceEngine,
    private val deviceContext: DeviceContextProvider,
    private val banter: BanterContextEngine,
    private val router: IntentRouter,
) : ViewModel() {

    val messages: StateFlow<List<JarvisMessage>> =
        memory.history
            .map { rows ->
                rows.map { JarvisMessage(it.messageText, it.speaker == Speaker.USER, it.timestamp) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineState: StateFlow<EngineState> = engine.state

    /**
     * The assistant's proactive, context-aware line: a greeting on first read, then a fresh
     * remark whenever power/network crosses a threshold (last meaningful line is retained).
     */
    val banterLine: StateFlow<String> =
        deviceContext.updates
            .runningFold(Pair<DeviceContext?, String>(null, "")) { acc, now ->
                val prev = acc.first
                val line = if (prev == null) banter.greeting(now) else banter.reactTo(prev, now) ?: acc.second
                now to line
            }
            .map { it.second }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _streaming = MutableStateFlow("")
    /** The assistant's in-flight partial reply, or "" when idle. */
    val streaming: StateFlow<String> = _streaming.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch { engine.ensureReady() }
    }

    fun send(input: String) {
        val text = input.trim()
        if (text.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _streaming.value = ""
            memory.append(Speaker.USER, text)
            try {
                when (val intent = router.route(text)) {
                    is JarvisIntent.Status ->
                        memory.append(Speaker.JARVIS, banter.statusReport(deviceContext.snapshot()))
                    is JarvisIntent.Lockdown ->
                        memory.append(
                            Speaker.JARVIS,
                            "Lockdown arms from the Active-Matrix service (coming online): it silences " +
                                "the profile and clears the clipboard. Network and Bluetooth toggles are " +
                                "Settings-only on this OS, so I'll deep-link you there.",
                        )
                    is JarvisIntent.Chat -> {
                        val history = memory.recentContext(HISTORY_TURNS)
                            .map { ChatTurn(it.speaker, it.messageText) }
                        val sb = StringBuilder()
                        engine.generate(intent.text, history, SYSTEM_PROMPT).collect { token ->
                            sb.append(token)
                            _streaming.value = sb.toString()
                        }
                        memory.append(Speaker.JARVIS, sb.toString().ifBlank { "…" })
                    }
                }
            } catch (e: Exception) {
                memory.append(Speaker.JARVIS, "// fault: ${e.message ?: "inference error"}")
            } finally {
                _streaming.value = ""
                _busy.value = false
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { memory.clearHistory() }
    }

    private companion object {
        const val HISTORY_TURNS = 12
        const val SYSTEM_PROMPT =
            "You are J.A.R.V.I.S. Matrix, a concise, deadpan, privacy-first on-device assistant. " +
                "You run entirely on the user's phone. Be brief and helpful. Never invent facts."
    }
}
