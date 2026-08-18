package dev.mascwa.pulse.feature.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The speaker, borrowed for the length of a spoken reply.
 *
 * The app has three things that make sound: the radio, live video, and the computer's own voice.
 * [MediaFloor] arbitrates the first two — they are exclusive, and one starting means the other
 * stops. Speech is the odd one out and needs the opposite treatment: a reply is one or two sentences
 * long, so stopping the radio for it would be worse than the problem. It should **duck**.
 *
 * ⚠️ Before this, nothing in the app requested audio focus at all — the two players get theirs from
 * media3 internally, and `TextToSpeech` requests none on your behalf (that has always been the
 * caller's job). So the computer answered at full volume straight over whatever was playing, and
 * neither one got quieter.
 *
 * Two things follow from holding focus properly, and the second matters more than the first:
 *
 * 1. Other audio ducks for the length of the reply and comes back afterwards.
 * 2. **A phone call takes focus back**, and [onLost] stops the utterance. Before, the computer would
 *    have carried on talking over a ringing call.
 *
 * Best-effort throughout: a denied request never silences the assistant. The worst case is the
 * behaviour that exists today, which is why this can improve things or do nothing but cannot make
 * them worse.
 */
class SpeechFocus(context: Context, private val onLost: () -> Unit) {

    private val audio: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    private val held = AtomicBoolean(false)

    /**
     * ⚠️ Built once and reused, because abandoning focus requires **the same request object** that
     * asked for it. A fresh one per utterance would leave the old entry on the system's focus stack
     * with nothing able to release it.
     */
    private val request: AudioFocusRequest? = runCatching {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SPEECH_ATTRIBUTES)
            // Ducking is the system's job, not ours — we do not pause for it.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                // Only a real loss. A duck is something happening TO other audio, not to us, and
                // AUDIOFOCUS_GAIN is us getting it back — neither should stop a reply mid-sentence.
                if (change == AudioManager.AUDIOFOCUS_LOSS ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    runCatching { onLost() }
                }
            }
            .build()
    }.getOrNull()

    /** Ask for the speaker. Idempotent — a reply that replaces another keeps the focus already held. */
    fun acquire() {
        val req = request ?: return
        if (!held.compareAndSet(false, true)) return
        runCatching { audio?.requestAudioFocus(req) }
    }

    /** Give it back. Idempotent, so every path that ends an utterance can call it unconditionally. */
    fun release() {
        val req = request ?: return
        if (!held.compareAndSet(true, false)) return
        runCatching { audio?.abandonAudioFocusRequest(req) }
    }

    companion object {
        /**
         * What the computer's voice is, as far as the system is concerned.
         *
         * Also set on the engine itself, so the speech is tagged as assistant speech rather than
         * media — which is what a car head unit or a hearing aid reads to decide how to treat it.
         * Volume routing is unchanged: `USAGE_ASSISTANT` sits on the music stream like the default.
         */
        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
