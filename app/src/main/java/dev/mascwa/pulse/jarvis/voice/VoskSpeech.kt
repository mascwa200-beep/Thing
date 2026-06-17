package dev.mascwa.pulse.jarvis.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Streaming-recognition callbacks (delivered on the main looper by Vosk's SpeechService). */
interface VoskListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(message: String)
    /** Fires when a non-zero recognition timeout elapses with no final result. */
    fun onTimeout() {}
}

/**
 * Offline speech recognition via Vosk — fully on-device, no Play Services, no account, and no
 * network at recognition time. Owns the heavy [Model] (loaded once) and at most one active
 * [SpeechService]. Used for both tap-to-talk transcription and, with a keyword grammar, the
 * wake word. Audio is processed locally and never recorded or transmitted.
 */
class VoskSpeech(context: Context) {

    private val appContext = context.applicationContext
    // Two tiers: a small model for the always-on wake word (light, reliable) and the full model for
    // accurate tap-to-talk dictation (downloaded on first use of the chat mic).
    private val wakeStore = SttModelStore(appContext, SttModelStore.SMALL)
    private val dictationStore = SttModelStore(appContext, SttModelStore.FULL)

    @Volatile private var wakeModel: Model? = null
    @Volatile private var dictationModel: Model? = null
    @Volatile private var speechService: SpeechService? = null

    /** Provisioning progress for the small wake model (shown in the resident notification). */
    val wakeProvisioning: StateFlow<SttModelStore.State> = wakeStore.state
    /** Provisioning progress for the full dictation model (shown in tap-to-talk). */
    val dictationProvisioning: StateFlow<SttModelStore.State> = dictationStore.state

    /** True while the J.A.R.V.I.S. console owns the mic for tap-to-talk. The resident wake loop
     *  observes this and pauses, so the two never fight over the single shared recognizer. */
    val consoleActive = MutableStateFlow(false)

    val isWakeModelReady: Boolean get() = wakeModel != null
    val isDictationModelReady: Boolean get() = dictationModel != null

    init {
        // Reclaim space from the previous single-model layout (jarvis/stt/<model> directly). Off the
        // calling thread — this can delete up to ~1.8 GB and must not block construction.
        Thread {
            runCatching {
                File(appContext.filesDir, "jarvis/stt").listFiles()
                    ?.forEach { if (it.name != "small" && it.name != "full") it.deleteRecursively() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Download (if needed) and load the small wake model. Safe to call repeatedly. */
    suspend fun ensureWakeModel(): Boolean = ensure(wakeStore, wake = true)

    /** Download (if needed) and load the full dictation model. Safe to call repeatedly. */
    suspend fun ensureDictationModel(): Boolean = ensure(dictationStore, wake = false)

    private suspend fun ensure(store: SttModelStore, wake: Boolean): Boolean {
        val tag = if (wake) "wake" else "dictation"
        if ((if (wake) wakeModel else dictationModel) != null) return true
        Log.i(TAG, "ensureModel($tag): provisioning…")
        val dir = store.ensure().getOrElse {
            Log.e(TAG, "ensureModel($tag): download/unpack failed", it)
            return false
        }
        Log.i(TAG, "ensureModel($tag): present at ${dir.absolutePath}; loading…")
        return withContext(Dispatchers.IO) {
            runCatching { Model(dir.absolutePath) }
                .onSuccess {
                    if (wake) wakeModel = it else dictationModel = it
                    Log.i(TAG, "ensureModel($tag): loaded OK")
                }
                .onFailure { Log.e(TAG, "ensureModel($tag): Model() load failed (corrupt/too large?)", it) }
                .isSuccess
        }
    }

    /**
     * Begin streaming recognition with the chosen tier ([dictation] = full model, else the small wake
     * model). [grammar] — a JSON array string like `["jarvis","[unk]"]` — constrains the vocabulary
     * for keyword spotting; null means free-form transcription. [timeoutMs] > 0 ends the session and
     * fires [VoskListener.onTimeout] after that idle window (0 = listen indefinitely). Returns false
     * if the requested model isn't loaded or the mic can't open.
     */
    fun start(dictation: Boolean, grammar: String? = null, timeoutMs: Int = 0, listener: VoskListener): Boolean {
        val m = if (dictation) dictationModel else wakeModel
        if (m == null) {
            Log.w(TAG, "start: ${if (dictation) "dictation" else "wake"} model not loaded — call ensure first")
            return false
        }
        stop()
        return runCatching {
            // A static (large) model doesn't support Vosk's runtime grammar constructor; if it throws,
            // fall back to free-form recognition so wake spotting still works (the caller matches the
            // wake word in the transcript). Dynamic models keep using the grammar for tighter spotting.
            val recognizer = if (grammar != null) {
                runCatching { Recognizer(m, SAMPLE_RATE, grammar) }.getOrElse { Recognizer(m, SAMPLE_RATE) }
            } else {
                Recognizer(m, SAMPLE_RATE)
            }
            val service = SpeechService(recognizer, SAMPLE_RATE)
            if (timeoutMs > 0) service.startListening(adapter(listener), timeoutMs) else service.startListening(adapter(listener))
            speechService = service
            Log.i(TAG, "start: listening (grammar=${grammar != null}, timeoutMs=$timeoutMs)")
            true
        }.getOrElse {
            // Most often: AudioRecord couldn't open the mic (permission, another app holding it, or
            // no foreground-mic). Surface it instead of failing silently.
            Log.e(TAG, "start: couldn't open the microphone / recognizer", it)
            listener.onError(it.message ?: "Microphone unavailable.")
            false
        }
    }

    /** Stop the active recognition session (releases the mic). */
    fun stop() {
        runCatching { speechService?.stop() }
        speechService = null
    }

    /** Release both models and any session (e.g. on full app shutdown). */
    fun shutdown() {
        stop()
        runCatching { wakeModel?.close() }
        runCatching { dictationModel?.close() }
        wakeModel = null
        dictationModel = null
    }

    private fun adapter(listener: VoskListener) = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            jsonField(hypothesis, "partial")?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
        }
        override fun onResult(hypothesis: String?) {
            jsonField(hypothesis, "text")?.takeIf { it.isNotBlank() }?.let { listener.onFinal(normalize(it)) }
        }
        override fun onFinalResult(hypothesis: String?) {
            jsonField(hypothesis, "text")?.takeIf { it.isNotBlank() }?.let { listener.onFinal(normalize(it)) }
        }
        override fun onError(exception: Exception?) {
            Log.w(TAG, "recognizer onError", exception)
            listener.onError(exception?.message ?: "Recognition error.")
        }
        override fun onTimeout() { listener.onTimeout() }
    }

    private fun jsonField(hypothesis: String?, key: String): String? =
        hypothesis?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }

    /** Tidy raw recognizer text: fix common "Jarvis" mishears and capitalize the first letter. */
    private fun normalize(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return t
        t = t.replace(Regex("\\b(jervis|jarvas|jarvix|javis|jarviss|jervais)\\b", RegexOption.IGNORE_CASE), "Jarvis")
        return t.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    private companion object {
        const val SAMPLE_RATE = 16000f
        const val TAG = "JarvisVoice"
    }
}
