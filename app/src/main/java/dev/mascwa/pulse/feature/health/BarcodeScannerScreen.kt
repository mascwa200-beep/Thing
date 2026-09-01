package dev.mascwa.pulse.feature.health

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.media.CameraFloor
import dev.mascwa.pulse.scan.ProductScanner
import dev.mascwa.pulse.scan.ScanHint
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Point the camera at a packet and log what is in it.
 *
 * The signature packaged-food workflow: nobody types "Ferrero Nutella hazelnut spread" while standing
 * in a kitchen, and the food database is organised around barcodes precisely because that is how a
 * shelf identifies itself.
 *
 * ⚠️ **Everything about how a barcode is actually read now lives in `:core:scan`**, shared with the
 * standalone nutrition application. This file is a viewfinder, a progress bar and six sentences in
 * this application's own register. The two used to be near-verbatim twins, each with a header saying
 * in writing that they had to be changed together — and they were not, which is how both came to
 * carry the same rotation defect that made the scanner unable to read a barcode held upright.
 *
 * ⚠️ **It claims [CameraFloor] for as long as it is on screen**, so ambient sensing stops taking
 * bursts. `ProcessCameraProvider.unbindAll()` unbinds every client, and without the floor the
 * Sensorium's next burst would tear this down mid-scan with nothing to show for it but a frozen
 * viewfinder. This is the one part of the scanner that genuinely differs between the applications —
 * the nutrition app has no ambient sensing and no second camera client at all — which is why it is
 * here rather than in the shared module.
 *
 * ⚠️ **One `DisposableEffect`, not two, and the order inside it is load-bearing.** The camera has to
 * be released BEFORE the floor is handed back: the other way round, the Sensorium's next burst can
 * bind between the two statements and this scanner's teardown then tears down the sampler that has
 * just started. Two separate effects would leave that ordering to Compose's disposal order, which is
 * not a guarantee worth resting a race on.
 *
 * ⚠️ Nothing is recorded. Each frame is decoded in memory and dropped; only the digits on the packet
 * leave this composable.
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

    // ⚠️ Declared out here rather than inside the `granted` branch. Permission is state: a refusal
    // followed by a grant re-enters that branch, and a scanner remembered inside it would be a
    // different object each time with the previous one holding a camera nobody can now release.
    val scanner = remember { ProductScanner(context, onCode) }

    // ⚠️ Claimed for the whole time this composable is on screen, not just while a camera is bound.
    // The permission dialog is part of that time, and a burst taken during it would be bound and
    // unbound underneath the preview the moment the person granted it.
    DisposableEffect(Unit) {
        CameraFloor.claim()
        onDispose {
            // Camera first, floor second — see the ordering note in this file's header.
            scanner.release()
            CameraFloor.release()
        }
    }

    // ⚠️ Keyed on the permission, so a grant starts the camera rather than leaving a dead preview
    // until the screen is closed and reopened. `start` is idempotent and refuses after release.
    LaunchedEffect(granted) { if (granted) scanner.start(lifecycleOwner) }

    val state by scanner.state.collectAsStateWithLifecycle()

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
                        .background(c.void)
                        // Tap to focus. A fixed-focus viewfinder cannot read a barcode held closer
                        // than about a foot, which is where a person naturally holds a small packet.
                        .pointerInput(scanner) {
                            detectTapGestures { offset -> scanner.focusAt(offset.x, offset.y) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth(),
                        // ⚠️ The view is created once by the scanner and handed over, because
                        // tap-to-focus needs THIS view's metering factory — it is the thing that
                        // knows how the preview is cropped onto the screen, and a point computed
                        // against anything else focuses somewhere other than where the finger was.
                        factory = { scanner.previewView },
                    )
                }
                LcarsFillRow(
                    segments = listOf(
                        state.progress.fraction to c.accent,
                        (1f - state.progress.fraction) to c.raise,
                    ),
                    modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 1.dp),
                    gap = 1.dp,
                )
                Text(
                    // ⚠️ Six situations rather than one sentence. The old scanner said "Line the
                    // barcode up in the frame" for a camera that never opened and for a room too dark
                    // to read anything in, which is most of the difference between a scanner that
                    // seems broken and one that is telling you what to do. Which situation this is,
                    // is decided by `ScanHint` in the shared module so the two applications cannot
                    // come to disagree about it.
                    when (state.hint) {
                        ScanHint.GOT_IT -> "Got it — ${state.progress.candidate}"
                        ScanHint.READING -> "Reading ${state.progress.candidate}… hold still"
                        ScanHint.BROKEN -> state.failure ?: "The camera could not be opened."
                        ScanHint.TOO_DARK -> "Too dark to read — try the torch."
                        ScanHint.STRUGGLING ->
                            "Still looking. Move closer, or steady the packet against something."
                        ScanHint.LOOKING -> "Line the barcode up in the frame."
                    },
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 14.sp,
                    color = if (state.hint == ScanHint.BROKEN) c.negative else c.muted,
                )
                // Only where there is a torch to offer. A control that does nothing is worse than one
                // that is absent.
                if (state.torchAvailable) {
                    LcarsButton(
                        text = if (state.torchOn) "TORCH OFF" else "TORCH ON",
                        onClick = { scanner.setTorch(!state.torchOn) },
                    )
                }
            }
            LcarsButton(text = "CANCEL", onClick = onCancel)
        }
    }
}
