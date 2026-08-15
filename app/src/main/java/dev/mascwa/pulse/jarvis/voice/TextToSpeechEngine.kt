package dev.mascwa.pulse.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One installed voice, as far as the platform will admit to knowing.
 *
 * [female] is a **guess**. Android's [Voice] exposes locale, quality and network requirement, and no
 * gender at all — the only signal is whether the engine happened to put the word in the voice's
 * internal name, which Google's do and several others do not. Treated as a hint for ordering the
 * picker, never as a fact stated to the user.
 */
data class VoiceOption(
    val name: String,
    val label: String,
    val female: Boolean,
    val quality: Int,
)

/**
 * Thin wrapper around the AOSP [TextToSpeech] engine so the computer can speak replies
 * fully on-device — no Google Play Services. Safe to use before initialization finishes
 * (requests are dropped until the engine reports ready) and an honest no-op when the
 * device has no TTS engine installed at all, which is common on de-Googled / GrapheneOS
 * builds. Lives for the app's lifetime; created on the main thread via the DI container.
 *
 * [preferredVoice] supplies the user's explicitly chosen voice name, or blank for automatic. It is a
 * lambda rather than a value because the engine outlives any one reading of settings and must be
 * able to re-select when the choice changes.
 */
class TextToSpeechEngine(
    context: Context,
    private val preferredVoice: () -> String = { "" },
) {

    private val ready = AtomicBoolean(false)
    @Volatile private var engine: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** Fired (once, on the main thread) when the current utterance finishes or fails. */
    @Volatile private var pendingDone: (() -> Unit)? = null

    init {
        val appContext = context.applicationContext
        engine = TextToSpeech(appContext) { status ->
            val e = engine
            val ok = status == TextToSpeech.SUCCESS && e != null && configure(e)
            if (ok) e?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = fireDone()
                override fun onError(utteranceId: String?) = fireDone()
            })
            ready.set(ok)
        }
    }

    /** Run and clear the completion callback on the main thread (TTS callbacks arrive off-thread). */
    private fun fireDone() {
        val cb = pendingDone ?: return
        pendingDone = null
        mainHandler.post(cb)
    }

    /** Set the language, select a voice, and set the ship's-computer cadence. */
    private fun configure(e: TextToSpeech): Boolean {
        val us = e.setLanguage(Locale.US)
        if (us == TextToSpeech.LANG_MISSING_DATA || us == TextToSpeech.LANG_NOT_SUPPORTED) {
            val gb = e.setLanguage(Locale.UK)
            if (gb == TextToSpeech.LANG_MISSING_DATA || gb == TextToSpeech.LANG_NOT_SUPPORTED) return false
        }
        selectVoice(e)
        e.setPitch(COMPUTER_PITCH)
        e.setSpeechRate(COMPUTER_RATE)
        return true
    }

    /**
     * The choice made in this process, which wins over [preferredVoice].
     *
     * It exists to close a race: picking a voice persists it and then wants to speak a sample
     * immediately, but the settings flow that feeds [preferredVoice] has not necessarily emitted yet,
     * so re-reading it would sample the *old* voice. Setting it here makes the pick take effect at
     * the moment it is made, and the later emission agrees.
     */
    @Volatile private var chosen: String? = null

    /**
     * Apply a choice now. Blank means automatic.
     *
     * The one entry point, called both when the user picks and when the persisted setting changes
     * (including a settings restore), so [chosen] always holds the latest value rather than shadowing
     * it for the life of the process. Re-selecting is cheap — no teardown, so the ~second it takes to
     * bind the engine is not paid again.
     */
    fun useVoice(name: String) {
        chosen = name
        val e = engine ?: return
        runCatching { selectVoice(e) }
    }

    /**
     * The on-device English voices, best first.
     *
     * Network voices are excluded outright: this app speaks on a phone that is regularly offline and
     * a voice that silently fails without signal is worse than a plainer one that always works.
     */
    fun voiceOptions(): List<VoiceOption> {
        val e = engine ?: return emptyList()
        return runCatching {
            englishVoices(e)
                .sortedWith(compareByDescending<Voice> { isFemaleName(it.name) }.thenByDescending { it.quality })
                .map { VoiceOption(it.name, labelFor(it), isFemaleName(it.name), it.quality) }
        }.getOrDefault(emptyList())
    }

    private fun englishVoices(e: TextToSpeech): List<Voice> =
        runCatching { e.voices }.getOrNull().orEmpty()
            .filter { !it.isNetworkConnectionRequired && it.locale?.language == "en" }

    /**
     * Apply the user's chosen voice, or pick one.
     *
     * Automatic selection leans female and American, in that order — the register this app is dressed
     * as. It is a lean and not a requirement: a device with neither still speaks, in whatever English
     * voice it has, rather than falling silent to protect a theme.
     */
    private fun selectVoice(e: TextToSpeech) {
        val all = englishVoices(e).takeIf { it.isNotEmpty() } ?: return
        val wanted = chosen ?: runCatching { preferredVoice() }.getOrDefault("")
        val chosen = all.firstOrNull { it.name == wanted }
            ?: all.filter { isFemaleName(it.name) }.bestOf()
            ?: all.bestOf()
        chosen?.let { runCatching { e.voice = it } }
    }

    /** Highest quality, preferring the American ones where quality ties. */
    private fun List<Voice>.bestOf(): Voice? =
        maxWithOrNull(compareBy<Voice> { it.quality }.thenBy { if (it.locale?.country == "US") 1 else 0 })

    /** A name a person can choose between, since the raw ones look like `en-us-x-sfg#female_1-local`. */
    private fun labelFor(v: Voice): String {
        val country = v.locale?.country.orEmpty().ifBlank { "EN" }
        val variant = v.name.substringAfter('#', "").substringBefore("-local").ifBlank {
            v.name.substringAfterLast('-').ifBlank { v.name }
        }
        return "$country · ${variant.replace('_', ' ').uppercase()}"
    }

    /**
     * Whether the engine's own name for the voice says "female".
     *
     * Google's voices do (`…#female_1-local`); Samsung's and eSpeak's do not, so a device with only
     * those will simply have no hint and fall through to quality. Deliberately not inferred from
     * anything else — guessing gender from a pitch measurement would be both unreliable and grim.
     */
    private fun isFemaleName(name: String): Boolean =
        name.contains("female", ignoreCase = true) || name.contains("fem_", ignoreCase = true)

    /** True once an engine is bound and an English voice is available. */
    val isAvailable: Boolean get() = ready.get()

    /**
     * Speak [text], replacing anything already being spoken. [onDone] runs (on the main thread) when
     * the utterance finishes — or immediately if there's nothing to speak / no engine, so callers can
     * safely sequence after speech (e.g. reopen the mic only once the computer has stopped talking).
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val trimmed = forSpeech(text)
        if (trimmed.isEmpty() || !ready.get()) { onDone(); return }
        pendingDone = onDone
        val res = engine?.speak(trimmed.take(MAX_LEN), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (res != TextToSpeech.SUCCESS) fireDone()
    }

    /** Strip Markdown markup so the engine doesn't read the symbols aloud (e.g. "**" as "star star").
     *  Keeps the words; drops emphasis/code/heading/quote/bullet markers and turns links into their text. */
    private fun forSpeech(raw: String): String {
        var t = raw
        t = t.replace(RE_CODE_FENCE, " ")   // code-fence markers
        t = t.replace("`", "")              // inline code ticks
        t = t.replace(RE_LINK, "$1")        // [text](url) -> text
        t = t.replace("*", "")              // ** bold ** and * italic *
        t = t.replace(RE_HEADING, "")       // # headings
        t = t.replace(RE_QUOTE, "")         // > block quotes
        t = t.replace(RE_BULLET, "")        // - / • bullet markers
        t = t.replace(RE_SPACES, " ")       // collapse runs of spaces
        return t.trim()
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
        // The ship's computer: level and unhurried, neither confiding nor brisk. Pitch sits a touch
        // above neutral and the rate a shade under it — the effect is announcement rather than
        // conversation. Flat affect itself is not settable: TextToSpeech exposes pitch and rate and
        // no control over intonation, so this is as close as the platform allows.
        const val COMPUTER_PITCH = 1.04f
        const val COMPUTER_RATE = 0.93f

        // Compiled once — forSpeech runs on every spoken utterance. Regex is immutable/thread-safe.
        val RE_CODE_FENCE = Regex("```[a-zA-Z0-9]*")
        val RE_LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
        val RE_HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
        val RE_QUOTE = Regex("(?m)^\\s{0,3}>\\s?")
        val RE_BULLET = Regex("(?m)^\\s{0,3}[-•]\\s+")
        val RE_SPACES = Regex("[ \\t]{2,}")
    }
}
