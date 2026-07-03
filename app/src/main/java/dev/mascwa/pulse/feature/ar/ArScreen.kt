package dev.mascwa.pulse.feature.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Text
import dev.mascwa.pulse.core.telemetry.ArProjection
import dev.mascwa.pulse.core.telemetry.SiteType
import dev.mascwa.pulse.core.telemetry.WorldSite
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.feature.tacnet.Pip
import dev.mascwa.pulse.feature.tacnet.crtScanlines
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Within this range you're standing at a site — engage-able (matches the geo-gate reach). */
private const val AR_REACH_M = 60.0

/** Radar minimap range (metres to the outer ring). */
private const val RADAR_RANGE_M = 800.0

/**
 * The AR wasteland camera — a Fallout-Pip-Boy "magic window" that projects the nearby geo-gated [WorldSite]s
 * onto the live camera picture. Horizontal position comes from your compass heading vs each site's bearing;
 * vertical position from the camera **pitch** (tilt up → markers slide down toward the horizon), so tilting
 * no longer smears things sideways. Extra HUD: a heading ribbon, a top-down radar, an operator STAT strip,
 * and the tracked objective. Phosphor-green CRT chrome. No ARCore.
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

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    val sites by vm.sites.collectAsStateWithLifecycle()
    val gps by vm.gps.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val pitch by vm.pitch.collectAsStateWithLifecycle()
    val unreliable by vm.compassUnreliable.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val character by vm.character.collectAsStateWithLifecycle()
    val activeWp by vm.activeWaypoint.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Pip.bg)) {
        if (hasCamera) {
            CameraPreview(Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("OPTICS OFFLINE", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    color = Pip.bright, letterSpacing = 2.sp)
                Text("The AR overlay projects the wasteland through your camera. Grant camera access to see it.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = Pip.dim,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                ArButton("GRANT OPTICS", Modifier.padding(top = 16.dp)) { permLauncher.launch(Manifest.permission.CAMERA) }
            }
        }

        // Nearest engage-able site (for the readout + radar highlight).
        val g = gps
        val nearest = if (g != null) {
            sites.minByOrNull { Geo.distanceMeters(g.latitude, g.longitude, it.lat, it.lon) }
        } else null

        // --- Projected markers (need camera + a fix) ---
        if (hasCamera && g != null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wDp = maxWidth
                val hDp = maxHeight

                // Tracked objective first (drawn under the sites is fine; it's gold + distinct).
                activeWp?.let { wp ->
                    val bearing = Geo.bearingDegrees(g.latitude, g.longitude, wp.latitude, wp.longitude)
                    if (ArProjection.inView(heading.toDouble(), bearing) &&
                        ArProjection.inViewVertical(pitch.toDouble())
                    ) {
                        val xFrac = ArProjection.screenX(heading.toDouble(), bearing).toFloat().coerceIn(0f, 1f)
                        val yFrac = ArProjection.screenY(pitch.toDouble()).toFloat().coerceIn(0.08f, 0.92f)
                        val dist = Geo.distanceMeters(g.latitude, g.longitude, wp.latitude, wp.longitude)
                        Box(Modifier.offset(x = wDp * xFrac - 75.dp, y = hDp * yFrac).width(150.dp),
                            contentAlignment = Alignment.TopCenter) {
                            WaypointMarker(wp.label, dist)
                        }
                    }
                }

                // Sites — far first so near draw on top.
                val visible = sites.mapNotNull { s ->
                    val bearing = Geo.bearingDegrees(g.latitude, g.longitude, s.lat, s.lon)
                    if (!ArProjection.inView(heading.toDouble(), bearing)) return@mapNotNull null
                    if (!ArProjection.inViewVertical(pitch.toDouble())) return@mapNotNull null
                    Triple(s, bearing, Geo.distanceMeters(g.latitude, g.longitude, s.lat, s.lon))
                }.sortedByDescending { it.third }

                visible.forEach { (s, bearing, dist) ->
                    val xFrac = ArProjection.screenX(heading.toDouble(), bearing).toFloat().coerceIn(0f, 1f)
                    val yFrac = ArProjection.screenY(pitch.toDouble()).toFloat().coerceIn(0.08f, 0.92f)
                    val size = ArProjection.sizeForDistance(dist).toFloat()
                    val markerW = 150.dp
                    Box(Modifier.offset(x = wDp * xFrac - markerW / 2, y = hDp * yFrac).width(markerW),
                        contentAlignment = Alignment.TopCenter) {
                        SiteMarker(s, dist, size)
                    }
                }
            }
        }

        // --- Fixed HUD chrome ---

        // Heading ribbon across the top.
        CompassRibbon(heading, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(30.dp)
            .padding(top = 2.dp))

        // Centre reticle — a phosphor ring + dot.
        Box(Modifier.align(Alignment.Center).size(48.dp).border(1.dp, Pip.bright.copy(alpha = 0.6f), CircleShape)) {
            Box(Modifier.align(Alignment.Center).size(4.dp).background(Pip.glow, CircleShape))
        }

        // Top bar: back + heading readout + calibration.
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 34.dp, start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ArButton("‹ EXIT") { onBack() }
            Column(horizontalAlignment = Alignment.End) {
                Text("${heading.toInt()}° ${cardinal(heading)}", fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Pip.glow, letterSpacing = 1.sp)
                Text("PITCH ${pitch.toInt()}°", fontFamily = JetBrainsMono, fontSize = 8.sp, color = Pip.dim)
                if (unreliable) {
                    Text("compass off — wave a figure-8", fontFamily = JetBrainsMono, fontSize = 8.sp, color = Pip.alert)
                }
            }
        }

        // Operator STAT strip (bottom-left).
        StatsHud(character.level, character.hp, character.maxHp, character.caps,
            Modifier.align(Alignment.BottomStart).padding(12.dp))

        // Radar minimap (bottom-right).
        if (g != null) {
            RadarMinimap(sites, g, heading, nearest?.id,
                Modifier.align(Alignment.BottomEnd).padding(12.dp).size(104.dp))
        }

        // Bottom-centre: nearest-site readout + scan.
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (g == null) {
                ReadoutLine("ACQUIRING SATELLITE FIX…")
            } else if (sites.isEmpty()) {
                ReadoutLine(if (scanning) "SCANNING THE WASTELAND…" else "NO SITES MAPPED — SCAN")
            } else if (nearest != null) {
                val d = Geo.distanceMeters(g.latitude, g.longitude, nearest.lat, nearest.lon)
                ReadoutLine("NEAREST · ${nearest.name.uppercase()} · ${Geo.formatDistance(d)}")
            }
            ArButton(if (scanning) "SCANNING…" else "SCAN AREA ▸", Modifier.padding(top = 6.dp)) { vm.scan() }
        }

        // CRT scanline tube over everything (decorative; passes touches through).
        Canvas(Modifier.fillMaxSize()) { crtScanlines(Color.Black.copy(alpha = 0.10f), gap = 3f) }
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

/** A floating site card in Pip-Boy chrome, tinted by threat, sized by distance. */
@Composable
private fun SiteMarker(site: WorldSite, distanceM: Double, size: Float) {
    val accent = arColor(site.type)
    val here = distanceM <= AR_REACH_M
    val scale = 0.82f + size * 0.5f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size((9 * scale).dp).background(accent))
        Box(Modifier.width(1.dp).height((10 * scale).dp).background(accent.copy(alpha = 0.7f)))
        Box(
            Modifier.clip(RoundedCornerShape(2.dp)).background(Pip.bg.copy(alpha = 0.82f))
                .border(1.dp, accent, RoundedCornerShape(2.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(site.name.uppercase(), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = (11 * scale).sp, color = accent, maxLines = 1, textAlign = TextAlign.Center)
                Text(
                    if (here) "◉ HERE · ENGAGE" else "${site.type.label} · ${Geo.formatDistance(distanceM)}",
                    fontFamily = JetBrainsMono, fontSize = (8 * scale).sp,
                    color = if (here) Pip.glow else Pip.dim,
                )
            }
        }
    }
}

/** The tracked objective, projected as a gold diamond. */
@Composable
private fun WaypointMarker(label: String, distanceM: Double) {
    val gold = Color(0xFFFFC542)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("◆", fontFamily = JetBrainsMono, fontSize = 16.sp, color = gold)
        Box(
            Modifier.clip(RoundedCornerShape(2.dp)).background(Pip.bg.copy(alpha = 0.82f))
                .border(1.dp, gold, RoundedCornerShape(2.dp)).padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("◆ ${label.uppercase()}", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                    fontSize = 11.sp, color = gold, maxLines = 1)
                Text("OBJECTIVE · ${Geo.formatDistance(distanceM)}", fontFamily = JetBrainsMono,
                    fontSize = 8.sp, color = gold.copy(alpha = 0.85f))
            }
        }
    }
}

/** A heading ribbon — cardinal labels slide across as you turn; a caret marks dead-ahead. */
@Composable
private fun CompassRibbon(heading: Float, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val wDp = maxWidth
        val marks = listOf(0 to "N", 45 to "NE", 90 to "E", 135 to "SE", 180 to "S", 225 to "SW", 270 to "W", 315 to "NW")
        marks.forEach { (deg, lbl) ->
            if (ArProjection.inView(heading.toDouble(), deg.toDouble())) {
                val xFrac = ArProjection.screenX(heading.toDouble(), deg.toDouble()).toFloat().coerceIn(0f, 1f)
                Text(lbl, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    color = if (lbl.length == 1) Pip.glow else Pip.dim,
                    modifier = Modifier.offset(x = wDp * xFrac - 12.dp).width(24.dp), textAlign = TextAlign.Center)
            }
        }
        Text("▾", fontFamily = JetBrainsMono, fontSize = 12.sp, color = Pip.bright,
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** A top-down radar: player at centre, sites as blips (up = your heading), range rings. */
@Composable
private fun RadarMinimap(
    sites: List<WorldSite>,
    gps: dev.mascwa.pulse.data.weather.DeviceLocation,
    heading: Float,
    nearestId: String?,
    modifier: Modifier,
) {
    Box(modifier.clip(CircleShape).background(Pip.bg.copy(alpha = 0.6f)).border(1.dp, Pip.grid, CircleShape)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f - 2f
            // Range rings.
            drawCircle(Pip.grid.copy(alpha = 0.5f), radius, c, style = Stroke(1f))
            drawCircle(Pip.grid.copy(alpha = 0.3f), radius * 0.5f, c, style = Stroke(1f))
            // Player.
            drawCircle(Pip.glow, 3f, c)
            sites.forEach { s ->
                val bearing = Geo.bearingDegrees(gps.latitude, gps.longitude, s.lat, s.lon)
                val dist = Geo.distanceMeters(gps.latitude, gps.longitude, s.lat, s.lon)
                val rel = Math.toRadians(ArProjection.relativeBearing(heading.toDouble(), bearing))
                val rr = (min(dist / RADAR_RANGE_M, 1.0) * radius).toFloat()
                val bx = c.x + (rr * sin(rel)).toFloat()
                val by = c.y - (rr * cos(rel)).toFloat()
                val col = arColor(s.type)
                drawCircle(if (s.id == nearestId) Pip.glow else col, if (s.id == nearestId) 4f else 3f, Offset(bx, by))
            }
        }
    }
}

/** Operator STAT strip — LVL / HP / CAPS, Pip-framed. */
@Composable
private fun StatsHud(level: Int, hp: Int, maxHp: Int, caps: Int, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(3.dp)).background(Pip.bg.copy(alpha = 0.6f))
            .border(1.dp, Pip.grid, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Text("LVL $level", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Pip.glow)
            Text("HP $hp/$maxHp", fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.mid)
            Text("CAPS $caps", fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.mid)
        }
    }
}

@Composable
private fun ReadoutLine(text: String) {
    Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pip.mid,
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Pip.bg.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun ArButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(3.dp)).background(Pip.bg.copy(alpha = 0.55f))
            .border(1.dp, Pip.bright, RoundedCornerShape(3.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            color = Pip.bright, letterSpacing = 1.sp)
    }
}

/** Threat-tinted marker colour: danger amber-red, the wilds mid-green, safe/trade bright green. */
private fun arColor(type: SiteType): Color = when {
    type.hostile -> Color(0xFFE6FF66) // in-palette alert (a gang camp / den / vault reads hot)
    type.threat >= 2 -> Pip.mid
    else -> Pip.bright
}

/** Cardinal label for a heading in degrees. */
private fun cardinal(deg: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[(((deg / 45f).toInt()) % 8 + 8) % 8]
}
