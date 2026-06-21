package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.Speaker
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.jarvis.inference.ChatFormat
import dev.mascwa.pulse.jarvis.inference.CloudProvider
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.ModelDownloadState
import dev.mascwa.pulse.jarvis.inference.ModelManager
import dev.mascwa.pulse.jarvis.inference.RoutingInferenceEngine
import dev.mascwa.pulse.jarvis.orchestrator.ActionOrchestrator
import dev.mascwa.pulse.jarvis.orchestrator.CommandStatus
import dev.mascwa.pulse.jarvis.orchestrator.LockdownResult
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
    private val knowledge: dev.mascwa.pulse.data.jarvis.KnowledgeStore,
    private val selfEdit: dev.mascwa.pulse.data.selfedit.SelfEditStore,
    private val jarvisMemory: JarvisMemory,
    private val orchestrator: ActionOrchestrator,
) : ViewModel() {

    /** Download lifecycle (Idle → Running → Done/Failed). Pre-seeded to Done if a model already exists. */
    val downloadState: StateFlow<ModelDownloadState> = modelManager.state

    /** Live engine state — Ready once the real model is loaded, Unavailable on the persona core. */
    val engineState: StateFlow<EngineState> = engine.state

    // ---- Console controls relocated here from the J.A.R.V.I.S. top bar ----

    /** Clear the conversation history. The console reflects it live (shared memory flow). */
    fun clearChat() {
        viewModelScope.launch { jarvisMemory.clearHistory() }
    }

    /** Run the Lockdown macro and log an honest per-command result to the conversation. */
    fun runLockdown() {
        viewModelScope.launch {
            val results = orchestrator.executeLockdownSequence()
            jarvisMemory.append(Speaker.JARVIS, formatLockdown(results))
        }
    }

    private fun formatLockdown(results: List<LockdownResult>): String = buildString {
        append("Lockdown sequence:\n")
        results.forEach { r ->
            val chip = when (r.status) {
                CommandStatus.DONE -> "✓"
                CommandStatus.NEEDS_PERMISSION -> "⚠"
                CommandStatus.UNSUPPORTED -> "✕"
            }
            append(chip).append(' ').append(r.label)
            if (r.detail.isNotBlank()) append(" — ").append(r.detail)
            append('\n')
        }
    }

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

    private val _followUp = MutableStateFlow(false)
    /** Whether the mic reopens after a reply so the user can answer without the wake word. */
    val followUpMode: StateFlow<Boolean> = _followUp.asStateFlow()

    private val _conversation = MutableStateFlow(false)
    /** Whether J.A.R.V.I.S. autonomously keeps a spoken conversation going. */
    val conversationMode: StateFlow<Boolean> = _conversation.asStateFlow()

    private val _voiceCloudInterpret = MutableStateFlow(true)
    /** Whether the active cloud brain cleans up the wake-word transcript before acting. */
    val voiceCloudInterpret: StateFlow<Boolean> = _voiceCloudInterpret.asStateFlow()

    private val _speakProactive = MutableStateFlow(false)
    /** Whether J.A.R.V.I.S. speaks proactive context remarks aloud while resident. */
    val speakProactive: StateFlow<Boolean> = _speakProactive.asStateFlow()

    private val _glassesHud = MutableStateFlow(false)
    /** Whether the glasses HUD renders on a connected external display. */
    val glassesHud: StateFlow<Boolean> = _glassesHud.asStateFlow()

    private val _agentTools = MutableStateFlow(false)
    /** Whether J.A.R.V.I.S. may use tools (web/GitHub-read/device/memory) in its agentic loop. */
    val agentTools: StateFlow<Boolean> = _agentTools.asStateFlow()

    private val _selfEdit = MutableStateFlow(false)
    /** Whether J.A.R.V.I.S. may PROPOSE self-edits/research/tools (each applied only on approval). */
    val selfEditEnabled: StateFlow<Boolean> = _selfEdit.asStateFlow()

    private val _selfCoding = MutableStateFlow(false)
    /** The single autonomous-self-coding switch: on = J.A.R.V.I.S. opens PRs, auto-merges on green CI
     *  and ships without per-change approval (drives all three underlying self-code flags together). */
    val selfCoding: StateFlow<Boolean> = _selfCoding.asStateFlow()

    private val _githubToken = MutableStateFlow("")
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    private val _feedTopic = MutableStateFlow("")
    /** What J.A.R.V.I.S. briefs on in the home status feed (a project/topic/"device health"). Never chat. */
    val feedTopic: StateFlow<String> = _feedTopic.asStateFlow()

    private val _chatFormat = MutableStateFlow(ChatFormat.AUTO)
    /** Chat template used to format prompts for the model (Auto/ChatML/Gemma/Plain). */
    val chatFormat: StateFlow<ChatFormat> = _chatFormat.asStateFlow()

    private val _charter = MutableStateFlow("")
    /** The user-supplied persona "charter" prepended atop every prompt (blank = built-in persona). */
    val charter: StateFlow<String> = _charter.asStateFlow()

    private val _backend = MutableStateFlow(0)
    /** Inference backend: 0=auto, 1=GPU, 2=CPU. */
    val inferenceBackend: StateFlow<Int> = _backend.asStateFlow()

    private val _cloudEnabled = MutableStateFlow(false)
    /** Whether chat uses a cloud AI instead of the on-device model. */
    val cloudEnabled: StateFlow<Boolean> = _cloudEnabled.asStateFlow()

    private val _cloudProvider = MutableStateFlow(CloudProvider.GEMINI)
    /** Selected cloud provider (OpenAI-compatible). */
    val cloudProvider: StateFlow<CloudProvider> = _cloudProvider.asStateFlow()

    private val _cloudApiKey = MutableStateFlow("")
    val cloudApiKey: StateFlow<String> = _cloudApiKey.asStateFlow()

    private val _cloudModel = MutableStateFlow("")
    /** Optional model override; blank uses the provider default. */
    val cloudModel: StateFlow<String> = _cloudModel.asStateFlow()

    private val _maxTokens = MutableStateFlow(2048)
    /** Reply-length budget (max_tokens) — caps the cloud reservation and the on-device answer. */
    val maxTokens: StateFlow<Int> = _maxTokens.asStateFlow()

    private val _curiosityLevel = MutableStateFlow(1)
    /** How often J.A.R.V.I.S. asks gap-filling questions: 0 Off / 1 Low / 2 Med / 3 High. */
    val curiosityLevel: StateFlow<Int> = _curiosityLevel.asStateFlow()

    private val _knowledgeChunks = MutableStateFlow(0)
    /** Number of chunks stored in the knowledge library (docs RAG). */
    val knowledgeChunks: StateFlow<Int> = _knowledgeChunks.asStateFlow()

    private val _knowledgeDocs = MutableStateFlow(0)
    /** Number of distinct documents in the knowledge library. */
    val knowledgeDocs: StateFlow<Int> = _knowledgeDocs.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = settings.current().jarvis
            _url.value = saved.modelUrl
            _token.value = saved.modelToken
            _resident.value = saved.residentService
            _vitals.value = saved.vitalsTracking
            _voiceReplies.value = saved.voiceReplies
            _wakeWord.value = saved.wakeWord
            _followUp.value = saved.followUpMode
            _conversation.value = saved.conversationMode
            _voiceCloudInterpret.value = saved.voiceCloudInterpret
            _speakProactive.value = saved.speakProactive
            _glassesHud.value = saved.glassesHud
            _agentTools.value = saved.agentToolsEnabled
            _selfEdit.value = saved.selfEditEnabled
            _selfCoding.value = saved.selfCodingEnabled
            _githubToken.value = saved.githubToken
            _chatFormat.value = saved.chatFormat
            _backend.value = saved.inferenceBackend
            _cloudEnabled.value = saved.cloudEnabled
            _cloudProvider.value = saved.cloudProvider
            _cloudApiKey.value = saved.cloudApiKey
            _cloudModel.value = saved.cloudModel
            _maxTokens.value = saved.maxTokens
            _curiosityLevel.value = saved.curiosityLevel
            _feedTopic.value = settings.current().jarvisFeedTopic
            _charter.value = runCatching { selfEdit.current().charter }.getOrDefault("")
            // If a model is already on disk, make sure the engine is warmed.
            engine.ensureReady()
        }
        refreshKnowledge()
    }

    fun onUrlChange(value: String) { _url.value = value }
    fun onTokenChange(value: String) { _token.value = value }

    /** Persist the inference backend and reload the model on it now. */
    fun setInferenceBackend(backend: Int) {
        _backend.value = backend
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(inferenceBackend = backend)) }
            engine.reset()
            runCatching { engine.ensureReady() }
        }
    }

    /** Enable/disable the cloud AI brain; refresh engine state so the status flips immediately. */
    fun setCloudEnabled(enabled: Boolean) {
        _cloudEnabled.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(cloudEnabled = enabled)) }
            runCatching { engine.ensureReady() }
        }
    }

    fun setCloudProvider(provider: CloudProvider) {
        _cloudProvider.value = provider
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(cloudProvider = provider)) }
            runCatching { engine.ensureReady() }
        }
    }

    fun onCloudKeyChange(value: String) {
        _cloudApiKey.value = value
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(cloudApiKey = value.trim())) }
            runCatching { engine.ensureReady() }
        }
    }

    fun onCloudModelChange(value: String) {
        _cloudModel.value = value
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(cloudModel = value.trim())) }
        }
    }

    /** Set the reply-length budget (max_tokens). Read fresh per generation, so it applies at once. */
    fun setMaxTokens(value: Int) {
        val clamped = value.coerceIn(256, 32_768)
        _maxTokens.value = clamped
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(maxTokens = clamped)) }
        }
    }

    fun setCuriosityLevel(level: Int) {
        _curiosityLevel.value = level
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(curiosityLevel = level)) }
        }
    }

    fun onCharterChange(value: String) { _charter.value = value }

    /** Persist the persona charter (snapshotting the previous one for rollback). */
    fun saveCharter() {
        viewModelScope.launch { runCatching { selfEdit.setCharter(_charter.value) } }
    }

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

    /** The single autonomous-self-coding switch. ON enables self-coding AND auto-merge-on-green AND
     *  per-change-free autonomy at once; OFF clears all three. The human-gate invariant is intact: this
     *  switch IS the user's deliberate opt-in, and protected paths (CI/signing/manifest/gate) + the CI
     *  build remain off-limits and required regardless. */
    fun setSelfCoding(enabled: Boolean) {
        _selfCoding.value = enabled
        viewModelScope.launch {
            settings.update {
                it.copy(
                    jarvis = it.jarvis.copy(
                        selfCodingEnabled = enabled,
                        selfCodeAutoMerge = enabled,
                        autonomousSelfCoding = enabled,
                    ),
                )
            }
        }
    }

    fun setSelfEdit(enabled: Boolean) {
        _selfEdit.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(selfEditEnabled = enabled)) }
        }
    }

    /** Persist the chat-template choice; the engine reads it fresh on the next generation. */
    fun setChatFormat(format: ChatFormat) {
        _chatFormat.value = format
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(chatFormat = format)) }
        }
    }

    /** Add a document to the knowledge library; it is chunked and indexed for retrieval. */
    fun addKnowledge(title: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            knowledge.addDocument(title, text, source = "manual")
            refreshKnowledge()
        }
    }

    /** Empty the knowledge library. */
    fun clearKnowledge() {
        viewModelScope.launch {
            knowledge.clear()
            refreshKnowledge()
        }
    }

    private fun refreshKnowledge() {
        viewModelScope.launch {
            _knowledgeChunks.value = runCatching { knowledge.chunkCount() }.getOrDefault(0)
            _knowledgeDocs.value = runCatching { knowledge.documentCount() }.getOrDefault(0)
        }
    }

    /** Update + persist the GitHub token (trimmed) for the read-only repo tool. */
    fun onGithubTokenChange(value: String) {
        _githubToken.value = value
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(githubToken = value.trim())) }
        }
    }

    /** Persist what J.A.R.V.I.S. should brief on in the home status feed (top-level setting, not chat). */
    fun onFeedTopicChange(value: String) {
        _feedTopic.value = value
        viewModelScope.launch {
            settings.update { it.copy(jarvisFeedTopic = value.trim()) }
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

    fun setFollowUpMode(enabled: Boolean) {
        _followUp.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(followUpMode = enabled)) }
        }
    }

    fun setConversationMode(enabled: Boolean) {
        _conversation.value = enabled
        // Conversation mode rides on the follow-up loop; enabling it implies follow-up.
        if (enabled) _followUp.value = true
        viewModelScope.launch {
            settings.update {
                it.copy(jarvis = it.jarvis.copy(conversationMode = enabled, followUpMode = it.jarvis.followUpMode || enabled))
            }
        }
    }

    fun setVoiceCloudInterpret(enabled: Boolean) {
        _voiceCloudInterpret.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(voiceCloudInterpret = enabled)) }
        }
    }

    fun setSpeakProactive(enabled: Boolean) {
        _speakProactive.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(speakProactive = enabled)) }
        }
    }

    fun setGlassesHud(enabled: Boolean) {
        _glassesHud.value = enabled
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(glassesHud = enabled)) }
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
