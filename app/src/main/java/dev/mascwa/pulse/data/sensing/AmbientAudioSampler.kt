package dev.mascwa.pulse.data.sensing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.components.containers.AudioData.AudioDataFormat
import com.google.mediapipe.tasks.core.BaseOptions
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.PerceptLabel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Sensorium's EARS: one short mic sip at a time, classified fully on-device by MediaPipe YAMNet
 * into soundscape labels (speech, music, traffic, alarms, …) and immediately discarded. Deliberately
 * DUMB — [sip] captures and classifies exactly once; the SensoriumEngine owns all cadence, adaptive
 * ramping, and the throttle ladder. Unlike the deleted always-recording perception sampler, the
 * microphone is opened per sip and released before returning, so between sips the mic is genuinely
 * free.
 *
 * ⚠️ **[micBusy] does not — and must not — yield to the wake word.** An earlier version of this note
 * claimed it did; it never has, and making it would be worse than the problem. The resident wake loop
 * listens essentially all the time the voice service is running, so a sampler that stood down for it
 * would never sip at all and the Sensorium's ears would be permanently deaf. The two coexist, and
 * whether the platform lets both capture at once is a device question this cannot settle. What
 * [micBusy] does cover is the deliberate, short-lived cases where sipping is either rude or wrong:
 * the console holding the mic for tap-to-talk, and the computer speaking — see the wiring in
 * `AppContainer` for why the second one matters more than it looks.
 *
 * Privacy: classify-then-discard. Raw audio exists only in the recorder's buffer during the sip;
 * only text labels leave this class. Fully defensive: no permission, no model, hardware or classifier
 * failure → an empty list, never a crash.
 */
class AmbientAudioSampler(
    private val context: Context,
    private val http: HttpClient,
    /** True while a sip would be rude or misleading — see the class note and the `AppContainer` wiring. */
    private val micBusy: () -> Boolean = { false },
) {
    private val mutex = Mutex()
    private var classifier: AudioClassifier? = null

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Capture ~[CLIP_MS] of ambient audio and return its labels. Empty when the mic is ungranted or
     * busy, the model is unavailable, or anything at all fails. Serialized — concurrent callers
     * coalesce behind one sip at a time.
     */
    suspend fun sip(): List<PerceptLabel> = mutex.withLock {
        if (!hasMic() || micBusy()) return@withLock emptyList()
        val c = runCatching { ensureClassifier() }.getOrNull() ?: return@withLock emptyList()
        withContext(Dispatchers.Default) {
            var recorder: AudioRecord? = null
            try {
                val r = c.createAudioRecord(AudioFormat.CHANNEL_IN_DEFAULT, SAMPLE_RATE, BUFFER_BYTES)
                recorder = r
                r.startRecording()
                delay(CLIP_MS)
                val audioData = AudioData.create(AudioDataFormat.create(r.format), SAMPLE_RATE)
                audioData.load(r)
                val cats = c.classify(audioData).classificationResults().firstOrNull()
                    ?.classifications()?.firstOrNull()?.categories().orEmpty()
                cats.filter { it.categoryName().isNotBlank() }
                    .map { PerceptLabel(it.categoryName().lowercase(), it.score()) }
            } catch (_: Throwable) {
                emptyList()
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
        }
    }

    /** Release the classifier (the service calls this on teardown; a later sip re-opens lazily). */
    suspend fun close() = mutex.withLock {
        runCatching { classifier?.close() }
        classifier = null
    }

    private suspend fun ensureClassifier(): AudioClassifier {
        classifier?.let { return it }
        val model = ensureModel()
        val opts = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.absolutePath).build())
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(SCORE_THRESHOLD)
            .build()
        return AudioClassifier.createFromOptions(context, opts).also { classifier = it }
    }

    /** Fetch the ~4 MB YAMNet model once (kept out of the APK), cached in filesDir — the same URL and
     *  filename the pre-b9ba600 stack used, so a still-cached model is reused as-is. */
    private suspend fun ensureModel(): File {
        val f = File(context.filesDir, MODEL_FILE)
        if (f.exists() && f.length() > 0) return f
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        http.download(MODEL_URL, tmp, MAX_MODEL_BYTES)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        return f
    }

    private companion object {
        const val MODEL_FILE = "yamnet.tflite"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
        const val MAX_MODEL_BYTES = 24L * 1024 * 1024
        const val SAMPLE_RATE = 16000
        const val BUFFER_BYTES = SAMPLE_RATE * 4 * 2 // ~2 s of float PCM headroom
        const val CLIP_MS = 1_600L
        const val MAX_RESULTS = 6
        const val SCORE_THRESHOLD = 0.25f
    }
}
