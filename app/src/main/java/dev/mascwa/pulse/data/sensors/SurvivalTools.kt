package dev.mascwa.pulse.data.sensors

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.core.content.getSystemService

/**
 * Offline survival tools that drive device hardware: the camera torch (for an
 * SOS strobe), a loud alarm tone, and vibration. No special permission needed
 * for the torch/tone; vibration uses VIBRATE.
 */
class SurvivalTools(private val context: Context) {

    private val cameraManager = context.getSystemService<CameraManager>()
    private val torchCameraId: String? by lazy {
        cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }

    private var tone: ToneGenerator? = null

    fun torchAvailable(): Boolean = torchCameraId != null

    fun setTorch(on: Boolean) {
        val id = torchCameraId ?: return
        runCatching { cameraManager?.setTorchMode(id, on) }
    }

    fun playAlarm() {
        runCatching {
            stopAlarm()
            tone = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also {
                it.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 600_000)
            }
        }
    }

    fun stopAlarm() {
        runCatching {
            tone?.stopTone()
            tone?.release()
        }
        tone = null
    }

    fun vibrate(pattern: LongArray) {
        runCatching {
            val vm = context.getSystemService<VibratorManager>() ?: return
            vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    fun cancelVibrate() {
        runCatching { context.getSystemService<VibratorManager>()?.defaultVibrator?.cancel() }
    }

    companion object {
        /** Morse SOS timing in ms (dot, gap, …) for torch/vibrate: · · · — — — · · · */
        val SOS_DURATIONS = longArrayOf(
            200, 200, 200, 200, 200, 600,   // S (· · ·)
            600, 200, 600, 200, 600, 600,   // O (— — —)
            200, 200, 200, 200, 200, 1400,  // S (· · ·) + word gap
        )
        /** true = light/buzz on, alternating starting with ON. */
        val SOS_STATES = booleanArrayOf(
            true, false, true, false, true, false,
            true, false, true, false, true, false,
            true, false, true, false, true, false,
        )
    }
}
