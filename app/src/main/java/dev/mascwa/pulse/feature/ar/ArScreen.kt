package dev.mascwa.pulse.feature.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mascwa.pulse.core.telemetry.ArProjection
import dev.mascwa.pulse.core.telemetry.SiteType
import dev.mascwa.pulse.core.telemetry.WorldSite
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import androidx.compose.material3.Text

/** Within this range you're standing at a site — engage-able (matches the geo-gate reach). */
private const val AR_REACH_M = 60.0

/**
 * The AR wasteland camera — a "magic window" that projects the nearby geo-gated [WorldSite]s onto the live
 * camera picture using the compass heading + each site's bearing ([ArProjection]). No ARCore: as you pan the
 * phone, markers slide across because their bearing is fixed while your heading changes. Minecraft-Earth-style.
 */
@Composable
fun ArScreen(vm: ArViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) permLauncher.launch(Manifest.permission.CAMERA) }

    // Compass + GPS run only while this screen is on.
    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    val sites by vm.sites.collectAsStateWithLifecycle()
    val gps by vm.gps.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val unreliable by vm.compassUnreliable.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            CameraPreview(Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("CAMERA NEEDED", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = Color.White, letterSpacing = 2.sp)
                Text("The AR view projects the wasteland through your camera. Grant camera access to see it.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = Color(0xFFB0B8C4),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                ArButton("GRANT CAMERA", Color(0xFF5AD1FF), Modifier.padding(top = 16.dp)) {
                    permLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }

        // Projected site markers (only when we have a fix + camera).
        if (hasCamera && gps != null) {
            val g = gps!!
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wDp = maxWidth
                val hDp = maxHeight
                // Far markers first so nearer ones draw on top.
                val visible = sites.mapNotNull { s ->
                    val bearing = Geo.bearingDegrees(g.latitude, g.longitude, s.lat, s.lon)
                    if (!ArProjection.inView(heading.toDouble(), bearing)) return@mapNotNull null
                    val dist = Geo.distanceMeters(g.latitude, g.longitude, s.lat, s.lon)
                    Triple(s, bearing, dist)
                }.sortedByDescending { it.third }

                visible.forEach { (s, bearing, dist) ->
                    val xFrac = ArProjection.screenX(heading.toDouble(), bearing).toFloat().coerceIn(0f, 1f)
                    val size = ArProjection.sizeForDistance(dist).toFloat()
                    val markerW = 150.dp
                    // Near sites sit lower (closer to you); far sites ride up toward the horizon line.
                    val yFrac = 0.30f + (1f - size) * 0.28f
                    Box(
                        Modifier.offset(x = wDp * xFrac - markerW / 2, y = hDp * yFrac).width(markerW),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        SiteMarker(s, dist, size)
                    }
                }
            }
        }

        // Centre reticle — a ring with a dot, marking where you're aimed.
        Box(
            Modifier.align(Alignment.Center).width(46.dp)
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        ) { Box(Modifier.width(46.dp).padding(22.dp).background(Color.White.copy(alpha = 0.5f), CircleShape)) }

        // Top bar: back + heading + calibration hint.
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ArButton("‹ BACK", Color(0xFF5AD1FF)) { onBack() }
            Column(horizontalAlignment = Alignment.End) {
                Text("${heading.toInt()}° ${cardinal(heading)}", fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White, letterSpacing = 1.sp)
                if (unreliable) {
                    Text("compass off — wave in a figure-8", fontFamily = JetBrainsMono, fontSize = 8.sp,
                        color = Color(0xFFFFC542))
                }
            }
        }

        // Bottom bar: scan + a hint when nothing's around.
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (gps != null && sites.isEmpty()) {
                Text(
                    if (scanning) "SCANNING THE WASTELAND…" else "No sites mapped here yet.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = Color(0xFFB0B8C4),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            } else if (gps == null) {
                Text("Waiting for a GPS fix…", fontFamily = JetBrainsMono, fontSize = 10.sp,
                    color = Color(0xFFB0B8C4), modifier = Modifier.padding(bottom = 8.dp))
            }
            ArButton(if (scanning) "SCANNING…" else "SCAN AREA ▸", Color(0xFF5AD1FF)) { vm.scan() }
        }
    }
}

/** Live back-camera preview via CameraX, bound to the composition's lifecycle. */
@Composable
private fun CameraPreview(modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = runCatching { providerFuture.get() }.getOrNull()
                if (provider != null) {
                    val preview = androidx.camera.core.Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                    }
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** A floating site card, tinted by threat, sized by distance. */
@Composable
private fun SiteMarker(site: WorldSite, distanceM: Double, size: Float) {
    val accent = arColor(site.type)
    val here = distanceM <= AR_REACH_M
    val scale = (0.82f + size * 0.5f) // 0.82..1.32 — a gentle depth cue on the text sizes
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Diamond pin.
        Box(
            Modifier.width((14 * scale).dp).padding(top = 0.dp)
                .background(accent, RoundedCornerShape(2.dp)),
        ) { Box(Modifier.width((14 * scale).dp).padding((7 * scale).dp)) }
        Box(
            Modifier.padding(top = 3.dp).clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.62f))
                .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(site.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = (11 * scale).sp, color = Color.White, maxLines = 1, textAlign = TextAlign.Center)
                Text(
                    if (here) "◉ HERE · ENGAGE" else "${site.type.label} · ${Geo.formatDistance(distanceM)}",
                    fontFamily = JetBrainsMono, fontSize = (8 * scale).sp,
                    color = if (here) accent else Color(0xFFCED6E0),
                )
            }
        }
    }
}

@Composable
private fun ArButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, color.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            color = color, letterSpacing = 1.sp)
    }
}

/** Threat-tinted marker colour: danger red, the wilds amber, safe/trade green. */
private fun arColor(type: SiteType): Color = when {
    type.hostile -> Color(0xFFFF4D6D)
    type.threat >= 2 -> Color(0xFFFFC542)
    else -> Color(0xFF46F9A0)
}

/** Cardinal label for a heading in degrees. */
private fun cardinal(deg: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[(((deg / 45f).toInt()) % 8 + 8) % 8]
}
