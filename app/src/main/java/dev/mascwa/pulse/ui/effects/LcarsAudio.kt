package dev.mascwa.pulse.ui.effects

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.PI
import kotlin.math.sin

/** Whether interface sounds are enabled (driven by Settings, beside [LocalHaptics]). */
val LocalSounds = staticCompositionLocalOf { false }

/**
 * The console's voice — short tonal blips, synthesised.
 *
 * **Nothing is sampled and no audio ships in the APK.** Every cue is a few dozen milliseconds of
 * arithmetic: a run of two or three pure tones with a quick envelope, rendered to PCM once and kept.
 * That is deliberate on two counts. The obvious one is the licence — the sounds this evokes are
 * Paramount's, and an original tone that occupies the same register is an homage where a lifted
 * waveform would be a copy. The other is that the whole set costs about sixty kilobytes of RAM and
 * zero bytes on disk, which is the standing constraint in this project.
 *
 * 22.05 kHz is ample: the highest partial here is under 2 kHz, nowhere near the 11 kHz ceiling.
 */
enum class SoundCue {
    /** A chip, a tab, a minor toggle. */
    TAP,

    /** Moving between destinations. */
    SELECT,

    /** Something completed. */
    CONFIRM,

    /** Refused, failed, or nothing to show. */
    REJECT,

    /** Condition red. Deliberately the only cue that is unpleasant. */
    ALERT,
}

/**
 * Renders and plays the cues.
 *
 * Each cue is synthesised and written to its own track on first use, so playing one afterwards costs
 * a rewind and a start — no synthesis, no buffer write. Every path is defensive: a device that
 * refuses to give us an [AudioTrack] simply gets no sound, never an exception on the UI thread.
 */
class LcarsAudio {

    /**
     * One track per cue, each sized to exactly that cue.
     *
     * ⚠️ Not one shared track sized to the longest cue, which is what this was. A static track plays
     * its whole buffer, so writing a 60 ms tap into a buffer still sized for the 380 ms alert plays
     * the tap and then whatever of the alert was left behind it. Per-cue tracks also mean the PCM is
     * written once rather than on every play.
     */
    private val tracks = HashMap<SoundCue, AudioTrack>()

    fun play(cue: SoundCue) {
        runCatching {
            val t = tracks[cue] ?: build(cue)?.also { tracks[cue] = it } ?: return
            t.stop()
            t.reloadStaticData()
            t.setVolume(VOLUME)
            t.play()
        }
    }

    private fun build(cue: SoundCue): AudioTrack? {
        val pcm = render(cue)
        val t = newTrack(pcm.size) ?: return null
        return runCatching { t.write(pcm, 0, pcm.size); t }.getOrNull()
    }

    private fun newTrack(samples: Int): AudioTrack? {
        return runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // Sonification, not media: this rides the system/UI volume, so it neither
                        // ducks whatever is playing nor arrives at music loudness.
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        }.getOrNull()
    }

    fun release() {
        tracks.values.forEach { t -> runCatching { t.release() } }
        tracks.clear()
    }

    /**
     * One segment of a cue: hold [from] for [ms], or glide from [from] to [to] across it.
     *
     * ⚠️ The glide is why this is a class rather than a pair. The alert klaxon does not *step*
     * between two pitches, it sweeps between them, and a renderer that can only hold a constant
     * frequency can produce a two-tone beeper but not a whoop. Everything else still holds, so
     * [to] defaults to [from] and the ordinary cues read exactly as they did.
     */
    private data class Seg(val from: Double, val ms: Int, val to: Double = from)

    /**
     * A cue is a list of segments.
     *
     * ⚠️ **Retuned from the 1987 console to the 1966 one, which is a change of character rather than
     * of pitch alone.** The later console's blips are bright, and three notes in quick succession —
     * the ear reads the interval. The original consoles are rounder and lower, usually a single
     * fuller tone, and their computer "working" sound is a fast trill rather than a rising figure.
     * So the acknowledgements lose a note and drop roughly an octave, CONFIRM becomes that trill,
     * and the alert becomes a swept klaxon.
     */
    private fun render(cue: SoundCue): ShortArray {
        val steps = when (cue) {
            // One round note, not a rising pair: the button on a 1966 console answers, it does not
            // announce.
            SoundCue.TAP -> listOf(Seg(523.3, 58))
            SoundCue.SELECT -> listOf(Seg(392.0, 34), Seg(587.3, 54))
            // The "working" trill — a fast alternation, which is what the original computer does
            // while it is thinking rather than a three-note chime when it finishes.
            SoundCue.CONFIRM -> listOf(
                Seg(659.3, 26), Seg(880.0, 26), Seg(659.3, 26), Seg(880.0, 26), Seg(659.3, 40),
            )
            // Downward, which is how a refusal reads without needing to be loud. Now a glide rather
            // than a step, because a fall that slides is unmistakably a negative.
            SoundCue.REJECT -> listOf(Seg(392.0, 40), Seg(from = 349.2, ms = 150, to = 196.0))
            // The klaxon: two swept whoops. Still the one cue meant to be disliked.
            SoundCue.ALERT -> listOf(
                Seg(from = 440.0, ms = 150, to = 740.0),
                Seg(from = 740.0, ms = 150, to = 440.0),
                Seg(from = 440.0, ms = 150, to = 740.0),
                Seg(from = 740.0, ms = 170, to = 440.0),
            )
        }
        // Per-cue level. The vocabulary has dynamics on purpose -- a tap is not an alarm -- but the
        // whole set was pitched far too low to hear: 0.5 here against a 0.35 track volume put every
        // cue at 17% of full scale, which on a phone speaker at UI-sonification volume is silence.
        val gain = when (cue) {
            SoundCue.TAP -> 0.55
            SoundCue.SELECT -> 0.66
            SoundCue.CONFIRM -> 0.72
            SoundCue.REJECT -> 0.70
            SoundCue.ALERT -> 0.95
        }
        val total = steps.sumOf { seg -> seg.ms * RATE / 1000 }
        val out = ShortArray(total)
        var i = 0
        // ⚠️ **Phase is accumulated, not computed from the sample index.** At a constant frequency
        // `2πf·s/RATE` and a running phase agree exactly, which is why the old form was right for
        // every cue that existed before the glide.
        //
        // For a sweep it is wrong, and measurably so. The angle `2π·f(s)·s/RATE` differentiates to an
        // instantaneous frequency of `f(s) + f'(s)·s`, so a segment asked to run 440 -> 740 Hz
        // actually arrives at **1040 Hz** — it sweeps at double the intended rate and overshoots the
        // frequency it was told to reach. (It does not click: `f(s)·s` is continuous, so there is no
        // discontinuity to hear. That was this comment's first claim, and measuring the two forms
        // refuted it — the max sample-to-sample step is 0.223 against 0.198, no jump at all.)
        // Accumulating the phase integrates f properly, and carrying it across segment boundaries
        // removes the discontinuity there as a bonus.
        var phase = 0.0
        for (seg in steps) {
            val n = seg.ms * RATE / 1000
            for (s in 0 until n) {
                // s.toDouble(), not s: integer division here would hold the frequency at `from` for
                // the whole segment and silently turn every glide back into a step.
                val f = if (seg.to == seg.from) seg.from
                else seg.from + (seg.to - seg.from) * (s.toDouble() / n)
                phase += 2.0 * PI * f / RATE
                // Attack and release ramps. Without them each step begins and ends on a
                // discontinuity, and a discontinuity is a click — which is audible, ugly, and the
                // usual reason hand-rolled tones sound cheap.
                val env = envelope(s, n)
                out[i++] = (sin(phase) * env * Short.MAX_VALUE * gain).toInt().toShort()
            }
        }
        return out
    }

    private fun envelope(sample: Int, total: Int): Double {
        val attack = (RATE * 0.004).toInt().coerceAtLeast(1)
        val release = (total * 0.35).toInt().coerceAtLeast(1)
        return when {
            sample < attack -> sample.toDouble() / attack
            sample > total - release -> (total - sample).toDouble() / release
            else -> 1.0
        }.coerceIn(0.0, 1.0)
    }

    private companion object {
        const val RATE = 22050
        /** Track gain left at unity: the per-cue [render] gain shapes the mix, and the phone's own
         *  system volume is the control that should decide how loud a console is. */
        const val VOLUME = 1.0f
    }
}

val LocalLcarsAudio = staticCompositionLocalOf<LcarsAudio?> { null }

/**
 * The paired entry point: one call plays the sound and the matching haptic together.
 *
 * They belong together — a console that ticks under the finger and blips in the ear at the same
 * instant reads as one machine, where either alone reads as a decoration. Both halves are
 * independently switchable, because one of them is far more likely to be unwelcome in company.
 */
@Composable
fun rememberLcarsCue(): (SoundCue, HapticCue) -> Unit {
    val haptic = rememberHapticCue()
    val soundsOn = LocalSounds.current
    val audio = LocalLcarsAudio.current
    return remember(haptic, soundsOn, audio) {
        { sound: SoundCue, cue: HapticCue ->
            haptic(cue)
            if (soundsOn) audio?.play(sound)
        }
    }
}
