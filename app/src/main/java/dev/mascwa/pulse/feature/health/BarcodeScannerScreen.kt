package dev.mascwa.pulse.feature.health

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.mascwa.pulse.core.telemetry.BarcodeScan
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.media.CameraFloor
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import androidx.compose.material3.Text
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Point the camera at a packet and log what is in it.
 *
 * The signature packaged-food workflow: nobody types "Ferrero Nutella hazelnut spread" while standing
 * in a kitchen, and Open Food Facts is organised around barcodes precisely because that is how a
 * shelf identifies itself.
 *
 * ⚠️ **It claims [CameraFloor] for as long as it is on screen**, so ambient sensing stops taking
 * bursts. `ProcessCameraProvider.unbindAll()` unbinds every client, and without the floor the
 * Sensorium's next burst would tear this down mid-scan with nothing to show for it but a frozen
 * viewfinder. The release is in `onDispose`, which runs on the back gesture and on any navigation
 * this screen did not initiate — a floor left claimed is ambient sensing silently off for ever.
 *
 * ⚠️ **The camera itself was NOT released by that teardown, and the floor made it look as though it
 * were.** `bindToLifecycle` is given the only `LifecycleOwner` a composable can reach, which here is
 * the navigation back stack entry for the whole HEALTH route — so closing the scanner card left the
 * camera device open and ZXing decoding every frame for as long as that tab stayed on the stack.
 * The floor being handed back merely let ambient sensing take the camera away again, which
 * *incidentally* cleaned up after this screen; with ambient sensing switched off, nothing ever did.
 * [ScannerBinding] ties the release to this composable rather than to a lifetime it does not own.
 *
 * ⚠️ **One `DisposableEffect`, not two, and the order inside it is load-bearing.** The camera has to
 * be unbound BEFORE the floor is handed back: released first, the Sensorium's next burst can bind
 * between the two statements and `unbindAll()` then tears down the sampler that has just started.
 * Two separate effects would leave that ordering to Compose's disposal order, which is not a
 * guarantee worth resting a race on.
 *
 * ⚠️ **A near-verbatim twin of `:nutrition`'s `BarcodeScanner`**, which is not shared because doing
 * so would put CameraX on `:core:health`, a module every consumer of the health *data* layer
 * depends on. The two must be changed together; a shared `:core:scan` is the real answer if a third
 * ever appears.
 */
@Composable
fun BarcodeScanner(
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
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

    // ⚠️ Claimed for the whole time this composable is on screen, not just while a camera is bound.
    // The permission dialog is part of that time, and a burst taken during it would be bound and
    // unbound underneath the preview the moment the person granted it.
    DisposableEffect(Unit) {
        CameraFloor.claim()
        onDispose {
            // Camera first, floor second — see the ordering note in this file's header.
            binding.release()
            CameraFloor.release()
        }
    }

    LcarsFrame(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "SCAN A BARCODE",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.accent,
            )
            if (!granted) {
                Text(
                    "The scanner needs the camera. Nothing is recorded — each frame is decoded in " +
                        "memory and discarded, and only the number on the packet leaves this screen.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, lineHeight = 14.sp,
                )
                LcarsButton(text = "ALLOW THE CAMERA", onClick = { ask.launch(Manifest.permission.CAMERA) })
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // 4:3 is the sensor's own shape, so the preview is not letterboxed and the
                        // analysis frames cover exactly what is on screen.
                        .aspectRatio(4f / 3f)
                        .background(c.void),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        factory = { ctx ->
                            PreviewView(ctx).also { view ->
                                view.scaleType = PreviewView.ScaleType.FILL_CENTER
                                bindScanner(ctx, view, lifecycleOwner, binding) { code ->
                                    // ⚠️ Guarded, because analysis frames keep arriving after the
                                    // confirmation while the screen tears down. Without `fired` the
                                    // same barcode would be handed over several times, and the
                                    // caller would log the product two or three times over.
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
                LcarsFillRow(
                    segments = listOf(
                        progress.fraction to c.accent,
                        (1f - progress.fraction) to c.raise,
                    ),
                    modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 1.dp),
                    gap = 1.dp,
                )
                Text(
                    when {
                        progress.confirmed -> "Got it — ${progress.candidate}"
                        progress.candidate.isNotBlank() -> "Reading ${progress.candidate}… hold still"
                        else -> "Line the barcode up in the frame."
                    },
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                )
            }
            LcarsButton(text = "CANCEL", onClick = onCancel)
        }
    }
}

/**
 * The formats a retail product carries, and nothing else.
 *
 * ⚠️ Restricting them is not only a speed optimisation. Left open, ZXing will happily decode the QR
 * code on a leaflet or the CODE-128 on a shipping label, and every one of those is a decode this
 * screen has to recognise and throw away. Narrowing the decoder means fewer wrong things arrive in
 * the first place. Every constant here was read out of the shipped 3.5.3 jar with `javap`.
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
 * ⚠️ **Every field here outlives the composable unless it is explicitly released**, and each leaks
 * something different:
 *  - the **provider** holds the camera device open, which is what keeps the operating system's
 *    camera indicator lit and the battery draining;
 *  - the **analysis** use case holds the analyzer lambda, which closes over this screen's state
 *    setters — so the composition itself cannot be collected;
 *  - the **frames** executor is a `newSingleThreadExecutor`, whose thread is **not** a daemon, so a
 *    forgotten one stays alive for the life of the process. Opening the scanner ten times left ten.
 *
 * ⚠️ Both entry points run on the **main** thread — the provider future is listened to on the main
 * executor and Compose applies `onDispose` there too — so there is no interleaving to guard against
 * and no lock is needed. What [took] guards is *ordering*: the future can resolve after the screen
 * has gone, and binding a camera to a dead screen is worse than not binding at all.
 *
 * ⚠️ Twinned with `:nutrition`'s copy. See this file's header for why it is not shared.
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
                    // A barcode held at arm's length in poor light is exactly the case worth spending
                    // extra work on; the frames are throttled by KEEP_ONLY_LATEST regardless.
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
            // ⚠️ Safe here only because CameraFloor keeps the ambient sampler away for the whole
            // time this screen is up. Outside that guarantee this line would unbind another client.
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * One frame, decoded and discarded.
 *
 * ⚠️ **The Y plane only, and its row stride is NOT the image width.** CameraX pads rows to a hardware
 * alignment, so on many devices `rowStride > width` — reading the buffer as if it were tightly packed
 * skews every row by a few pixels and the barcode simply never decodes, on exactly the devices where
 * it looks like the scanner is just bad. `PlanarYUVLuminanceSource` takes the stride as its `dataWidth`
 * and the real width as the crop, which is what makes that correct.
 *
 * ⚠️ `proxy.close()` in a `finally`. ImageAnalysis hands out a bounded pool of buffers and stalls for
 * ever once they are all held — a decode that throws without closing freezes the viewfinder with no
 * error anywhere.
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
