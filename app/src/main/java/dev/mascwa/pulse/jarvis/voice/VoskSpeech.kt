package dev.mascwa.pulse.jarvis.voice

import android.content.Context
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
    private val store = SttModelStore(appContext)

    @Volatile private var model: Model? = null
    @Volatile private var speechService: SpeechService? = null

    /** Provisioning progress for the underlying speech model (download / unpack). */
    val provisioning: StateFlow<SttModelStore.State> = store.state

    /** True while the J.A.R.V.I.S. console owns the mic for tap-to-talk. The resident wake loop
     *  observes this and pauses, so the two never fight over the single shared recognizer. */
    val consoleActive = MutableStateFlow(false)

    val isModelReady: Boolean get() = model != null

    /** Download (if needed) and load the model. Safe to call repeatedly; returns success. */
    suspend fun ensureModel(): Boolean {
        if (model != null) return true
        val dir = store.ensure().getOrNull() ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { Model(dir.absolutePath) }.onSuccess { model = it }.isSuccess
        }
    }

    /**
     * Begin streaming recognition. [grammar] — a JSON array string like `["jarvis","[unk]"]` —
     * constrains the vocabulary for keyword spotting; null means free-form transcription.
     * [timeoutMs] > 0 ends the session and fires [VoskListener.onTimeout] after that idle window
     * (0 = listen indefinitely). Returns false if the model isn't loaded or the mic can't open.
     */
    fun start(grammar: String? = null, timeoutMs: Int = 0, listener: VoskListener): Boolean {
        val m = model ?: return false
        stop()
        return runCatching {
            val recognizer = if (grammar != null) Recognizer(m, SAMPLE_RATE, grammar) else Recognizer(m, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            if (timeoutMs > 0) service.startListening(adapter(listener), timeoutMs) else service.startListening(adapter(listener))
            speechService = service
            true
        }.getOrElse {
            listener.onError(it.message ?: "Microphone unavailable.")
            false
        }
    }

    /** Stop the active recognition session (releases the mic). */
    fun stop() {
        runCatching { speechService?.stop() }
        speechService = null
    }

    /** Release the model and any session (e.g. on full app shutdown). */
    fun shutdown() {
        stop()
        runCatching { model?.close() }
        model = null
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
    }
}
