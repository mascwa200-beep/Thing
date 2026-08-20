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
 * Disturb. `STREAM_ALARM` is the platform's answer to the first half — an alarm is the one stream
 * Android deliberately keeps audible through ringer modes, which is why every clock app uses it and
 * exactly what an emergency warning is for. That part holds with no permission at all.
 *
 * ⚠️ **The second half is best-effort, and saying so is more useful than implying otherwise.**
 * RAISING the alarm volume goes through `setStreamVolume`, which the platform can refuse with a
 * SecurityException while Do Not Disturb is active unless the app holds Notification Policy access —
 * which this app does not, and which cannot simply be declared in the manifest because the user
 * grants it through a system screen. The call is wrapped, so a refusal costs the boost and nothing
 * else: the alarm still sounds on the alarm stream, at whatever level that stream is already set to.
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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Start the alarm. Safe to call twice; the second call is a no-op rather than a second tone. */
    fun start() {
        if (tone != null) return
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        runCatching {
            audio?.let { am ->
                // ⚠️ **Capture the prior level ONLY IF nothing has been captured yet**, because the
                // `tone != null` guard above does not cover the case that matters. If the
                // ToneGenerator constructor throws — which is why it sits in its own runCatching —
                // the volume has ALREADY been raised to maximum while `tone` stays null, so the
                // next `start()` walks straight past the guard, records MAXIMUM as the "prior"
                // level, and `stop()` then restores maximum permanently. The next morning's alarm
                // goes off at full scale for a reason nobody can connect to this, which is exactly
                // what the note below promises will not happen.
                if (priorVolume == null) priorVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                    0, // no UI flash — the screen is already the loudest thing on the phone
                )
            }
        }
        runCatching {
            tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
        }
        sound()
        vibrate()
    }

    /**
     * Sound the tone, and keep sounding it.
     *
     * ⚠️ **The re-arm is here because whether the tone runs for the duration asked cannot be
     * settled from this machine.** `ToneGenerator.startTone(type, durationMs)` plays for the
     * *minimum* of the duration given and the tone's own defined length, and that length lives in
     * the platform's native tone table — not in `android.jar`, so no amount of reading the SDK
     * settles whether `TONE_CDMA_EMERGENCY_RINGBACK` is finite. If it is, the old code played a
     * short burst and then went silent while the red screen stayed up, which for an emergency alarm
     * is the whole failure.
     *
     * Re-issuing it well inside the requested duration is correct under both possibilities and
     * costs a handler message a few seconds apart. `stopTone` first, so a tone still playing is
     * replaced rather than layered — that ordering does not depend on an undocumented restart
     * behaviour either.
     */
    private fun sound() {
        val g = tone ?: return
        runCatching { g.stopTone() }
        runCatching { g.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, TONE_MS) }
        handler.postDelayed({ if (tone != null) sound() }, REARM_MS)
    }

    /** Stop the alarm and hand the volume back exactly as it was found. */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
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
        /** Comfortably inside any plausible tone length, so a finite tone never leaves a gap. */
        const val REARM_MS = 4_000L
    }
}
