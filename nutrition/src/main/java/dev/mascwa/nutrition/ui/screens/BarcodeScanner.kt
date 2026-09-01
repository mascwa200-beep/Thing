package dev.mascwa.nutrition.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.nutrition.ui.SectionCard
import dev.mascwa.pulse.scan.ProductScanner
import dev.mascwa.pulse.scan.ScanHint

/**
 * Point the camera at a packet and log what is in it.
 *
 * The signature packaged-food workflow: nobody types "Ferrero Nutella hazelnut spread" while
 * standing in a kitchen, and the database is organised around barcodes precisely because that is how
 * a shelf identifies itself.
 *
 * ⚠️ **Everything about how a barcode is actually read now lives in `:core:scan`**, shared with the
 * LCARS application. This file is a viewfinder, a progress bar and six sentences. The two used to be
 * near-verbatim twins whose headers each said in writing that they had to be changed together — and
 * they were not, which is how both came to carry the same rotation defect that made the scanner
 * unable to read a barcode held the way a person holds a phone.
 *
 * ⚠️ **No camera arbiter here, and that is a real difference from the larger application rather than
 * an omission.** There, `unbindAll()` would tear down the ambient sampler's burst mid-scan, so the
 * scanner has to claim a floor first. This app has no ambient sensing, no sampler and no second
 * camera client at all, so unbinding is unambiguous — and if one is ever added, the floor comes with
 * it rather than being remembered.
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

    // ⚠️ Declared out here rather than inside the `granted` branch. Permission is state: a refusal
    // followed by a grant re-enters that branch, and a scanner remembered inside it would be a
    // different object each time with the previous one holding a camera nobody can now release.
    //
    // ⚠️ There is no second `fired` guard here and there does not need to be one: the scanner latches
    // before it posts, so this arrives exactly once however many frames are still in flight while the
    // screen tears down. Two guards for one property is two places for it to be wrong.
    val scanner = remember { ProductScanner(context, onCode) }
    DisposableEffect(Unit) { onDispose { scanner.release() } }

    // ⚠️ Keyed on the permission, so a grant starts the camera rather than leaving a dead preview
    // until the screen is closed and reopened. `start` is idempotent and refuses after release.
    LaunchedEffect(granted) { if (granted) scanner.start(lifecycleOwner) }

    val state by scanner.state.collectAsStateWithLifecycle()

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
                    .aspectRatio(4f / 3f)
                    // Tap to focus. A fixed-focus viewfinder cannot read a barcode held closer than
                    // about a foot, which is where a person naturally holds a small packet.
                    .pointerInput(scanner) {
                        detectTapGestures { offset -> scanner.focusAt(offset.x, offset.y) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    // ⚠️ The view is created once by the scanner and handed over, because
                    // tap-to-focus needs THIS view's metering factory — it is the thing that knows
                    // how the preview is cropped onto the screen, and a point computed against
                    // anything else focuses somewhere other than where the finger was.
                    factory = { scanner.previewView },
                )
            }
            LinearProgressIndicator(
                progress = { state.progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                // ⚠️ Six situations rather than one sentence. The old scanner said "Line the barcode
                // up in the frame" for a camera that never opened and for a room too dark to read
                // anything in, which is most of the difference between a scanner that seems broken
                // and one that is telling you what to do. Which situation this is, is decided by
                // `ScanHint` in the shared module so both applications cannot disagree about it.
                when (state.hint) {
                    ScanHint.GOT_IT -> "Got it — ${state.progress.candidate}"
                    ScanHint.READING -> "Reading ${state.progress.candidate}… hold still"
                    ScanHint.BROKEN -> state.failure ?: "The camera could not be opened."
                    ScanHint.TOO_DARK -> "Too dark to read — try the torch."
                    ScanHint.STRUGGLING ->
                        "Still looking. Move a little closer, or steady the packet against something."
                    ScanHint.LOOKING -> "Line the barcode up in the frame."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.hint == ScanHint.BROKEN) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            // Only where there is a torch to offer. Front cameras and some tablets have none, and a
            // control that does nothing is worse than one that is absent.
            if (state.torchAvailable) {
                OutlinedButton(
                    onClick = { scanner.setTorch(!state.torchOn) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.torchOn) "Turn the torch off" else "Turn the torch on") }
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}
