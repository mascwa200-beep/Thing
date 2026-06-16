package dev.mascwa.pulse.jarvis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around the AOSP [TextToSpeech] engine so J.A.R.V.I.S. can speak replies
 * fully on-device — no Google Play Services. Safe to use before initialization finishes
 * (requests are dropped until the engine reports ready) and an honest no-op when the
 * device has no TTS engine installed at all, which is common on de-Googled / GrapheneOS
 * builds. Lives for the app's lifetime; created on the main thread via the DI container.
 */
class TextToSpeechEngine(context: Context) {

    private val ready = AtomicBoolean(false)
    private var engine: TextToSpeech? = null

    init {
        val appContext = context.applicationContext
        engine = TextToSpeech(appContext) { status ->
            val e = engine
            ready.set(
                status == TextToSpeech.SUCCESS &&
                    e != null &&
                    e.setLanguage(Locale.US).let {
                        it != TextToSpeech.LANG_MISSING_DATA && it != TextToSpeech.LANG_NOT_SUPPORTED
                    },
            )
        }
    }

    /** True once an engine is bound and an English voice is available. */
    val isAvailable: Boolean get() = ready.get()

    /** Speak [text], replacing anything already being spoken. No-op if unavailable. */
    fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !ready.get()) return
        engine?.speak(trimmed.take(MAX_LEN), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    /** Stop the current utterance immediately. */
    fun stop() {
        runCatching { engine?.stop() }
    }

    /** Release the engine (e.g. on full app shutdown). */
    fun shutdown() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        ready.set(false)
    }

    private companion object {
        const val UTTERANCE_ID = "jarvis"
        // TextToSpeech.getMaxSpeechInputLength() is ~4000; stay comfortably under it.
        const val MAX_LEN = 3500
    }
}
