package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.jarvis.inference.ChatFormat
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.ModelDownloadState
import dev.mascwa.pulse.jarvis.inference.ModelManager
import dev.mascwa.pulse.jarvis.inference.RoutingInferenceEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the J.A.R.V.I.S. model-provisioning screen: edits the model URL/token,
 * streams the download, and flips the live [RoutingInferenceEngine] over to the real
 * LLM when the file lands — all without restarting the app.
 */
class JarvisSetupViewModel(
    private val modelManager: ModelManager,
    private val engine: RoutingInferenceEngine,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** Download lifecycle (Idle → Running → Done/Failed). Pre-seeded to Done if a model already exists. */
    val downloadState: StateFlow<ModelDownloadState> = modelManager.state

    /** Live engine state — Ready once the real model is loaded, Unavailable on the persona core. */
    val engineState: StateFlow<EngineState> = engine.state

    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _resident = MutableStateFlow(false)
    /** Whether the user wants the Active-Matrix resident service running. */
    val resident: StateFlow<Boolean> = _resident.asStateFlow()

    private val _vitals = MutableStateFlow(false)
    /** Whether the user wants BLE heart-rate vitals tracking. */
    val vitals: StateFlow<Boolean> = _vitals.asStateFlow()

    private val _voiceReplies = MutableStateFlow(false)
    /** Whether J.A.R.V.I.S. speaks replies aloud. */
    val voiceReplies: StateFlow<Boolean> = _voiceReplies.asStateFlow()

    private val _wakeWord = MutableStateFlow(false)
    /** Whether J.A.R.V.I.S. listens for its wake word while resident. */
    val wakeWord: StateFlow<Boolean> = _wakeWord.asStateFlow()

    private val _agentTools = MutableStateFlow(false)
    /** Whether J.A.R.V.I.S. may use tools (web/GitHub-read/device/memory) in its agentic loop. */
    val agentTools: StateFlow<Boolean> = _agentTools.asStateFlow()

    private val _githubToken = MutableStateFlow("")
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    private val _chatFormat = MutableStateFlow(ChatFormat.AUTO)
    /** Chat template used to format prompts for the model (Auto/ChatML/Gemma/Plain). */
    val chatFormat: StateFlow<ChatFormat> = _chatFormat.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settings.current().jarvis
            _url.value = saved.modelUrl
            _token.value = saved.modelToken
            _resident.value = saved.residentService
            _vitals.value = saved.vitalsTracking
            _voiceReplies.value = saved.voiceReplies
            _wakeWord.value = saved.wakeWord
            _agentTools.value = saved.agentToolsEnabled
            _githubToken.value = saved.githubToken
            _chatFormat.value = saved.chatFormat
            // If a model is already on disk, make sure the engine is warmed.
            engine.ensureReady()
        }
    }

    fun onUrlChange(value: String) { _url.value = value }
    fun onTokenChange(value: String) { _token.value = value }

    /** Persist the resident-service preference. Starting/stopping the service itself is
     *  done by the screen, which has the Android context. */
    fun setResident(enabled: Boolean) {
        _resident.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(residentService = enabled)) }
        }
    }

    fun setVitals(enabled: Boolean) {
        _vitals.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(vitalsTracking = enabled)) }
        }
    }

    fun setVoiceReplies(enabled: Boolean) {
        _voiceReplies.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(voiceReplies = enabled)) }
        }
    }

    fun setAgentTools(enabled: Boolean) {
        _agentTools.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(agentToolsEnabled = enabled)) }
        }
    }

    /** Persist the chat-template choice; the engine reads it fresh on the next generation. */
    fun setChatFormat(format: ChatFormat) {
        _chatFormat.value = format
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(chatFormat = format)) }
        }
    }

    /** Update + persist the GitHub token (trimmed) for the read-only repo tool. */
    fun onGithubTokenChange(value: String) {
        _githubToken.value = value
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(githubToken = value.trim())) }
        }
    }

    /** Persist the wake-word preference. The screen restarts the resident service so it
     *  re-opens (or releases) the microphone to match. */
    fun setWakeWord(enabled: Boolean) {
        _wakeWord.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(wakeWord = enabled)) }
        }
    }

    fun download() {
        viewModelScope.launch {
            val url = _url.value.trim()
            val token = _token.value.trim()
            settings.update { it.copy(jarvis = it.jarvis.copy(modelUrl = url, modelToken = token)) }
            val result = modelManager.download(url, token.ifBlank { null })
            if (result.isSuccess) {
                engine.reset()        // drop any previously-loaded delegate
                engine.ensureReady()  // load the freshly-downloaded model
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            modelManager.deleteModel()
            engine.reset()
        }
    }

    fun modelSizeBytes(): Long = modelManager.modelSizeBytes()
}
