package dev.mascwa.pulse.feature.redalert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The alarm a red alert sounds — loud, on the alarm stream, and not silenced by a quiet phone.
 *
 * The owner's instruction was explicit: it plays at full volume regardless of silent or Do Not
 * Disturb. `STREAM_ALARM` is the platform's answer to that — an alarm is the one stream Android
 * deliberately keeps audible through ringer modes, which is exactly why every clock app uses it and
 * exactly what an emergency warning is for.
 *
 * ⚠️ **It restores the volume it changed.** Raising the alarm stream to maximum and walking away
 * would mean the next morning's alarm goes off at full scale for reasons the owner has no way to
 * connect to this. The prior level is captured before the change and put back on [stop], including
 * when [stop] runs from the Activity being destroyed.
 *
 * ⚠️ **Nothing silences an individual alert.** There is a Settings switch for the whole feature and
 * an acknowledge button on the screen; there is no snooze and no per-alert mute. A warning you can
 * wave away without reading is a warning that will be waved away.
 *
 * Reuses `SurvivalTools`' proven approach rather than a media file: a synthesised tone needs no
 * asset, cannot fail to decode, and starts instantly.
 */
class EmergencyKlaxon(private val context: Context) {

    private var tone: ToneGenerator? = null
    private var priorVolume: Int? = null

    /** Start the alarm. Safe to call twice; the second call is a no-op rather than a second tone. */
    fun start() {
        if (tone != null) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            audio?.let { am ->
                priorVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0, // no UI flash — the screen is already the loudest thing on the phone
                )
            }
        }
        runCatching {
            // A long duration rather than a loop: ToneGenerator counts in milliseconds and this is
            // stopped explicitly, so the number is only a backstop for a process that dies oddly.
            tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also {
                it.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, TONE_MS)
            }
        }
        vibrate()
    }

    /** Stop the alarm and hand the volume back exactly as it was found. */
    fun stop() {
        runCatching { tone?.stopTone() }
        runCatching { tone?.release() }
        tone = null
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        priorVolume?.let { prior ->
            runCatching { audio?.setStreamVolume(AudioManager.STREAM_ALARM, prior, 0) }
        }
        priorVolume = null
        runCatching { vibrator()?.cancel() }
    }

    private fun vibrate() {
        val v = vibrator() ?: return
        // Deaf, asleep, phone face-down in a pocket: the pattern matters as much as the tone.
        val pattern = longArrayOf(0, 700, 300, 700, 300, 1400, 500)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createWaveform(pattern, 0),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private companion object {
        /** Long enough to outlast any realistic acknowledge, short enough not to run forever. */
        const val TONE_MS = 10 * 60_000
    }
}
