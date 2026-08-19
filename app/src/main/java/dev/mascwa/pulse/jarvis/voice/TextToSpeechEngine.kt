package dev.mascwa.pulse.jarvis.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import dev.mascwa.pulse.feature.media.SpeechFocus
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    /**
     * Fired (once, on the main thread) when the current utterance finishes, fails, or is given up on.
     *
     * Atomic because there are now two things that can fire it — the engine and [watchdog] — and the
     * callback re-arms a microphone. Firing it twice is not something to leave to luck.
     */
    private val pendingDone = AtomicReference<(() -> Unit)?>(null)

    /** The backstop that runs [pendingDone] if the engine never calls back at all. */
    @Volatile private var watchdog: Runnable? = null

    /**
     * The speaker, borrowed for the length of an utterance so other audio ducks and a phone call can
     * take it back. Held here rather than at the call sites because [speak] and [fireDone] are
     * already the one place that owns an utterance's lifetime.
     */
    private val focus = SpeechFocus(context) { stop() }

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
        val cb = pendingDone.getAndSet(null) ?: return
        watchdog?.let(mainHandler::removeCallbacks)
        watchdog = null
        // Give the speaker back here rather than at each call site: every path that ends an
        // utterance — the engine, an error, the watchdog, stop(), shutdown() — already funnels
        // through this one method, which is exactly why the watchdog is cancelled here too.
        focus.release()
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
        // Tag the speech as what it is. A car head unit, a hearing aid or a Bluetooth stack reads
        // this to decide how to treat the stream; untagged, an assistant reply is indistinguishable
        // from music. Volume routing does not change — USAGE_ASSISTANT sits on the music stream.
        runCatching { e.setAudioAttributes(SpeechFocus.SPEECH_ATTRIBUTES) }
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
     * True while an utterance is outstanding — i.e. the computer is talking.
     *
     * Read from [pendingDone], which is set the instant before the engine is asked to speak and
     * cleared by [fireDone] on every path that ends an utterance, so it brackets the speaking window
     * exactly. Deliberately not the platform's `TextToSpeech.isSpeaking`: that needs the engine bound
     * to answer, and the point of asking is usually to avoid touching it.
     */
    val isSpeaking: Boolean get() = pendingDone.get() != null

    /**
     * Speak [text], replacing anything already being spoken. [onDone] runs (on the main thread) once
     * the computer has stopped talking — or immediately if there's nothing to speak / no engine, so
     * callers can safely sequence after speech (e.g. reopen the mic only once it has stopped).
     *
     * ⚠️ **"Once it has stopped talking", not "once this utterance finishes"**, and the difference is
     * the whole point: if a later [speak] replaces this one, [onDone] waits for *that* one instead of
     * being dropped. A caller waiting on speech is never left waiting forever, and the microphone is
     * never re-opened over an utterance that is still playing.
     */
    fun speak(text: String, onDone: () -> Unit = {}) {
        val trimmed = forSpeech(text)
        if (trimmed.isEmpty() || !ready.get()) { onDone(); return }
        val spoken = trimmed.take(MAX_LEN)
        // ⚠️ **Chain the callback being replaced; never drop it.** This `set` used to overwrite
        // whatever was pending, and that was the callback's last moment of existence: `QUEUE_FLUSH`
        // ends the previous utterance, the platform reports *that* through `onStop`, and
        // `UtteranceProgressListener.onStop` compiles to a bare `return` — read out of the platform
        // bytecode, not recalled. This listener does not override it, so no `onDone`/`onError` ever
        // arrives for the flushed utterance and nothing downstream would have run it.
        //
        // It matters because the one caller that passes a real callback is the voice service's
        // `speakThen`, which moves the arbiter into SPEAKING *before* speaking and relies on the
        // callback to bring it back out. Losing it strands the arbiter: the wake word never re-arms
        // and the phone silently stops answering to its name until the service is restarted — the
        // exact failure [armWatchdog] exists to prevent, by a route the watchdog cannot see, because
        // a callback *did* run, just not that one. [stop] and [shutdown] were already safe; they go
        // through [fireDone]. Replacement was the one path that leaked, which is why the fix for the
        // battery warning had to be a `!capturing` guard at that call site — this is the same defect
        // at its root, where the console reply, a proactive remark and the Setup voice sample are
        // all equally exposed.
        //
        // Chained rather than run here: the caller is waiting for the computer to *stop talking*,
        // and it has not — a replacement is about to start, and re-arming the microphone over it is
        // precisely what the watchdog is deliberately oversized to avoid. Last-in-first-out, so the
        // newer caller's sequencing completes before the older one resumes. Re-arming twice is
        // harmless: `VoiceMachine` is idempotent by construction. The chain can only grow while
        // utterances are replaced back-to-back with no completion in between, and it collapses on
        // the first one that finishes.
        pendingDone.getAndUpdate { displaced ->
            val chained: () -> Unit = { onDone(); displaced?.invoke() }
            chained
        }
        armWatchdog(spoken.length)
        // Before the sound starts, so the duck is already in place rather than arriving a beat late.
        focus.acquire()
        val res = engine?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        if (res != TextToSpeech.SUCCESS) fireDone()
    }

    /**
     * Guarantee [speak]'s callback runs even if the engine never reports the utterance at all.
     *
     * `speak()` returning anything but SUCCESS is the *synchronous* failure and was already handled.
     * The asynchronous one is not: an engine that dies or is swapped out mid-utterance can deliver
     * neither `onDone` nor `onError`, and then the callback never runs. That callback is what re-arms
     * the wake word, so the failure mode is a phone that silently stops answering to its name, with
     * nothing on screen to say so and nothing short of restarting the service to recover it.
     *
     * Sized as a **backstop, not an estimate** — deliberately several times the real duration, since
     * firing early would re-open the microphone while the computer is still talking, and it would
     * then hear itself. Speech that finishes normally cancels this long before it comes due.
     */
    private fun armWatchdog(chars: Int) {
        watchdog?.let(mainHandler::removeCallbacks)
        val r = Runnable {
            if (pendingDone.get() != null) {
                Log.w(TAG, "TTS never reported the utterance finishing — running the callback anyway")
                fireDone()
            }
        }
        watchdog = r
        mainHandler.postDelayed(
            r,
            (WATCHDOG_FLOOR_MS + chars * WATCHDOG_PER_CHAR_MS).coerceAtMost(WATCHDOG_MAX_MS),
        )
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

    /**
     * Stop the current utterance immediately.
     *
     * Fires any pending completion callback rather than leaving it for the watchdog: the utterance
     * is over either way, and a caller waiting on it should not learn that a minute later.
     */
    fun stop() {
        runCatching { engine?.stop() }
        fireDone()
    }

    /** Release the engine (e.g. on full app shutdown). */
    fun shutdown() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        fireDone()
        engine = null
        ready.set(false)
    }

    private companion object {
        const val TAG = "JarvisTts"
        const val UTTERANCE_ID = "jarvis"
        // TextToSpeech.getMaxSpeechInputLength() is ~4000; stay comfortably under it.
        const val MAX_LEN = 3500

        // Backstop for a TTS engine that never calls back. Generous on purpose: ~7 characters per
        // second is less than half of ordinary speech, so a real utterance always beats it.
        private const val WATCHDOG_FLOOR_MS = 4_000L
        private const val WATCHDOG_PER_CHAR_MS = 140L
        private const val WATCHDOG_MAX_MS = 120_000L
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
