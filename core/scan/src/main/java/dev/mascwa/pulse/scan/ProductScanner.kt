package dev.mascwa.pulse.scan

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.mascwa.pulse.core.telemetry.BarcodeScan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Point a camera at a packet and get the number off it.
 *
 * Shared by both applications, and shared for a reason rather than for tidiness: the two had a
 * near-verbatim copy of this each, both carrying the same rotation defect, and the header of each
 * said in writing that they had to be changed together. They were not. Everything about *how* a
 * barcode is read lives here; each application keeps only its own chrome, its own words and — in the
 * LCARS one — its own camera arbiter, because those genuinely differ.
 *
 * ⚠️ **Nothing is recorded.** Each frame is decoded in memory and dropped. Only the digits printed
 * on the packet leave this object.
 *
 * ⚠️ **Everything a scanner holds outlives the screen unless it is put down**, and each leaks
 * something different: the provider holds the camera device open, which is what keeps the operating
 * system's indicator lit and the battery draining; the analysis use case holds the analyser lambda,
 * which closes over the caller's state and so pins it; and the frames executor is a
 * `newSingleThreadExecutor`, whose thread is not a daemon, so a forgotten one stays alive and
 * scheduled for the life of the process. Opening the scanner ten times over an afternoon left ten.
 * [release] is what ties all of that to the caller's own lifetime.
 */
class ProductScanner(
    private val context: Context,
    private val onCode: (String) -> Unit,
) {

    private val _state = MutableStateFlow(ScanState())

    /** What the scanner has to say for itself; see [ScanState] and [ScanHint]. */
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /**
     * The view to put in the caller's own hierarchy.
     *
     * ⚠️ Created here rather than by the caller because tap-to-focus needs *this* view's metering
     * factory: it is the thing that knows how the preview is cropped and scaled onto the screen, and
     * a focus point computed against anything else lands somewhere other than where the finger was.
     */
    val previewView: PreviewView = PreviewView(context).apply {
        // FILL_CENTER, so the preview is not letterboxed and what is on screen is what the analyser
        // is looking at.
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var frames: ExecutorService? = null
    private var camera: Camera? = null
    private var decoders: List<FrameDecoder> = emptyList()
    private var released = false
    private var lastDecodeMs = 0L
    private var fired = false

    /**
     * Open the camera and start reading.
     *
     * ⚠️ Everything in here runs on the **main** thread — the provider future is listened to on the
     * main executor and the caller disposes there too — so there is no interleaving to guard and no
     * lock is needed. What [released] guards is *ordering*: the future can resolve after the screen
     * has already gone, and binding a camera to a dead screen is worse than not binding at all.
     */
    fun start(lifecycleOwner: LifecycleOwner) {
        if (released || provider != null) return
        // Counted from the start, so "nothing has decoded for four seconds" is true from the first
        // four seconds rather than only after something once did.
        lastDecodeMs = System.currentTimeMillis()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (released) return@addListener
            val got = runCatching { future.get() }
            val cameraProvider = got.getOrNull()
            if (cameraProvider == null) {
                fail("The camera service did not start", got.exceptionOrNull())
                return@addListener
            }
            bind(cameraProvider, lifecycleOwner)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(cameraProvider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        // ⚠️ ML Kit first and ZXing behind it, in that order, and the order is the whole policy:
        // the trained detector reads curved, crinkled, angled and dim barcodes that a scanline
        // reader cannot, and the scanline reader is what answers if the trained one is unavailable
        // on a phone with no Play Services — which cannot be established from a build machine.
        //
        // ⚠️ **`listOfNotNull` and a factory, because the previous `listOf(MlKitDecoder(), …)` made
        // that fallback impossible.** `BarcodeScanning.getClient` ran in a property initialiser, so a
        // device where it throws did not fall back to ZXing — the throw came out of the list, out of
        // `bind`, and the scanner did not start at all. The sentence above described behaviour the
        // code could not produce, on exactly the phone it was reasoning about.
        val chain = listOfNotNull(MlKitDecoder.createOrNull(), ZxingDecoder())

        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(ScanTuning.ANALYSIS_WIDTH, ScanTuning.ANALYSIS_HEIGHT),
                    // ⚠️ Closest LOWER first. Asking a camera that cannot manage 1280×960 for
                    // something bigger instead is the wrong direction: it costs decode time on the
                    // device least able to afford it, to solve a resolution problem it does not have.
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            // At most one frame queued. Combined with the decoders blocking on this executor, the
            // camera simply drops what arrives while a decode is running, which is the backpressure
            // rather than a queue that grows.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(selector)
            .build()
            .also { it.setAnalyzer(executor) { proxy -> onFrame(proxy, chain) } }

        // Recorded BEFORE the bind, not after. Afterwards leaves a window in which a camera is bound
        // and nothing knows to release it — exactly the window somebody creates by opening the
        // scanner and immediately changing their mind.
        provider = cameraProvider
        analysis = imageAnalysis
        frames = executor
        decoders = chain

        val preview = Preview.Builder().build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val bound = runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis,
            )
        }
        val cam = bound.getOrNull()
        if (cam == null) {
            fail("The camera could not be opened", bound.exceptionOrNull())
            return
        }
        camera = cam
        val hasTorch = runCatching { cam.cameraInfo.hasFlashUnit() }.getOrDefault(false)
        // ⚠️ `update`, not `value = value.copy(…)`, and the same everywhere below. The analyser
        // thread writes progress and brightness while the main thread writes the torch and the
        // failure; a read-modify-write through `.value` lets either clobber the other's field, and
        // the visible symptom would be a torch that turns itself off a frame later.
        _state.update { it.copy(running = true, failure = null, torchAvailable = hasTorch) }
    }

    /**
     * ⚠️ **What used to be swallowed.** Both failure paths were `runCatching { … }.getOrNull() ?:
     * return`, so a camera held by another application, a device policy that forbids it, or a phone
     * with no back camera all produced a black rectangle and silence.
     */
    private fun fail(what: String, cause: Throwable?) {
        val detail = cause?.message?.takeIf { it.isNotBlank() }
        _state.update {
            it.copy(running = false, failure = if (detail == null) what else "$what — $detail")
        }
    }

    /**
     * One frame: measure it, read it, and always put it down.
     *
     * ⚠️ `proxy.close()` in a `finally`. `ImageAnalysis` hands out a bounded pool of buffers and
     * stalls for ever once they are all held, so a decode that throws without closing freezes the
     * viewfinder with no error anywhere — the same symptom as a camera that never opened, from a
     * completely different cause.
     */
    private fun onFrame(proxy: ImageProxy, chain: List<FrameDecoder>) {
        try {
            if (released || fired) return
            val dark = brightness(proxy)?.let { it < ScanTuning.DARK_BELOW } ?: false
            var code: String? = null
            for (decoder in chain) {
                code = runCatching { decoder.decode(proxy) }.getOrNull()
                if (code != null) break
            }
            val now = System.currentTimeMillis()
            if (code != null) lastDecodeMs = now
            val quiet = now - lastDecodeMs
            val next = _state.updateAndGet {
                it.copy(
                    progress = if (code != null) {
                        BarcodeScan.see(it.progress, code)
                    } else {
                        BarcodeScan.nothing(it.progress)
                    },
                    tooDark = dark,
                    quietMs = quiet,
                )
            }
            // ⚠️ Guarded, because frames keep arriving after the confirmation while the caller tears
            // its screen down. Without this the same barcode is handed over two or three times and
            // the product is logged as many.
            if (next.progress.confirmed && !fired) {
                fired = true
                ContextCompat.getMainExecutor(context).execute { onCode(next.progress.candidate) }
            }
        } catch (_: Throwable) {
            // A frame that cannot be read is the ordinary case, several times a second. There is
            // nothing to report and nothing to recover: the next arrives in milliseconds.
        } finally {
            proxy.close()
        }
    }

    /**
     * Mean luminance over an even sample of the frame, 0..255, or null if it cannot be measured.
     *
     * ⚠️ Sampled rather than summed. A few hundred evenly-spaced pixels give the same answer as a
     * million for the only question being asked — is there light here — and it runs in microseconds
     * on a thread whose whole budget is one frame interval.
     */
    private fun brightness(proxy: ImageProxy): Int? {
        val plane = proxy.planes.firstOrNull() ?: return null
        // ⚠️ A rewound DUPLICATE, not the plane's own buffer. A `ByteBuffer` carries a position, the
        // decoders read from the same plane, and ML Kit is handed the underlying `Image` whose
        // planes may be the very same buffers — so measuring off the original would either move the
        // position under a decoder or measure a region somebody else had already consumed past.
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val size = buffer.remaining()
        if (size <= 0) return null
        val step = maxOf(1, size / ScanTuning.BRIGHTNESS_SAMPLES)
        var total = 0L
        var count = 0
        var i = 0
        while (i < size) {
            total += buffer.get(i).toInt() and 0xFF
            count++
            i += step
        }
        return if (count == 0) null else (total / count).toInt()
    }

    /** Light the torch, if this camera has one. Returns what the torch is now doing. */
    fun setTorch(on: Boolean): Boolean {
        val control = camera?.cameraControl ?: return false
        if (!_state.value.torchAvailable) return false
        runCatching { control.enableTorch(on) }
        _state.update { it.copy(torchOn = on) }
        return on
    }

    /**
     * Focus where the person tapped, in the preview view's own coordinates.
     *
     * ⚠️ **Auto-cancelled after a few seconds, deliberately.** A focus locked for ever is right for a
     * camera application and wrong for a scanner: the packet moves, the phone moves, and a lock that
     * outlives the moment it was asked for is a viewfinder that has stopped being able to focus at
     * all — with nothing on screen to say so.
     */
    fun focusAt(x: Float, y: Float) {
        val control = camera?.cameraControl ?: return
        val point = runCatching { previewView.meteringPointFactory.createPoint(x, y) }.getOrNull()
            ?: return
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(FOCUS_HOLD_SECONDS, TimeUnit.SECONDS)
            .build()
        runCatching { control.startFocusAndMetering(action) }
    }

    /** Put everything down. Safe to call more than once, and safe to call before [start]. */
    fun release() {
        released = true
        // Ordered: drop the analyser reference first, because that is the one holding the caller's
        // state. `unbindAll` stops the frames but leaves the lambda attached to the use case.
        analysis?.clearAnalyzer()
        runCatching { provider?.unbindAll() }
        decoders.forEach { runCatching { it.close() } }
        // `shutdown`, not `shutdownNow`. A decode in flight is holding an `ImageProxy` and closes it
        // in a `finally`; interrupting risks losing that close, and a leaked proxy stalls the
        // pipeline's bounded pool. Backpressure keeps at most one frame queued, so this finishes in
        // milliseconds.
        frames?.shutdown()
        provider = null
        analysis = null
        frames = null
        camera = null
        decoders = emptyList()
        _state.update { it.copy(running = false, torchOn = false) }
    }

    private companion object {
        const val FOCUS_HOLD_SECONDS = 4L
    }
}
