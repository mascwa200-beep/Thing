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
     * A cue is a list of (frequency, milliseconds) steps.
     *
     * The stepped pitch is the whole character of the thing — a console blip is not one note, it is
     * two or three in quick succession, and the ear reads the interval rather than the pitch.
     */
    private fun render(cue: SoundCue): ShortArray {
        val steps = when (cue) {
            SoundCue.TAP -> listOf(1046.5 to 26, 1396.9 to 34)
            SoundCue.SELECT -> listOf(880.0 to 30, 1318.5 to 30, 1174.7 to 40)
            SoundCue.CONFIRM -> listOf(784.0 to 34, 987.8 to 34, 1318.5 to 62)
            // Downward, which is how a refusal reads without needing to be loud.
            SoundCue.REJECT -> listOf(392.0 to 60, 261.6 to 90)
            // The one that is meant to be disliked: a hard two-tone alternation.
            SoundCue.ALERT -> listOf(740.0 to 90, 493.9 to 90, 740.0 to 90, 493.9 to 110)
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
        val total = steps.sumOf { (_, ms) -> ms * RATE / 1000 }
        val out = ShortArray(total)
        var i = 0
        for ((freq, ms) in steps) {
            val n = ms * RATE / 1000
            for (s in 0 until n) {
                val angle = 2.0 * PI * freq * s / RATE
                // Attack and release ramps. Without them each step begins and ends on a
                // discontinuity, and a discontinuity is a click — which is audible, ugly, and the
                // usual reason hand-rolled tones sound cheap.
                val env = envelope(s, n)
                out[i++] = (sin(angle) * env * Short.MAX_VALUE * gain).toInt().toShort()
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
