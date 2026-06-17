package dev.mascwa.pulse.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Post-wake command transcription via Android's **on-device** Google speech recognizer. It runs in the
 * system's recognition-service process (not ours), so it adds no memory to the app or the on-device
 * LLM — and it's far more accurate than the offline Vosk model that must fit alongside the LLM.
 *
 * On-device only: audio never leaves the phone (we never use the network recognizer). When on-device
 * recognition isn't available on a device, [available] is false and the caller falls back to Vosk.
 *
 * SpeechRecognizer is single-threaded: every method here must be called on the **main thread** (the
 * resident service drives this from its `Dispatchers.Main` voice scope).
 */
class DeviceSpeechRecognizer(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var unsupported = false
    private var recognizer: SpeechRecognizer? = null
    private var timeoutRunnable: Runnable? = null

    /** Whether the on-device recognizer is usable on this device (cached-false after a hard failure). */
    val available: Boolean
        get() {
            if (unsupported) return false
            return runCatching {
                if (Build.VERSION.SDK_INT >= 33) SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
                else SpeechRecognizer.isRecognitionAvailable(appContext)
            }.getOrDefault(false)
        }

    /** Recognize one utterance on-device, delivering results via [listener]. Main thread only. */
    fun listen(timeoutMs: Int, listener: VoskListener) {
        cancel()
        val rec = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) }.getOrElse {
            Log.e(TAG, "createOnDeviceSpeechRecognizer failed", it)
            unsupported = true
            listener.onError(UNAVAILABLE)
            return
        }
        recognizer = rec
        var done = false
        fun cleanup() {
            timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
            timeoutRunnable = null
            runCatching { rec.destroy() }
            if (recognizer === rec) recognizer = null
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (done) return
                topResult(partialResults)?.takeIf { it.isNotBlank() }?.let(listener::onPartial)
            }
            override fun onResults(results: Bundle?) {
                if (done) return
                done = true
                val text = topResult(results).orEmpty()
                Log.i(TAG, "on-device result: \"$text\"")
                cleanup()
                if (text.isBlank()) listener.onTimeout() else listener.onFinal(text)
            }
            override fun onError(error: Int) {
                if (done) return
                done = true
                Log.w(TAG, "on-device error code=$error")
                cleanup()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.onTimeout()
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ->
                        { unsupported = true; listener.onError(UNAVAILABLE) }
                    else -> listener.onError("recognizer error $error")
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // belt-and-braces; this recognizer is on-device
        }
        // Safety net: if no terminal callback ever arrives, time out and let the caller re-arm.
        val r = Runnable {
            if (done) return@Runnable
            done = true
            Log.w(TAG, "on-device timeout (no callback)")
            cleanup()
            listener.onTimeout()
        }
        timeoutRunnable = r
        mainHandler.postDelayed(r, (timeoutMs + SAFETY_PAD_MS).toLong())
        runCatching { rec.startListening(intent) }.onFailure {
            if (done) return
            done = true
            Log.e(TAG, "startListening failed", it)
            cleanup()
            listener.onError("start failed")
        }
    }

    /** Abort any in-flight recognition and release the recognizer (and the mic). Main thread only. */
    fun cancel() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun topResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        /** [VoskListener.onError] message signalling on-device recognition isn't usable here. */
        const val UNAVAILABLE = "unavailable"
        private const val TAG = "JarvisVoice"
        private const val SAFETY_PAD_MS = 4000
    }
}
