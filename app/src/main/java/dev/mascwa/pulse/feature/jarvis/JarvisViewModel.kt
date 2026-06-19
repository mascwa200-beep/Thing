package dev.mascwa.pulse.feature.jarvis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.withContext
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
    private val usage: dev.mascwa.pulse.data.usage.UsageRepository,
    private val cerebellum: dev.mascwa.pulse.data.cerebellum.CerebellumStore,
    private val profile: dev.mascwa.pulse.data.profile.ProfileStore,
    private val taskStore: dev.mascwa.pulse.data.tasks.TaskStore,
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
            logChat("chat-in", text)
            // Always-on profile capture: keep a clear self-declaration (preference/interest/project).
            profile.detectAndAdd(text)
            // Always-on task capture: track a clear self-assigned task ("I need to …", "todo: …").
            taskStore.detectAndCapture(text)
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

    /** Analyze a picked image with J.A.R.V.I.S. (cloud vision). [caption] is the optional question typed
     *  alongside it. Cloud-only — the on-device model can't see images. */
    fun sendImage(context: Context, uri: Uri, caption: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _streaming.value = ""
            val cap = caption.trim()
            memory.append(Speaker.USER, if (cap.isBlank()) "📎 [image]" else "📎 [image] $cap")
            try {
                val dataUrl = encodeImage(context, uri)
                if (dataUrl == null) sayJarvis("I couldn't read that image, sir.")
                else analyzeImages(cap.ifBlank { "Describe and analyze this image in detail, sir." }, listOf(dataUrl))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                memory.append(Speaker.JARVIS, "// image fault: ${e.message ?: "error"}")
            } finally {
                _streaming.value = ""
                _busy.value = false
            }
        }
    }

    /** Analyze a picked file: images & PDFs go to the vision model (PDFs rendered to page images),
     *  text/code/CSV/JSON are read and reasoned over. [caption] is the optional question. */
    fun sendFile(context: Context, uri: Uri, caption: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _streaming.value = ""
            val cap = caption.trim()
            val name = fileName(context, uri)
            memory.append(Speaker.USER, "📎 [file${name?.let { ": $it" } ?: ""}]" + if (cap.isBlank()) "" else " $cap")
            try {
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty().lowercase()
                val prompt = cap.ifBlank { "Interpret and summarize this file, sir." }
                when {
                    mime.startsWith("image/") -> {
                        val d = encodeImage(context, uri)
                        if (d == null) sayJarvis("I couldn't read that image, sir.") else analyzeImages(prompt, listOf(d))
                    }
                    mime == "application/pdf" || name?.endsWith(".pdf", true) == true -> {
                        val pages = renderPdf(context, uri)
                        if (pages.isEmpty()) sayJarvis("I couldn't read that PDF, sir.") else analyzeImages(prompt, pages)
                    }
                    isTextual(mime, name) -> {
                        val text = readTextFile(context, uri)
                        if (text.isNullOrBlank()) {
                            sayJarvis("That file looks empty or unreadable, sir.")
                        } else {
                            runCatching { engine.ensureReady() }
                            val full = "$prompt\n\nFile \"${name ?: "document"}\" contents:\n${text.take(MAX_FILE_CHARS)}"
                            val sb = StringBuilder()
                            engine.generate(full, emptyList(), withMemory(composePersona(), prompt)).collect { tok ->
                                sb.append(tok)
                                _streaming.value = sb.toString()
                            }
                            val reply = sb.toString().ifBlank { "…" }
                            memory.append(Speaker.JARVIS, reply)
                            speakIfEnabled(reply)
                        }
                    }
                    else -> sayJarvis("I can't read that file type, sir — try an image, a PDF, or a text/code file.")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                memory.append(Speaker.JARVIS, "// file fault: ${e.message ?: "error"}")
            } finally {
                _streaming.value = ""
                _busy.value = false
            }
        }
    }

    /** Stream a vision analysis of [dataUrls] into the chat (caller owns _busy). Cloud-only. */
    private suspend fun analyzeImages(prompt: String, dataUrls: List<String>) {
        val vision = engine as? dev.mascwa.pulse.jarvis.inference.VisionEngine
        if (vision == null || !runCatching { vision.supportsVision() }.getOrDefault(false)) {
            sayJarvis("I need a cloud AI key (Setup) to see images or PDFs, sir — the on-device model can't.")
            return
        }
        if (dataUrls.isEmpty()) { sayJarvis("Nothing to analyze, sir."); return }
        val sb = StringBuilder()
        vision.generateWithImages(prompt, dataUrls, withMemory(composePersona(), prompt)).collect { tok ->
            sb.append(tok)
            _streaming.value = sb.toString()
        }
        val reply = sb.toString().ifBlank { "…" }
        memory.append(Speaker.JARVIS, reply)
        speakIfEnabled(reply)
    }

    /** Read [uri], downscale, and JPEG-encode it as a base64 data URL for the vision API. */
    private suspend fun encodeImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            encodeBitmap(downscale(bmp))
        }.getOrNull()
    }

    /** Render up to [MAX_PDF_PAGES] PDF pages to page images (data URLs) for the vision model. */
    private suspend fun renderPdf(context: Context, uri: Uri): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val out = ArrayList<String>()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                try {
                    val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
                    for (i in 0 until pages) {
                        val page = renderer.openPage(i)
                        try {
                            val scale = MAX_IMAGE_PX.toFloat() / maxOf(page.width, page.height).coerceAtLeast(1)
                            val w = (page.width * scale).toInt().coerceAtLeast(1)
                            val h = (page.height * scale).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE) // PDFs render onto transparency
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            out.add(encodeBitmap(bmp))
                        } finally {
                            page.close()
                        }
                    }
                } finally {
                    renderer.close()
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private suspend fun readTextFile(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrNull()
    }

    private fun fileName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun isTextual(mime: String, name: String?): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in TEXT_MIMES) return true
        val n = name?.lowercase() ?: return false
        return TEXT_EXTS.any { n.endsWith(it) }
    }

    private fun downscale(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= MAX_IMAGE_PX) return bmp
        val scale = MAX_IMAGE_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    }

    private fun encodeBitmap(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
                // Self-coding / self-edit live only inside the agent loop, so enabling either implies the
                // tool loop even if "Agent Tools" itself is off — otherwise J.A.R.V.I.S. has no tools and
                // wrongly insists it can't read or change its own code.
                val jcfg = runCatching { settings.current().jarvis }.getOrNull()
                val useAgent = jcfg != null &&
                    (jcfg.agentToolsEnabled || jcfg.selfCodingEnabled || jcfg.selfEditEnabled)
                val reply = if (useAgent) generateWithAgent(intent.text) else generateDirect(intent.text)
                memory.append(Speaker.JARVIS, reply)
                logChat("chat-out", reply)
                // Cerebellum: learn whether the agent vs. direct path reliably handles requests like this.
                val sig = dev.mascwa.pulse.core.telemetry.Cerebellum.signature(intent.text)
                if (sig.isNotBlank()) {
                    val ok = reply.isNotBlank() && !reply.trimStart().startsWith("//")
                    cerebellum.observe("req:$sig", if (useAgent) "agent" else "direct", ok)
                }
                speakIfEnabled(reply)
                maybeBeCurious()
            }
        }
    }

    /** Append a J.A.R.V.I.S. line to history and speak it if voice replies are on. */
    private suspend fun sayJarvis(text: String) {
        memory.append(Speaker.JARVIS, text)
        logChat("chat-out", text)
        speakIfEnabled(text)
    }

    /** Record a chat turn to the on-device activity log: full text when detailed logging is on,
     *  otherwise a content-free marker so the timeline still shows a turn happened. Credentials are
     *  scrubbed downstream by [UsageRepository]. */
    private suspend fun logChat(direction: String, text: String) {
        val verbose = runCatching { settings.current().jarvis.verboseActivityLog }.getOrDefault(true)
        usage.log(direction, if (verbose) text else "(message)")
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

    /** The live system prompt: the user's charter (or built-in persona) + the immutable safety addendum,
     *  plus the always-on user-profile digest (durable preferences/interests/projects) so tailoring is
     *  proactive on every turn, not gated on a keyword recall. */
    private suspend fun composePersona(): String {
        val base = JarvisPersona.compose(runCatching { selfEdit.current().charter }.getOrDefault(""))
        var prompt = base
        val digest = runCatching { profile.digest() }.getOrDefault("")
        if (digest.isNotBlank()) {
            prompt += "\n\nThe user's profile (tailor your help to it; keep it current via the `profile` tool):\n" + digest
        }
        val tasks = runCatching { taskStore.digest() }.getOrDefault("")
        if (tasks.isNotBlank()) {
            prompt += "\n\nThe user's open tasks you're tracking (follow up proactively; keep current via the `task` tool):\n" + tasks
        }
        return prompt
    }

    /** Run the bounded agentic loop, surfacing tool/reasoning steps in the streaming line. Recent
     *  conversation is threaded in so the agent keeps short-term context (the durable-memory recall is
     *  injected inside the loop separately). */
    private suspend fun generateWithAgent(text: String): String {
        val recent = memory.recentContext(HISTORY_TURNS).map { ChatTurn(it.speaker, it.messageText) }
        // Drop the just-appended current user turn — it's already the agent's query.
        val history = if (recent.lastOrNull()?.let { it.role.equals("user", true) && it.text == text } == true) {
            recent.dropLast(1)
        } else {
            recent
        }
        val sb = StringBuilder()
        agent.run(text, composePersona(), history).collect { step ->
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
        // Downscale the long edge of an uploaded image before sending to the vision API (token/cost cap).
        const val MAX_IMAGE_PX = 1024
        // PDF: render at most this many pages to the vision model (cost cap).
        const val MAX_PDF_PAGES = 3
        // Text file: characters of content fed to the model (cost cap).
        const val MAX_FILE_CHARS = 30_000
        val TEXT_MIMES = setOf(
            "application/json", "application/xml", "application/csv", "application/x-yaml",
            "application/javascript", "application/x-sh", "application/sql",
        )
        val TEXT_EXTS = listOf(
            ".txt", ".md", ".json", ".csv", ".tsv", ".xml", ".yml", ".yaml", ".log", ".ini", ".toml",
            ".kt", ".java", ".py", ".js", ".ts", ".c", ".cpp", ".h", ".cs", ".go", ".rs", ".rb", ".php",
            ".html", ".css", ".sh", ".gradle", ".properties", ".sql",
        )
    }
}
