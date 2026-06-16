package dev.mascwa.pulse.jarvis.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Streaming-recognition callbacks (delivered on Vosk's worker thread). */
interface VoskListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(message: String)
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
     * Returns false if the model isn't loaded or the microphone can't be opened.
     */
    fun start(grammar: String? = null, listener: VoskListener): Boolean {
        val m = model ?: return false
        stop()
        return runCatching {
            val recognizer = if (grammar != null) Recognizer(m, SAMPLE_RATE, grammar) else Recognizer(m, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            service.startListening(adapter(listener))
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
            jsonField(hypothesis, "text")?.takeIf { it.isNotBlank() }?.let(listener::onFinal)
        }
        override fun onFinalResult(hypothesis: String?) {
            jsonField(hypothesis, "text")?.takeIf { it.isNotBlank() }?.let(listener::onFinal)
        }
        override fun onError(exception: Exception?) {
            listener.onError(exception?.message ?: "Recognition error.")
        }
        override fun onTimeout() { /* caller decides when to stop */ }
    }

    private fun jsonField(hypothesis: String?, key: String): String? =
        hypothesis?.let { runCatching { JSONObject(it).optString(key) }.getOrNull() }

    private companion object {
        const val SAMPLE_RATE = 16000f
    }
}
