package dev.mascwa.pulse.data.perception

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * On-device ambient SEEING for the Fallout life-sim: samples the camera in brief frames, runs each through
 * the MediaPipe EfficientNet-Lite image classifier **entirely on-device**, and publishes the recognised
 * object/scene labels (desk, screen, tree, street, car, …). The pure
 * [dev.mascwa.pulse.core.telemetry.Perception] core distils these (with the mic's sound labels + light +
 * motion + clock) into the game's SceneContext, which drives encounter selection + the story director.
 *
 * Uses **CameraX ImageAnalysis** bound to a self-managed [LifecycleRegistry] (this sampler is its own
 * [LifecycleOwner]) so it runs headless, without an Activity. It spends most of its time on the **back**
 * camera (it sees your surroundings) and briefly flips to the **front** every few cycles (owner-chosen
 * "alternate back + front").
 *
 * Privacy: nothing leaves the device — only short text labels are produced; each frame is classified in
 * memory and immediately discarded (never persisted or transmitted). Fully defensive: no CAMERA permission,
 * no model, or any hardware/classifier failure → it publishes nothing and the game falls back to a neutral
 * scene, never crashing. The ~4 MB model is fetched once on first use (kept out of the APK) and cached.
 */
class CameraPerceptionSampler(
    private val context: Context,
    private val http: HttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val _sceneLabels = MutableStateFlow<List<PerceptLabel>>(emptyList())
    /** What the game currently sees (object/scene label + confidence); empty when idle/unavailable. */
    val sceneLabels: StateFlow<List<PerceptLabel>> = _sceneLabels.asStateFlow()

    private var job: Job? = null
    @Volatile private var classifier: ImageClassifier? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastProcessedMs = 0L

    /** True only if the user has granted the camera permission. */
    fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Begin sampling (no-op if already running or the camera isn't granted). */
    fun start() {
        if (job?.isActive == true || !hasCamera()) return
        job = scope.launch {
            val model = runCatching { ensureModel() }.getOrNull() ?: return@launch
            if (!runCatching { openClassifier(model) }.getOrDefault(false)) return@launch
            val provider = runCatching { awaitProvider() }.getOrNull() ?: return@launch
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            try {
                var cycle = 0
                var boundFront: Boolean? = null
                while (isActive) {
                    // Mostly the back camera (your surroundings); a brief front peek every FRONT_EVERY cycles.
                    val front = FRONT_ENABLED && cycle % FRONT_EVERY == FRONT_EVERY - 1
                    if (boundFront != front) {
                        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                        bind(provider, analysis, selector)
                        boundFront = front
                    }
                    delay(if (front) FRONT_MS else BACK_MS)
                    cycle++
                }
            } finally {
                withContext(Dispatchers.Main) { runCatching { provider.unbindAll() } }
                closeClassifier()
            }
        }
    }

    /** Stop sampling + release the camera/classifier; clears the published labels. */
    fun stop() {
        job?.cancel()
        job = null
        _sceneLabels.value = emptyList()
        // Drop below STARTED so CameraX releases the camera even if a bind is mid-flight.
        scope.launch(Dispatchers.Main) { runCatching { registry.currentState = Lifecycle.State.CREATED } }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider? = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (cont.isActive) cont.resume(runCatching { future.get() }.getOrNull())
        }, ContextCompat.getMainExecutor(context))
    }

    private suspend fun bind(provider: ProcessCameraProvider, analysis: ImageAnalysis, selector: CameraSelector) {
        withContext(Dispatchers.Main) {
            runCatching {
                registry.currentState = Lifecycle.State.STARTED
                provider.unbindAll()
                provider.bindToLifecycle(this@CameraPerceptionSampler, selector, analysis)
            }
        }
    }

    private fun analyze(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastProcessedMs < INTERVAL_MS) return
            lastProcessedMs = now
            val mp = BitmapImageBuilder(proxy.toBitmap()).build()
            val cats = classifier?.classify(mp)
                ?.classificationResult()?.classifications()?.firstOrNull()?.categories().orEmpty()
            _sceneLabels.value = cats
                .filter { it.categoryName().isNotBlank() }
                .map { PerceptLabel(it.categoryName().lowercase(), it.score()) }
        } catch (_: Throwable) {
            // Defensive — a bad frame/classify must never crash the app or the sampler.
        } finally {
            runCatching { proxy.close() }
        }
    }

    private suspend fun ensureModel(): File {
        val f = File(context.filesDir, MODEL_FILE)
        if (f.exists() && f.length() > 0) return f
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        http.download(MODEL_URL, tmp, MAX_MODEL_BYTES)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        return f
    }

    private fun openClassifier(model: File): Boolean {
        val base = BaseOptions.builder().setModelAssetPath(model.absolutePath).build()
        val opts = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(SCORE_THRESHOLD)
            .build()
        classifier = ImageClassifier.createFromOptions(context, opts)
        return true
    }

    private fun closeClassifier() {
        runCatching { classifier?.close() }
        classifier = null
    }

    private companion object {
        const val MODEL_FILE = "efficientnet_lite0.tflite"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite"
        const val MAX_MODEL_BYTES = 24L * 1024 * 1024
        const val INTERVAL_MS = 4_000L   // classify a frame at most this often (ambient, battery-friendly)
        const val BACK_MS = 45_000L      // dwell on the back camera this long each cycle
        const val FRONT_MS = 6_000L      // brief front-camera peek
        const val FRONT_EVERY = 4        // every Nth cycle flips to the front
        const val FRONT_ENABLED = true
        const val MAX_RESULTS = 5
        const val SCORE_THRESHOLD = 0.20f
    }
}
