package dev.mascwa.pulse.feature.ar3d

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * 3D-AR slice 1: the live camera (bottom layer) with a transparent Filament triangle floating over it (top
 * layer) — the proof that the Filament-over-CameraX composite works on the Pixel. The Filament SurfaceView
 * uses `setZOrderOnTop(true)` + a `TRANSLUCENT` holder so it composites above the camera TextureView and
 * shows the camera through its transparent pixels. Later slices grow this into the geo-anchored 3D wasteland.
 */
@Composable
fun WastelandArScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            CameraPreview(Modifier.fillMaxSize())      // bottom: live camera (TextureView)
            FilamentTriangleSurface(Modifier.fillMaxSize()) // top: transparent Filament overlay
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("CAMERA NEEDED", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                Ar3dButton("GRANT CAMERA", Modifier.padding(top = 12.dp)) { permLauncher.launch(Manifest.permission.CAMERA) }
            }
        }

        // Back + a "beta" tag, held below the system status bar. Drawn LAST so it wins hit-testing.
        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp)) {
            Ar3dButton("‹ EXIT 3D") { onBack() }
            Text("3D WASTELAND · SLICE 1 (proof)", fontWeight = FontWeight.Bold, fontSize = 8.sp,
                color = Color(0xFF9CFFC4), modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE // TextureView (in the window)
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        },
        update = { previewView ->
            val future = ProcessCameraProvider.getInstance(previewView.context)
            future.addListener({
                val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                }
            }, ContextCompat.getMainExecutor(previewView.context))
        },
    )
}

@Composable
private fun FilamentTriangleSurface(modifier: Modifier) {
    val renderer = remember { WastelandRenderer() }
    DisposableEffect(Unit) { onDispose { renderer.detach() } } // frees native memory on leave
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                setZOrderOnTop(true)                       // composite above the camera TextureView
                holder.setFormat(PixelFormat.TRANSLUCENT)  // give the surface an alpha channel
                renderer.attach(this)
            }
        },
    )
}

@Composable
private fun Ar3dButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(3.dp)).background(Color(0xAA000000))
            .border(1.dp, Color(0xFF5BFF9B), RoundedCornerShape(3.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF5BFF9B))
    }
}
