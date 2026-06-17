package dev.mascwa.pulse.jarvis.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
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
    @Volatile private var engine: TextToSpeech? = null

    init {
        val appContext = context.applicationContext
        engine = TextToSpeech(appContext) { status ->
            val e = engine
            ready.set(status == TextToSpeech.SUCCESS && e != null && configure(e))
        }
    }

    /** Give J.A.R.V.I.S. a British voice and a calm, measured cadence. Falls back to US English (and
     *  the default voice) on devices without en-GB data, so we still speak everywhere. */
    private fun configure(e: TextToSpeech): Boolean {
        val gb = e.setLanguage(Locale.UK)
        if (gb == TextToSpeech.LANG_MISSING_DATA || gb == TextToSpeech.LANG_NOT_SUPPORTED) {
            val us = e.setLanguage(Locale.US)
            if (us == TextToSpeech.LANG_MISSING_DATA || us == TextToSpeech.LANG_NOT_SUPPORTED) return false
        }
        runCatching { pickBritishVoice(e)?.let { e.voice = it } }
        e.setPitch(JARVIS_PITCH)
        e.setSpeechRate(JARVIS_RATE)
        return true
    }

    /** Prefer an on-device en-GB voice, leaning male/high-quality where the engine exposes a hint. */
    private fun pickBritishVoice(e: TextToSpeech): Voice? {
        val gb = runCatching { e.voices }.getOrNull()
            ?.filter { !it.isNetworkConnectionRequired && it.locale?.language == "en" && it.locale?.country == "GB" }
            ?.takeIf { it.isNotEmpty() } ?: return null
        return gb.firstOrNull { v -> MALE_HINTS.any { v.name.contains(it, ignoreCase = true) } }
            ?: gb.maxByOrNull { it.quality }
            ?: gb.first()
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
        // Calm, faintly authoritative butler: a touch below neutral pitch, a shade slower than default.
        const val JARVIS_PITCH = 0.96f
        const val JARVIS_RATE = 0.95f
        // Engine-specific name fragments that tend to denote a male en-GB voice.
        val MALE_HINTS = listOf("gbb", "gbd", "male", "#male")
    }
}
