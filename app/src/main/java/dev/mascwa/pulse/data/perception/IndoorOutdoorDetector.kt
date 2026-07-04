package dev.mascwa.pulse.data.perception

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.PerceptLabel
import dev.mascwa.pulse.core.telemetry.Perception
import dev.mascwa.pulse.core.telemetry.SceneSignals
import dev.mascwa.pulse.core.telemetry.Setting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * On-device **indoor / outdoor** classifier for the AR wasteland. The 3D renderer needs to know whether the
 * camera is inside or outside so it can pick the right ground: **outdoors** it lays down the solid Fallout
 * wasteland (it *replaces* the real ground); **indoors** a solid floor would block the room, so it only draws
 * a wireframe ground *ghost* + the wireframe structures. This detector turns each AR camera frame into that
 * one boolean.
 *
 * It reuses the same MediaPipe EfficientNet-Lite model (and cache file) as [CameraPerceptionSampler] and the
 * same pure [Perception.distill] logic, so "outdoor"/"indoor" here means exactly what it means to the rest of
 * the life-sim. Unlike that sampler it owns **no camera or lifecycle** — the AR screen already holds the
 * camera for its live preview, so it just binds an extra `ImageAnalysis` and feeds frames to [analyze]; a
 * second camera session (with its own `unbindAll`) would fight the preview.
 *
 * Privacy: nothing leaves the device — each frame is classified in memory and immediately discarded; only the
 * INDOOR/OUTDOOR verdict is published. Fully defensive: no permission / no model / any classifier failure →
 * it stays [Setting.UNKNOWN] and the renderer falls back to the non-blocking ground ghost, never crashing.
 */
class IndoorOutdoorDetector(
    private val context: Context,
    private val http: HttpClient,
) {

    private val _setting = MutableStateFlow(Setting.UNKNOWN)
    /** The current INDOOR/OUTDOOR/VEHICLE read (UNKNOWN until enough confident frames agree). */
    val setting: StateFlow<Setting> = _setting.asStateFlow()

    @Volatile private var classifier: ImageClassifier? = null
    @Volatile private var lastMs = 0L

    // Small debounce so one stray frame can't flip the whole ground: require CONFIRM consecutive agreeing
    // reads before committing. Touched only from the single analysis executor.
    private var pending: Setting = Setting.UNKNOWN
    private var pendingCount = 0

    fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Download (once) + open the classifier. Idempotent; suspends on the model fetch so call it off the main
     * thread (the ViewModel does, in a coroutine). A failure leaves [classifier] null → [analyze] no-ops.
     */
    suspend fun prepare() {
        if (classifier != null) return
        val model = runCatching { ensureModel() }.getOrNull() ?: return
        runCatching {
            val base = BaseOptions.builder().setModelAssetPath(model.absolutePath).build()
            val opts = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(MAX_RESULTS)
                .setScoreThreshold(SCORE_THRESHOLD)
                .build()
            classifier = ImageClassifier.createFromOptions(context, opts)
        }
    }

    /** Feed one AR camera frame. Throttled to [INTERVAL_MS]; classifies → indoor/outdoor. Always closes the proxy. */
    fun analyze(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastMs < INTERVAL_MS) return
            lastMs = now
            val clf = classifier ?: return
            val mp = BitmapImageBuilder(proxy.toBitmap()).build()
            val cats = clf.classify(mp)
                ?.classificationResult()?.classifications()?.firstOrNull()?.categories().orEmpty()
            val labels = cats
                .filter { it.categoryName().isNotBlank() }
                .map { PerceptLabel(it.categoryName().lowercase(), it.score()) }
            if (labels.isEmpty()) return
            commit(Perception.distill(SceneSignals(sceneLabels = labels)).setting)
        } catch (_: Throwable) {
            // Defensive — a bad frame / classify must never crash the AR view.
        } finally {
            runCatching { proxy.close() }
        }
    }

    /** Commit a fresh read through the debounce. UNKNOWN is ignored so a blank frame never wipes a known setting. */
    private fun commit(s: Setting) {
        if (s == Setting.UNKNOWN) return
        if (s == pending) pendingCount++ else { pending = s; pendingCount = 1 }
        if (pendingCount >= CONFIRM && _setting.value != s) _setting.value = s
    }

    /** Release the classifier (call when the AR screen leaves). */
    fun close() {
        runCatching { classifier?.close() }
        classifier = null
    }

    private suspend fun ensureModel(): File {
        val f = File(context.filesDir, MODEL_FILE)
        if (f.exists() && f.length() > 0) return f
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        http.download(MODEL_URL, tmp, MAX_MODEL_BYTES)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        return f
    }

    private companion object {
        // Same model + cache file as CameraPerceptionSampler → whichever runs first primes the shared cache.
        const val MODEL_FILE = "efficientnet_lite0.tflite"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite"
        const val MAX_MODEL_BYTES = 24L * 1024 * 1024
        const val INTERVAL_MS = 2_000L   // classify a frame at most this often
        const val MAX_RESULTS = 5
        const val SCORE_THRESHOLD = 0.20f
        const val CONFIRM = 2            // consecutive agreeing reads before flipping the ground
    }
}
