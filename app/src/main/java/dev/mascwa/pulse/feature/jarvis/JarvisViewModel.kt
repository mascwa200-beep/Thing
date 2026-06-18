package dev.mascwa.pulse.feature.jarvis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.jarvis.JarvisMemory
import dev.mascwa.pulse.data.jarvis.db.NoteSource
import dev.mascwa.pulse.data.jarvis.db.Speaker
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.jarvis.curiosity.CuriosityEngine
import dev.mascwa.pulse.core.telemetry.BanterContextEngine
import dev.mascwa.pulse.core.telemetry.DeviceContext
import dev.mascwa.pulse.core.telemetry.DeviceContextProvider
import dev.mascwa.pulse.core.telemetry.IntentRouter
import dev.mascwa.pulse.core.telemetry.JarvisIntent
import dev.mascwa.pulse.jarvis.JarvisPersona
import dev.mascwa.pulse.jarvis.orchestrator.ActionOrchestrator
import dev.mascwa.pulse.jarvis.orchestrator.CommandStatus
import dev.mascwa.pulse.jarvis.orchestrator.LockdownResult
import dev.mascwa.pulse.jarvis.inference.ChatTurn
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.agent.AgentOrchestrator
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import dev.mascwa.pulse.jarvis.voice.TextToSpeechEngine
import dev.mascwa.pulse.jarvis.voice.VoskListener
import dev.mascwa.pulse.jarvis.voice.VoskSpeech
import kotlinx.coroutines.Dispatchers
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

/** Tap-to-talk voice input lifecycle. */
sealed interface VoiceInputState {
    data object Idle : VoiceInputState
    /** [status] carries provisioning progress (e.g. "downloading voice model 42%") for the big model. */
    data class Preparing(val status: String = "") : VoiceInputState
    data class Listening(val partial: String) : VoiceInputState
    data class Error(val message: String) : VoiceInputState
}

class JarvisViewModel(
    private val memory: JarvisMemory,
    private val engine: LocalInferenceEngine,
    private val deviceContext: DeviceContextProvider,
    private val banter: BanterContextEngine,
    private val router: IntentRouter,
    private val orchestrator: ActionOrchestrator,
    private val tts: TextToSpeechEngine,
    private val settings: SettingsRepository,
    private val voskSpeech: VoskSpeech,
    private val agent: AgentOrchestrator,
    private val knowledge: dev.mascwa.pulse.data.jarvis.KnowledgeStore,
    private val selfEdit: dev.mascwa.pulse.data.selfedit.SelfEditStore,
    private val briefing: dev.mascwa.pulse.jarvis.BriefingBuilder,
    private val curiosity: CuriosityEngine,
    private val approvalGate: dev.mascwa.pulse.jarvis.selfedit.ApprovalGate,
) : ViewModel() {

    /** A curiosity question awaiting the user's answer, then their confirm of the distilled fact. */
    private data class PendingLearn(val question: String, val staged: String? = null)
    private var pendingLearn: PendingLearn? = null

    val messages: StateFlow<List<JarvisMessage>> =
        memory.history
            .map { rows ->
                rows.map { JarvisMessage(it.messageText, it.speaker == Speaker.USER, it.timestamp) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val engineState: StateFlow<EngineState> = engine.state

    /** The active cloud provider's label when the cloud brain is on (else null), for the status pill. */
    val cloudStatus: StateFlow<String?> =
        settings.settings
            .map { if (it.jarvis.cloudActive) it.jarvis.cloudProvider.label else null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether replies are spoken aloud (mirrors the persisted Jarvis setting). */
    val voiceReplies: StateFlow<Boolean> =
        settings.settings
            .map { it.jarvis.voiceReplies }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    private val _voiceInput = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    /** Tap-to-talk voice-input state (Idle → Preparing → Listening → back to Idle on a result). */
    val voiceInput: StateFlow<VoiceInputState> = _voiceInput.asStateFlow()

    /** The newest staged self-code change awaiting approval — surfaced inline in the console so the user
     *  can approve/reject without leaving the chat. Null when nothing is pending. */
    val pendingCode: StateFlow<dev.mascwa.pulse.data.selfedit.PendingAction?> =
        selfEdit.state
            .map { st -> st.pendingActions.lastOrNull { it.type == dev.mascwa.pulse.data.selfedit.ActionType.CODE_PR } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
                val pending = pendingLearn
                if (pending != null) handleCuriosityReply(pending, text) else routeTurn(text)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable (not just Exception) so an OutOfMemoryError from inference becomes a
                // fault bubble rather than an app crash.
                memory.append(Speaker.JARVIS, "// fault: ${e.message ?: "inference error"}")
            } finally {
                _streaming.value = ""
                _busy.value = false
            }
        }
    }

    /** Normal intent routing for a turn (also the path when a curiosity reply turns out to be a new
     *  request rather than an answer). */
    private suspend fun routeTurn(text: String) {
        when (val intent = router.route(text)) {
            is JarvisIntent.Status -> {
                val report = banter.statusReport(deviceContext.snapshot())
                sayJarvis(report)
            }
            is JarvisIntent.Lockdown -> executeLockdown()
            is JarvisIntent.Brief -> {
                val brief = briefing.build()
                sayJarvis(brief)
            }
            is JarvisIntent.Chat -> {
                // Reload the model if its (separate) process was reclaimed or faulted, so a
                // transient "process lost" self-heals instead of sticking on the persona core.
                runCatching { engine.ensureReady() }
                val useAgent = runCatching { settings.current().jarvis.agentToolsEnabled }.getOrDefault(false)
                val reply = if (useAgent) generateWithAgent(intent.text) else generateDirect(intent.text)
                memory.append(Speaker.JARVIS, reply)
                speakIfEnabled(reply)
                maybeBeCurious()
            }
        }
    }

    /** Append a J.A.R.V.I.S. line to history and speak it if voice replies are on. */
    private suspend fun sayJarvis(text: String) {
        memory.append(Speaker.JARVIS, text)
        speakIfEnabled(text)
    }

    /** End-of-turn curiosity hook: ask one gap/follow-up question (rate-limited inside the engine). */
    private suspend fun maybeBeCurious() {
        if (pendingLearn != null) return
        val turns = memory.recentContext(HISTORY_TURNS).map { ChatTurn(it.speaker, it.messageText) }
        val question = runCatching { curiosity.nextPrompt(turns) }.getOrNull() ?: return
        pendingLearn = PendingLearn(question = question)
        sayJarvis(question)
    }

    /** Handle the user's reply to a curiosity question: capture the answer, reflect the fact back, then
     *  save only on confirmation — so the user can correct it before it's stored. */
    private suspend fun handleCuriosityReply(pending: PendingLearn, text: String) {
        if (pending.staged == null) {
            // If the user issued a command / new question instead of answering, don't capture it as the
            // answer — drop the pending question and handle the message normally.
            if (curiosity.classify(text) == CuriosityEngine.Confirm.OTHER) {
                pendingLearn = null
                routeTurn(text)
                return
            }
            val fact = curiosity.distill(pending.question, text)
            pendingLearn = pending.copy(staged = fact)
            sayJarvis("Noted — I'll remember: \"$fact\". Shall I keep that, sir? (yes / no — or just correct me.)")
            return
        }
        when (curiosity.classify(text)) {
            CuriosityEngine.Confirm.AFFIRM -> {
                runCatching { memory.remember(pending.staged, NoteSource.LEARNED) }
                pendingLearn = null
                sayJarvis("Committed to memory, sir.")
            }
            CuriosityEngine.Confirm.REJECT -> {
                pendingLearn = null
                sayJarvis("Forgotten, sir.")
            }
            CuriosityEngine.Confirm.CORRECTION -> {
                val fact = curiosity.distill(pending.question, text)
                runCatching { memory.remember(fact, NoteSource.LEARNED) }
                pendingLearn = null
                sayJarvis("Corrected — saved, sir.")
            }
            CuriosityEngine.Confirm.OTHER -> {
                // Not an answer — don't hijack the conversation. Drop the staged fact and route normally.
                pendingLearn = null
                routeTurn(text)
            }
        }
    }

    /** Speak a daily brief from a console control (no fake user bubble). */
    fun requestBrief() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val brief = briefing.build()
                memory.append(Speaker.JARVIS, brief)
                speakIfEnabled(brief)
            } catch (e: Exception) {
                memory.append(Speaker.JARVIS, "// brief fault: ${e.message ?: "error"}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Run the Lockdown macro from a console control (no fake user bubble). */
    fun runLockdown() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                executeLockdown()
            } catch (e: Exception) {
                memory.append(Speaker.JARVIS, "// lockdown fault: ${e.message ?: "error"}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Execute the lockdown sequence and report honest, per-command results. */
    private suspend fun executeLockdown() {
        val results = orchestrator.executeLockdownSequence()
        memory.append(Speaker.JARVIS, formatLockdown(results))
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

    /** Single-shot reply straight from the model (no tools), streaming tokens to the console. */
    private suspend fun generateDirect(text: String): String {
        val history = memory.recentContext(HISTORY_TURNS).map { ChatTurn(it.speaker, it.messageText) }
        // Attentiveness/memory maximal: thread what J.A.R.V.I.S. remembers about the user into the moment.
        val system = withMemory(withKnowledge(composePersona(), text), text)
        val sb = StringBuilder()
        engine.generate(text, history, system).collect { token ->
            sb.append(token)
            _streaming.value = sb.toString()
        }
        return sb.toString().ifBlank { "…" }
    }

    /** Prepend the most relevant knowledge-library chunks to [base] so direct chat is RAG-grounded
     *  too (not only the agent path). No-op when the library is empty or nothing matches. */
    private suspend fun withKnowledge(base: String, query: String): String {
        val docs = runCatching { knowledge.search(query, limit = 3) }.getOrDefault(emptyList())
        if (docs.isEmpty()) return base
        // Fence retrieved docs as untrusted data (see JarvisPersona.SAFETY_ADDENDUM).
        val block = docs.joinToString("\n") { "- <untrusted source=\"knowledge\">[${it.title}] ${it.text.take(400)}</untrusted>" }
        return base + "\n\nRelevant knowledge from your library (use if helpful):\n" + block
    }

    /** Thread durable memories relevant to [query] into the prompt so J.A.R.V.I.S. references what the
     *  user has told it — attentiveness/memory always-on. Read-only recall; no-op when nothing matches. */
    private suspend fun withMemory(base: String, query: String): String {
        val notes = runCatching { memory.recall(query, limit = 5) }.getOrDefault(emptyList())
        if (notes.isEmpty()) return base
        val block = notes.joinToString("\n") { "- ${it.noteText}" }
        return base + "\n\nWhat you remember about the user (weave in naturally when relevant):\n" + block
    }

    /** The live system prompt: the user's charter (or built-in persona) + the immutable safety addendum. */
    private suspend fun composePersona(): String =
        JarvisPersona.compose(runCatching { selfEdit.current().charter }.getOrDefault(""))

    /** Run the bounded agentic loop, surfacing tool/reasoning steps in the streaming line. */
    private suspend fun generateWithAgent(text: String): String {
        val sb = StringBuilder()
        agent.run(text, composePersona()).collect { step ->
            when (step.kind) {
                AgentOrchestrator.Kind.THINKING -> _streaming.value = "◌ reasoning…"
                AgentOrchestrator.Kind.TOOL -> _streaming.value = "⚙ ${step.text}"
                AgentOrchestrator.Kind.FINAL -> sb.append(step.text)
            }
        }
        return sb.toString().ifBlank { "…" }
    }

    /** Speak [text] aloud when the user has voice replies enabled. */
    private fun speakIfEnabled(text: String) {
        if (voiceReplies.value) tts.speak(text)
    }

    /** Toggle spoken replies; persists the setting and silences any current utterance when off. */
    fun setVoiceReplies(on: Boolean) {
        viewModelScope.launch {
            settings.update { it.copy(jarvis = it.jarvis.copy(voiceReplies = on)) }
            if (!on) tts.stop()
        }
    }

    /** Begin tap-to-talk: provision/load the speech model if needed, then transcribe one
     *  utterance and send it. Caller must already hold the RECORD_AUDIO permission. */
    fun startVoiceInput() {
        if (_voiceInput.value is VoiceInputState.Listening || _voiceInput.value is VoiceInputState.Preparing) return
        viewModelScope.launch {
            _voiceInput.value = VoiceInputState.Preparing()
            // The big STT model is a ~1.8 GB download on first use — reflect its progress so the
            // button doesn't look frozen for minutes.
            val progress = launch {
                voskSpeech.dictationProvisioning.collect { st ->
                    val msg = when (st) {
                        is dev.mascwa.pulse.jarvis.voice.SttModelStore.State.Downloading -> "downloading voice model ${st.pct}%"
                        is dev.mascwa.pulse.jarvis.voice.SttModelStore.State.Unpacking -> "unpacking voice model…"
                        else -> ""
                    }
                    if (_voiceInput.value is VoiceInputState.Preparing) _voiceInput.value = VoiceInputState.Preparing(msg)
                }
            }
            // Tap-to-talk uses the accurate full model (downloaded on first use).
            val ready = voskSpeech.ensureDictationModel()
            progress.cancel()
            if (!ready) {
                _voiceInput.value = VoiceInputState.Error("Voice model unavailable.")
                return@launch
            }
            _voiceInput.value = VoiceInputState.Listening("")
            // Dispatchers.Main (non-immediate) so stop()/send() are POSTED off Vosk's callback
            // frame — stop() joins the recognizer thread and must not run inside the callback.
            val started = voskSpeech.start(dictation = true, timeoutMs = TAP_TO_TALK_TIMEOUT_MS, listener = object : VoskListener {
                override fun onPartial(text: String) {
                    _voiceInput.value = VoiceInputState.Listening(text)
                }
                override fun onFinal(text: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        voskSpeech.stop()
                        _voiceInput.value = VoiceInputState.Idle
                        if (text.isNotBlank()) send(text)
                    }
                }
                override fun onError(message: String) {
                    viewModelScope.launch(Dispatchers.Main) {
                        voskSpeech.stop()
                        _voiceInput.value = VoiceInputState.Error(message)
                    }
                }
                override fun onTimeout() {
                    viewModelScope.launch(Dispatchers.Main) {
                        voskSpeech.stop()
                        _voiceInput.value = VoiceInputState.Idle
                    }
                }
            })
            if (!started) _voiceInput.value = VoiceInputState.Error("Couldn't start the microphone.")
        }
    }

    fun stopVoiceInput() {
        voskSpeech.stop()
        _voiceInput.value = VoiceInputState.Idle
    }

    /** Claim the shared mic while the console is on-screen so the resident wake loop pauses;
     *  release it on exit (also stopping any tap-to-talk) so the wake word auto-resumes. */
    fun setConsoleActive(active: Boolean) {
        voskSpeech.consoleActive.value = active
        if (!active) stopVoiceInput()
    }

    /** Approve a staged self-code change from the console: opens the PR via the same ApprovalGate the
     *  Approvals screen uses (the only code that performs the change), then reports the result. */
    fun approveCode(action: dev.mascwa.pulse.data.selfedit.PendingAction) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                sayJarvis(approvalGate.apply(action))
            } catch (e: Throwable) {
                memory.append(Speaker.JARVIS, "// self-code fault: ${e.message ?: "error"}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Reject a staged self-code change from the console (nothing is pushed). */
    fun rejectCode(action: dev.mascwa.pulse.data.selfedit.PendingAction) {
        viewModelScope.launch {
            runCatching { approvalGate.reject(action.id) }
            sayJarvis("Dropped that change, sir.")
        }
    }

    fun clearHistory() {
        viewModelScope.launch { memory.clearHistory() }
    }

    override fun onCleared() {
        // Only release the mic if WE were tap-to-talk listening — the shared recognizer may be
        // serving the resident wake word, which must keep running when the chat screen closes.
        val active = _voiceInput.value is VoiceInputState.Listening || _voiceInput.value is VoiceInputState.Preparing
        if (active) voskSpeech.stop()
        super.onCleared()
    }

    private companion object {
        const val HISTORY_TURNS = 12
        const val TAP_TO_TALK_TIMEOUT_MS = 10_000
    }
}
