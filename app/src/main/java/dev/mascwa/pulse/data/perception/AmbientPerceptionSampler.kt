package dev.mascwa.pulse.data.perception

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * On-device ambient HEARING for the Fallout life-sim: samples the microphone in short clips, runs each
 * through the MediaPipe YAMNet audio classifier **entirely on-device**, and publishes the recognised sound
 * labels (speech, music, traffic, crowd, silence, …). The pure [dev.mascwa.pulse.core.telemetry.Perception]
 * core distils these (plus light/motion/clock) into the game's SceneContext, which drives encounter
 * strategy + the story director.
 *
 * Privacy: nothing leaves the device — only short text labels are produced, and the raw audio is never
 * persisted or transmitted (each clip is classified and discarded). Fully defensive: no RECORD_AUDIO
 * permission, no model, or any hardware/classifier failure → it simply publishes nothing and the game falls
 * back to a neutral scene. The ~4 MB YAMNet model is fetched once on first use (kept out of the APK) and
 * cached on device. Sampling is throttled (a clip every few seconds, only while the game screen is open).
 */
class AmbientPerceptionSampler(
    private val context: Context,
    private val http: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _soundLabels = MutableStateFlow<List<PerceptLabel>>(emptyList())
    /** The sounds the game currently hears (label + confidence); empty when idle/unavailable. */
    val soundLabels: StateFlow<List<PerceptLabel>> = _soundLabels.asStateFlow()

    private var job: Job? = null
    private var classifier: AudioClassifier? = null
    @Volatile private var recorder: AudioRecord? = null

    /** True only if the user has granted the microphone permission. */
    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Begin sampling (no-op if already running or the mic isn't granted). */
    fun start() {
        if (job?.isActive == true || !hasMic()) return
        job = scope.launch {
            val model = runCatching { ensureModel() }.getOrNull() ?: return@launch
            if (!runCatching { open(model) }.getOrDefault(false)) return@launch
            try {
                delay(WARMUP_MS)
                while (isActive) {
                    sampleOnce()
                    delay(INTERVAL_MS)
                }
            } finally {
                closeAll()
            }
        }
    }

    /** Stop sampling + release the mic/classifier; clears the published labels. */
    fun stop() {
        job?.cancel()
        job = null
        _soundLabels.value = emptyList()
    }

    /** Fetch the YAMNet model once (kept out of the APK), cached in filesDir. */
    private suspend fun ensureModel(): File {
        val f = File(context.filesDir, MODEL_FILE)
        if (f.exists() && f.length() > 0) return f
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        http.download(MODEL_URL, tmp, MAX_MODEL_BYTES)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        return f
    }

    private fun open(model: File): Boolean {
        val base = BaseOptions.builder().setModelAssetPath(model.absolutePath).build()
        val opts = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(SCORE_THRESHOLD)
            .build()
        val c = AudioClassifier.createFromOptions(context, opts)
        classifier = c
        val r = c.createAudioRecord(AudioFormat.CHANNEL_IN_DEFAULT, SAMPLE_RATE, BUFFER_BYTES)
        recorder = r
        r.startRecording()
        return true
    }

    private fun sampleOnce() {
        val c = classifier ?: return
        val r = recorder ?: return
        runCatching {
            val audioData = AudioData.create(AudioDataFormat.create(r.format), SAMPLE_RATE)
            audioData.load(r)
            val cats = c.classify(audioData).classificationResults().firstOrNull()
                ?.classifications()?.firstOrNull()?.categories().orEmpty()
            _soundLabels.value = cats
                .filter { it.categoryName().isNotBlank() }
                .map { PerceptLabel(it.categoryName().lowercase(), it.score()) }
        }.onFailure { _soundLabels.value = emptyList() }
    }

    private fun closeAll() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { classifier?.close() }
        classifier = null
    }

    private companion object {
        const val MODEL_FILE = "yamnet.tflite"
        const val MODEL_URL = "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite"
        const val MAX_MODEL_BYTES = 24L * 1024 * 1024
        const val SAMPLE_RATE = 16000
        const val BUFFER_BYTES = SAMPLE_RATE * 4 * 2 // ~2 s of float PCM headroom
        const val WARMUP_MS = 900L
        const val INTERVAL_MS = 3_000L
        const val MAX_RESULTS = 5
        const val SCORE_THRESHOLD = 0.25f
    }
}
