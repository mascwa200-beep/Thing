package dev.mascwa.pulse.data.sensing

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
import dev.mascwa.pulse.data.model.ModelFile
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Sensorium's EYES: one brief back-camera burst at a time — CameraX ImageAnalysis bound to a
 * self-managed [LifecycleRegistry] (this sampler is its own [LifecycleOwner], so it runs headless
 * from the service), a few frames classified fully on-device by MediaPipe EfficientNet-Lite into
 * scene/object labels, camera released before returning. Deliberately DUMB — [burst] samples exactly
 * once; the SensoriumEngine owns cadence, triggers, and the throttle ladder. Between bursts the
 * camera is genuinely closed (no persistent indicator, no held pipeline).
 *
 * Privacy: classify-then-discard. Each frame lives only in memory during classification; only text
 * labels leave this class. Fully defensive: no permission, no model, a GrapheneOS camera-toggle
 * refusal, or any classifier failure → an empty list, never a crash.
 */
class AmbientCameraSampler(
    private val context: Context,
    private val http: HttpClient,
) : LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val mutex = Mutex()
    @Volatile private var classifier: ImageClassifier? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Open the back camera for ~[BURST_MS], classify up to a few frames, close, and return the union
     * of labels seen (best confidence per label). Empty when ungranted/unavailable/refused. Serialized.
     */
    suspend fun burst(): List<PerceptLabel> = mutex.withLock {
        if (!hasCamera()) return@withLock emptyList()
        // ⚠️ Yield to the barcode scanner. `provider.unbindAll()` below unbinds EVERY client's use
        // cases, not just this sampler's, so a burst taken while a scanner is open tears down its
        // preview and analysis mid-scan — the viewfinder freezes and nothing on screen says why.
        // Publishing nothing for a few seconds costs a neutral scene; the collision costs the scan.
        if (dev.mascwa.pulse.feature.media.CameraFloor.scanning.value) return@withLock emptyList()
        val c = runCatching { ensureClassifier() }.getOrNull() ?: return@withLock emptyList()
        val provider = runCatching { awaitProvider() }.getOrNull() ?: return@withLock emptyList()

        val seen = Collections.synchronizedMap(mutableMapOf<String, Float>())
        var lastProcessedMs = 0L
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { proxy ->
                    classifyFrame(proxy, c, seen) {
                        val now = System.currentTimeMillis()
                        (now - lastProcessedMs >= FRAME_GAP_MS).also { ok -> if (ok) lastProcessedMs = now }
                    }
                }
            }

        try {
            val bound = withContext(Dispatchers.Main) {
                runCatching {
                    registry.currentState = Lifecycle.State.STARTED
                    provider.unbindAll()
                    provider.bindToLifecycle(this@AmbientCameraSampler, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                }.isSuccess
            }
            if (!bound) return@withLock emptyList()
            delay(BURST_MS)
        } finally {
            withContext(Dispatchers.Main) {
                runCatching { provider.unbindAll() }
                runCatching { registry.currentState = Lifecycle.State.CREATED }
            }
        }
        seen.entries
            .sortedByDescending { it.value }
            .take(MAX_RESULTS)
            .map { PerceptLabel(it.key, it.value) }
    }

    private fun classifyFrame(
        proxy: ImageProxy,
        c: ImageClassifier,
        seen: MutableMap<String, Float>,
        shouldProcess: () -> Boolean,
    ) {
        try {
            if (!shouldProcess()) return
            val mp = BitmapImageBuilder(proxy.toBitmap()).build()
            val cats = c.classify(mp)
                .classificationResult().classifications().firstOrNull()?.categories().orEmpty()
            for (cat in cats) {
                val name = cat.categoryName().lowercase()
                if (name.isBlank()) continue
                val prev = seen[name]
                if (prev == null || cat.score() > prev) seen[name] = cat.score()
            }
        } catch (_: Throwable) {
            // Defensive — a bad frame/classify must never crash the sampler or the service.
        } finally {
            runCatching { proxy.close() }
        }
    }

    /** Release the classifier (service teardown; a later burst re-opens lazily). */
    suspend fun close() = mutex.withLock {
        runCatching { classifier?.close() }
        classifier = null
    }

    /** How much of the disk this model is holding — see [ModelFile], including a half-fetched one. */
    fun bytesOnDisk(): Long = ModelFile.bytes(context, MODEL_FILE)

    /**
     * Give the storage back. Closes the classifier first and re-opens lazily — see
     * [AmbientAudioSampler.discardModel], which this mirrors exactly, for why both halves matter.
     */
    suspend fun discardModel(): Boolean {
        close()
        return withContext(Dispatchers.IO) { ModelFile.discard(context, MODEL_FILE) }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider? = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (cont.isActive) cont.resume(runCatching { future.get() }.getOrNull())
        }, ContextCompat.getMainExecutor(context))
    }

    private suspend fun ensureClassifier(): ImageClassifier {
        classifier?.let { return it }
        val model = ensureModel()
        val opts = ImageClassifier.ImageClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(model.absolutePath).build())
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(MAX_RESULTS)
            .setScoreThreshold(SCORE_THRESHOLD)
            .build()
        return ImageClassifier.createFromOptions(context, opts).also { classifier = it }
    }

    /** Same URL/filename as the pre-b9ba600 stack, so a still-cached model is reused as-is. */
    private suspend fun ensureModel(): File {
        val f = File(context.filesDir, MODEL_FILE)
        if (f.exists() && f.length() > 0) return f
        val tmp = File(context.filesDir, "$MODEL_FILE.part")
        http.download(MODEL_URL, tmp, MAX_MODEL_BYTES)
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        return f
    }

    private companion object {
        const val MODEL_FILE = "efficientnet_lite0.tflite"
        const val MODEL_URL =
            "https://storage.googleapis.com/mediapipe-models/image_classifier/efficientnet_lite0/float32/1/efficientnet_lite0.tflite"
        const val MAX_MODEL_BYTES = 24L * 1024 * 1024
        const val BURST_MS = 3_200L
        const val FRAME_GAP_MS = 900L
        const val MAX_RESULTS = 6
        const val SCORE_THRESHOLD = 0.20f
    }
}
