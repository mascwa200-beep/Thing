package dev.mascwa.nutrition.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.pulse.core.telemetry.BarcodeScan
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Point the camera at a packet and log what is in it.
 *
 * The signature packaged-food workflow: nobody types "Ferrero Nutella hazelnut spread" while
 * standing in a kitchen, and the bundled database is organised around barcodes precisely because
 * that is how a shelf identifies itself.
 *
 * ⚠️ **No camera arbiter here, and that is a real difference from the larger application rather than
 * an omission.** There, `unbindAll()` would tear down the ambient sampler's burst mid-scan, so the
 * scanner has to claim a floor first. This app has no ambient sensing, no sampler and no second
 * camera client at all, so unbinding is unambiguous — and if one is ever added, the floor comes with
 * it rather than being remembered.
 *
 * ⚠️ **The binding is bound to the Activity's lifecycle and released by this composable's, which are
 * not the same thing and were being treated as if they were.** `bindToLifecycle` takes the only
 * `LifecycleOwner` a composable can reach, and that owner outlives the scanner card by the whole rest
 * of the session — so closing the scanner left the camera open, the `ImageAnalysis` pipeline running
 * and ZXing decoding every frame until the app was killed. Battery, a pegged core and the operating
 * system's camera-in-use indicator, all lit by a screen nobody is looking at, on the phone this app
 * exists to run on. [ScannerBinding] is what ties the teardown back to the composable.
 *
 * ⚠️ **This app has no `NavHost` at all**, so `LocalLifecycleOwner` is the Activity itself and that
 * leak lasted the whole process rather than the whole route — the worst version of it, on the app
 * whose reason for existing is running on a phone that cannot spare either.
 *
 * ⚠️ **A near-verbatim twin of `:app`'s `BarcodeScannerScreen`**, which is not shared because doing
 * so would put CameraX on `:core:health`, a module every consumer of the health *data* layer depends
 * on. The two must be changed together; a shared `:core:scan` is the real answer if a third appears.
 *
 * ⚠️ Nothing is recorded. Each frame is decoded in memory and dropped; only the digits on the packet
 * leave this composable.
 */
@Composable
fun BarcodeScanner(
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    var progress by remember { mutableStateOf(BarcodeScan.Progress()) }
    var fired by remember { mutableStateOf(false) }

    // ⚠️ Declared out here rather than inside the `granted` branch. Permission is state: a refusal
    // followed by a grant re-enters that branch, and a binding remembered inside it would be a
    // different object each time with the previous one holding a camera nobody can now release.
    val binding = remember { ScannerBinding() }
    DisposableEffect(Unit) { onDispose { binding.release() } }

    SectionCard("Scan a barcode") {
        if (!granted) {
            Text(
                "The scanner needs the camera. Nothing is recorded — each frame is decoded in memory " +
                    "and discarded, and only the number on the packet leaves this screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { ask.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Allow the camera") }
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    // 4:3 is the sensor's own shape, so the preview is not letterboxed and the
                    // analysis frames cover exactly what is on screen.
                    .aspectRatio(4f / 3f),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        PreviewView(ctx).also { view ->
                            view.scaleType = PreviewView.ScaleType.FILL_CENTER
                            bindScanner(ctx, view, lifecycleOwner, binding) { code ->
                                // ⚠️ Guarded, because analysis frames keep arriving after the
                                // confirmation while the screen tears down. Without `fired` the same
                                // barcode is handed over several times and the caller logs the
                                // product two or three times over.
                                if (fired) return@bindScanner
                                progress = BarcodeScan.see(progress, code)
                                if (progress.confirmed) {
                                    fired = true
                                    onCode(progress.candidate)
                                }
                            }
                        }
                    },
                )
            }
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when {
                    progress.confirmed -> "Got it — ${progress.candidate}"
                    progress.candidate.isNotBlank() -> "Reading ${progress.candidate}… hold still"
                    else -> "Line the barcode up in the frame."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

/**
 * The formats a retail product carries, and nothing else.
 *
 * ⚠️ Restricting them is not only a speed optimisation. Left open, ZXing will happily decode the QR
 * code on a leaflet or the CODE-128 on a shipping label, and every one of those is a decode this
 * screen has to recognise and throw away. Narrowing the decoder means fewer wrong things arrive in
 * the first place.
 */
private val PRODUCT_FORMATS = listOf(
    BarcodeFormat.EAN_13,
    BarcodeFormat.EAN_8,
    BarcodeFormat.UPC_A,
    BarcodeFormat.UPC_E,
    BarcodeFormat.ITF,
)

/**
 * What the scanner is holding, so that closing the scanner can put it down.
 *
 * ⚠️ **Every field here is something that outlives the composable unless it is explicitly released**,
 * and each leaks a different resource:
 *  - the **provider** holds the camera device open, which is what lights the operating system's
 *    camera indicator and drains the battery;
 *  - the **analysis** use case holds the analyzer lambda, which closes over this screen's state
 *    setters — so the composition itself cannot be collected;
 *  - the **frames** executor is a `newSingleThreadExecutor`, whose thread is **not** a daemon, so a
 *    forgotten one stays alive and scheduled for the life of the process. Opening the scanner ten
 *    times over an afternoon left ten of them.
 *
 * ⚠️ Both entry points run on the **main** thread — the provider future is listened to on the main
 * executor and Compose applies `onDispose` there too — so there is no interleaving to guard against
 * and no lock is needed. What [took] is guarding is *ordering*: the future can resolve after the
 * screen has already gone, and binding a camera to a dead screen is worse than not binding at all.
 */
private class ScannerBinding {
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var frames: ExecutorService? = null
    private var released = false

    /**
     * Take ownership of what is about to be bound; `false` means do not bind, the screen has gone.
     *
     * ⚠️ Called **before** `bindToLifecycle`, not after. Recording it afterwards leaves a window in
     * which a camera is bound and nothing knows to release it, and that window is exactly the one a
     * user creates by opening the scanner and immediately changing their mind.
     */
    fun took(provider: ProcessCameraProvider, analysis: ImageAnalysis, frames: ExecutorService): Boolean {
        if (released) {
            frames.shutdown()
            return false
        }
        this.provider = provider
        this.analysis = analysis
        this.frames = frames
        return true
    }

    fun release() {
        released = true
        // ⚠️ Ordered: drop the analyzer reference first, because that is the one holding the
        // composition. `unbindAll` stops the frames but leaves the lambda attached to the use case.
        analysis?.clearAnalyzer()
        runCatching { provider?.unbindAll() }
        // ⚠️ `shutdown`, not `shutdownNow`. A decode in flight is holding an `ImageProxy` and closes
        // it in a `finally`; interrupting it risks losing that close, and a leaked proxy stalls the
        // pipeline's bounded buffer pool. Backpressure keeps at most one frame queued, so a graceful
        // shutdown finishes in milliseconds.
        frames?.shutdown()
        provider = null
        analysis = null
        frames = null
    }
}

private fun bindScanner(
    context: android.content.Context,
    view: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    binding: ScannerBinding,
    onDecode: (String) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to PRODUCT_FORMATS,
                    // A barcode held at arm's length in poor light is exactly the case worth
                    // spending extra work on; the frames are throttled by KEEP_ONLY_LATEST anyway.
                    DecodeHintType.TRY_HARDER to true,
                ),
            )
        }
        val frames = Executors.newSingleThreadExecutor()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(frames) { proxy -> decode(proxy, reader, onDecode) } }

        if (!binding.took(provider, analysis, frames)) return@addListener

        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * One frame, decoded and discarded.
 *
 * ⚠️ **The Y plane only, and its row stride is NOT the image width.** CameraX pads rows to a
 * hardware alignment, so on many devices `rowStride > width` — reading the buffer as if it were
 * tightly packed skews every row by a few pixels and the barcode simply never decodes, on exactly
 * the devices where it looks like the scanner is just bad. `PlanarYUVLuminanceSource` takes the
 * stride as its `dataWidth` and the real width as the crop, which is what makes that correct.
 *
 * ⚠️ `proxy.close()` in a `finally`. ImageAnalysis hands out a bounded pool of buffers and stalls
 * for ever once they are all held — a decode that throws without closing freezes the viewfinder
 * with no error anywhere.
 */
private fun decode(proxy: ImageProxy, reader: MultiFormatReader, onDecode: (String) -> Unit) {
    try {
        val plane = proxy.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val stride = plane.rowStride
        val source = PlanarYUVLuminanceSource(
            bytes, stride, proxy.height, 0, 0, proxy.width, proxy.height, false,
        )
        val result = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }.getOrNull()
        val text = result?.text
        if (!text.isNullOrBlank()) onDecode(text)
    } catch (_: Exception) {
        // A frame that cannot be read is the ordinary case, several times a second. There is nothing
        // to report and nothing to recover: the next frame arrives in milliseconds.
    } finally {
        // ⚠️ Also resets the reader's cached state, or a decode from a frame ago keeps being
        // re-reported after the packet has been taken out of shot.
        reader.reset()
        proxy.close()
    }
}
