package dev.mascwa.pulse.feature.ar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
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
import dev.mascwa.pulse.core.telemetry.ElevationField
import dev.mascwa.pulse.core.telemetry.LocalFootprint
import dev.mascwa.pulse.core.telemetry.Setting
import dev.mascwa.pulse.core.telemetry.SiteType
import dev.mascwa.pulse.core.telemetry.WorldSite
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.feature.ar3d.WastelandRenderer
import dev.mascwa.pulse.feature.tacnet.Pip
import dev.mascwa.pulse.feature.tacnet.crtScanlines
import dev.mascwa.pulse.ui.effects.DecryptText
import dev.mascwa.pulse.ui.effects.LocalGlitchEnabled
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import java.util.concurrent.Executors
import kotlin.math.abs
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
 *
 * There's ONE combined AR view: a real Filament-rendered 3D wasteland ([WastelandRenderer] — a transparent GL
 * surface composited over the live camera) with the site labels projected on top of it. The camera also runs
 * an on-device indoor/outdoor classifier ([IndoorOutdoorDetector]): **outdoors** the solid wasteland ground
 * replaces the real floor; **indoors** only a wireframe ground ghost is drawn so it never blocks the room.
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
    // The AR view rides the STABLE motion-gated anchor (not the raw jittery fix) — so the projected wasteland
    // sits still when you're stationary and only tracks when you actually move.
    val gps by vm.anchor.collectAsStateWithLifecycle()
    val moving by vm.moving.collectAsStateWithLifecycle()
    val heading by vm.heading.collectAsStateWithLifecycle()
    val pitch by vm.pitch.collectAsStateWithLifecycle()
    val unreliable by vm.compassUnreliable.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val character by vm.character.collectAsStateWithLifecycle()
    val activeWp by vm.activeWaypoint.collectAsStateWithLifecycle()
    val setting by vm.setting.collectAsStateWithLifecycle()
    // Solid wasteland ground only when we're confident we're OUTSIDE (or in transit). Indoors / not-yet-known →
    // the non-blocking wireframe ground ghost, so a solid floor never obscures the room.
    val indoor = setting != Setting.OUTDOOR && setting != Setting.VEHICLE
    // Real OSM building footprints, projected to the local frame around the current fix (empty → procedural).
    val localBuildings by vm.localBuildings.collectAsStateWithLifecycle()
    // Real DEM elevation field (the invisible ground anchor); null → flat-anchored procedural terrain.
    val elevation by vm.elevation.collectAsStateWithLifecycle()

    // The AR overlay is ONE thing: the Filament wasteland + the site labels together (no mode toggle). It
    // "models" for a beat on entry — a Fallout loading screen while the scene builds.
    var modeling by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2700); modeling = false }

    Box(Modifier.fillMaxSize().background(Pip.bg)) {
        if (hasCamera) {
            // Live camera + an on-device indoor/outdoor analysis pass (feeds the renderer's ground mode).
            CameraPreview(vm.indoorDetector::analyze, Modifier.fillMaxSize())
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

        // 3D wasteland — a transparent Filament GL surface composited over the live camera (immediate vicinity).
        // Always on now: the wasteland IS the AR view, with the site labels drawn on top of it below. The
        // ground is solid outdoors / a wireframe ghost indoors ([indoor], from the camera classifier).
        // Placed early so the Compose HUD chrome (drawn after) stays tappable through the surface's clear pixels.
        if (hasCamera) {
            FilamentLayer(heading, pitch, indoor, localBuildings, elevation, Modifier.fillMaxSize())
        }

        // Fallout hero beacon glow — a soft additive halo over the Filament beacon so the opaque orb reads as
        // emitting light. Outdoors only (the beacon is drawn only outdoors); under the site labels + HUD so
        // those stay crisp. Fades out as you turn away from the beacon's bearing.
        if (hasCamera && !indoor) {
            HeroBeaconBloom(heading, pitch, Modifier.fillMaxSize())
        }

        // Nearest engage-able site (for the readout + radar highlight).
        val g = gps
        val nearest = if (g != null) {
            sites.minByOrNull { Geo.distanceMeters(g.latitude, g.longitude, it.lat, it.lon) }
        } else null

        // --- Site labels — projected over the wasteland (always shown; they ARE the combined AR view) ---
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

        // Fallout grade + frame over the whole AR view — a duotone wash, vignette, tube-curve edges, a horizon
        // haze band, and the amber corner brackets (Ref A). Drawn BELOW the HUD chrome so the stats/radar stay
        // legible; touch passes straight through (no pointerInput).
        if (hasCamera) {
            ArGradeOverlay(pitch, Modifier.fillMaxSize())
            ArCornerBrackets(Modifier.fillMaxSize())
        }

        // --- Fixed HUD chrome ---

        // Centre reticle — a phosphor ring + dot.
        Box(Modifier.align(Alignment.Center).size(48.dp).border(1.dp, Pip.bright.copy(alpha = 0.6f), CircleShape)) {
            Box(Modifier.align(Alignment.Center).size(4.dp).background(Pip.glow, CircleShape))
        }

        // Top chrome — heading ribbon + EXIT/heading, held BELOW the system status bar (clock/battery).
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()) {
            CompassRibbon(heading, Modifier.fillMaxWidth().height(30.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, start = 12.dp, end = 12.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ArButton("‹ EXIT") { onBack() }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${heading.toInt()}° ${cardinal(heading)}", fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Pip.glow, letterSpacing = 1.sp)
                    Text("PITCH ${pitch.toInt()}°", fontFamily = JetBrainsMono, fontSize = 8.sp, color = Pip.dim)
                    Text(settingLabel(setting), fontFamily = JetBrainsMono, fontSize = 8.sp, color = Pip.dim)
                    Text(
                        if (localBuildings.isEmpty()) "STRUCTURES · SCANNED"
                        else "STRUCTURES · ${localBuildings.size} MAPPED",
                        fontFamily = JetBrainsMono, fontSize = 8.sp, color = Pip.dim,
                    )
                    Text(
                        if (moving) "◈ IN MOTION · TRACKING" else "◉ STATIONARY · LOCKED",
                        fontFamily = JetBrainsMono, fontSize = 8.sp,
                        color = if (moving) Pip.glow else Pip.dim,
                    )
                    if (unreliable) {
                        Text("compass off — wave a figure-8", fontFamily = JetBrainsMono, fontSize = 8.sp, color = Pip.alert)
                    }
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
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp),
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
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArButton(if (scanning) "SCANNING…" else "SCAN ▸") { vm.scan() }
            }
        }

        // Fallout loading beat — covers the raw pop-in while the wasteland models on entry.
        if (modeling) {
            WastelandLoading(Modifier.fillMaxSize())
        }

        // CRT scanline tube over everything (decorative; passes touches through).
        Canvas(Modifier.fillMaxSize()) { crtScanlines(Color.Black.copy(alpha = 0.10f), gap = 3f) }
    }
}

/** The diagnostic roll the "modelling the wasteland" boot decrypts in — a RobCo-terminal power-on beat. */
private fun wastelandBootLog(): List<String> = listOf(
    "> INITIALISING VICINITY MODEL",
    "mapping terrain mesh ............ OK",
    "resolving OSM structures ........ OK",
    "sampling DEM elevation grid ..... OK",
    "calibrating optical compass ..... OK",
    "rendering wasteland overlay ..... READY",
)

/**
 * The "modelling the wasteland" loading screen — a cinematic RobCo terminal boot beat over the camera on
 * entry (BootScreen idiom): a phosphor header, a diagnostic roll that DECRYPTS in line-by-line, and a
 * clearance bar filling to 100%. Plays for the ~2.7 s the wasteland models, then the AR view fades in.
 */
@Composable
private fun WastelandLoading(modifier: Modifier) {
    val lines = remember { wastelandBootLog() }
    var shown by remember { mutableStateOf(0) }
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val step = 2200L / lines.size
        for (i in lines.indices) {
            kotlinx.coroutines.delay(step)
            shown = i + 1
            progress = (i + 1f) / lines.size
        }
    }
    Box(modifier.background(Color(0xFF05070A)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "◈ WASTELAND OVERLAY", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 18.sp, color = Pip.glow, letterSpacing = 3.sp,
            )
            Text(
                "RobCo(TM) TERRAIN MODELLER", fontFamily = JetBrainsMono, fontSize = 9.sp,
                color = Pip.dim, letterSpacing = 2.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )
            // Decrypting diagnostic roll — forced on (a deliberate one-time cinematic, like the app cold-open).
            CompositionLocalProvider(LocalGlitchEnabled provides true) {
                Column(Modifier.fillMaxWidth().height(150.dp), verticalArrangement = Arrangement.Top) {
                    lines.take(shown).forEachIndexed { i, ln ->
                        val col = when (i) {
                            0 -> Pip.glow
                            lines.lastIndex -> Pip.bright
                            else -> Pip.mid
                        }
                        DecryptText(
                            ln, JetBrainsMono, 11.sp, col,
                            Modifier.fillMaxWidth().padding(vertical = 2.dp), durationMs = 420,
                        )
                    }
                }
            }
            // Clearance / progress bar.
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().height(6.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Pip.bg.copy(alpha = 0.8f))
                    .border(1.dp, Pip.grid, RoundedCornerShape(2.dp)),
            ) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(6.dp).background(Pip.glow))
            }
            Text(
                "◉ MODELLING THE WASTELAND · ${(progress * 100).toInt()}%",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pip.bright, letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Live back-camera preview via CameraX, bound to the composition's lifecycle. Also binds an [ImageAnalysis]
 * that hands each frame to [analyzer] (the on-device indoor/outdoor classifier) — one camera session drives
 * both the preview and the classification, so nothing fights over the camera.
 */
@Composable
private fun CameraPreview(analyzer: (ImageProxy) -> Unit, modifier: Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { runCatching { analysisExecutor.shutdown() } } }
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
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                        )
                    }
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/**
 * The 3D wasteland layer — a **transparent** Filament SurfaceView floating over the live camera. `setZOrderOnTop`
 * + a `TRANSLUCENT` holder composite it above the camera TextureView while its clear pixels reveal the camera
 * (and the Compose HUD drawn behind it). The [WastelandRenderer]'s native engine is freed when the AR view
 * leaves. [indoor] picks the ground mode: solid wasteland outdoors, wireframe ground ghost indoors.
 */
@Composable
private fun FilamentLayer(
    heading: Float,
    pitch: Float,
    indoor: Boolean,
    buildings: List<LocalFootprint>,
    elevation: ElevationField?,
    modifier: Modifier,
) {
    val renderer = remember { WastelandRenderer() }
    DisposableEffect(Unit) { onDispose { renderer.detach() } } // frees native memory on leave
    // Aim the wasteland camera by the live compass + tilt (main thread — same as the renderer).
    LaunchedEffect(heading, pitch) { renderer.setOrientation(heading, pitch) }
    // Swap solid ground ↔ wireframe ghost as the camera classifier decides indoors/outdoors.
    LaunchedEffect(indoor) { renderer.setIndoor(indoor) }
    // Feed the real geo-anchored OSM footprints (re-projected as you move); empty keeps the procedural skyline.
    LaunchedEffect(buildings) { renderer.setBuildings(buildings) }
    // Anchor the wasteland floor to the real DEM topography (null → flat-anchored procedural).
    LaunchedEffect(elevation) { renderer.setElevation(elevation) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                setZOrderOnTop(true)                      // composite above the camera TextureView
                holder.setFormat(PixelFormat.TRANSLUCENT) // give the surface an alpha channel
                renderer.attach(this)
            }
        },
    )
}

/**
 * A soft additive glow over the Filament hero beacon so the opaque orb reads as *emitting* light (the material
 * can't truly bloom). Aligns to [WastelandRenderer.HERO_AZ_DEG] / [WastelandRenderer.HERO_ELEV_DEG] via the
 * same magic-window projection as the site markers, and fades out as the beacon leaves the view.
 */
@Composable
private fun HeroBeaconBloom(heading: Float, pitch: Float, modifier: Modifier) {
    val az = WastelandRenderer.HERO_AZ_DEG.toDouble()
    val rel = abs(ArProjection.relativeBearing(heading.toDouble(), az))
    if (rel > 46.0) return // beacon well out of view → no glow
    val fade = (1f - (rel / 46.0).toFloat()).coerceIn(0f, 1f)
    val xFrac = ArProjection.screenX(heading.toDouble(), az).toFloat().coerceIn(-0.3f, 1.3f)
    val yFrac = ArProjection.screenY(pitch.toDouble(), WastelandRenderer.HERO_ELEV_DEG.toDouble())
        .toFloat().coerceIn(0f, 1f)
    val glow = Color(0xFF9CF0C0) // pale phosphor green (matches the baked beacon)
    Canvas(modifier) {
        val c = Offset(xFrac * size.width, yFrac * size.height)
        val r = size.minDimension * 0.6f
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to glow.copy(alpha = 0.38f * fade),
                    0.45f to glow.copy(alpha = 0.14f * fade),
                    1f to Color.Transparent,
                ),
                center = c, radius = r,
            ),
            radius = r, center = c, blendMode = BlendMode.Plus,
        )
    }
}

/** The two reference moods (GREEN_NIGHT committed; AMBER_DUSK ready to select later). */
private enum class ArMood { GREEN_NIGHT, AMBER_DUSK }

/** Screen-space grade + frame colours for a mood: corner brackets, the duotone wash, the vignette, the horizon haze. */
private data class ArChrome(val bracket: Color, val grade: Color, val shadow: Color, val haze: Color)

private fun chromeFor(mood: ArMood): ArChrome = when (mood) {
    ArMood.GREEN_NIGHT -> ArChrome(
        bracket = Color(0xFFE8A83C), // amber instrument frame (Ref A)
        grade = Color(0xFF16362A),   // phosphor-teal wash unifying camera + wasteland
        shadow = Color(0xFF04080A),  // near-black vignette
        haze = Color(0xFF2C5E54),    // teal horizon glow
    )
    ArMood.AMBER_DUSK -> ArChrome(
        bracket = Color(0xFFE8A83C),
        grade = Color(0xFF3A1E10),   // warm amber wash
        shadow = Color(0xFF0A0402),
        haze = Color(0xFFC8702A),    // burnt-orange horizon glow
    )
}

/** The committed AR mood — flip to [ArMood.AMBER_DUSK] (with the renderer [Mood]) for the Ref-B look. */
private val AR_MOOD = ArMood.GREEN_NIGHT

/**
 * The Fallout screen grade over the whole AR view (camera + wasteland): a faint duotone wash, a radial
 * vignette, tube-curve edge darkening, and a soft horizon haze band anchored to the camera pitch. Pure
 * Compose Canvas (SrcOver — additive/multiply are no-ops over the camera SurfaceView); drawn below the HUD.
 */
@Composable
private fun ArGradeOverlay(pitch: Float, modifier: Modifier) {
    val c = chromeFor(AR_MOOD)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // (1) duotone wash — a faint tint unifying the real camera with the baked wasteland.
        drawRect(color = c.grade.copy(alpha = 0.10f))
        // (2) radial vignette — clear centre → shadow at the edges.
        drawRect(
            brush = Brush.radialGradient(
                colorStops = arrayOf(0.55f to Color.Transparent, 1f to c.shadow.copy(alpha = 0.5f)),
                center = Offset(w / 2f, h / 2f), radius = maxOf(w, h) * 0.72f,
            ),
        )
        // (3) tube-curve edge darken — thin gradient bands on each side.
        val edge = c.shadow.copy(alpha = 0.35f)
        val bh = h * 0.14f; val bw = w * 0.10f
        drawRect(Brush.verticalGradient(listOf(edge, Color.Transparent), 0f, bh), size = Size(w, bh))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, edge), h - bh, h), Offset(0f, h - bh), Size(w, bh))
        drawRect(Brush.horizontalGradient(listOf(edge, Color.Transparent), 0f, bw), size = Size(bw, h))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, edge), w - bw, w), Offset(w - bw, 0f), Size(bw, h))
        // (4) horizon haze band — glows along the horizon line (from the camera pitch).
        val horizonY = (ArProjection.screenY(pitch.toDouble(), 0.0).toFloat().coerceIn(0.15f, 0.95f)) * h
        val bandH = h * 0.30f
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, c.haze.copy(alpha = 0.20f), Color.Transparent),
                horizonY - bandH / 2f, horizonY + bandH / 2f,
            ),
            topLeft = Offset(0f, horizonY - bandH / 2f), size = Size(w, bandH),
        )
    }
}

/** The amber corner-bracket L-frame (Ref A) — a heavier bottom pair. Drawn crisp, below the HUD so it never washes. */
@Composable
private fun ArCornerBrackets(modifier: Modifier) {
    val col = chromeFor(AR_MOOD).bracket
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val len = size.minDimension * 0.06f
        val m = size.minDimension * 0.035f
        val top = 2.dp.toPx(); val bot = 3.dp.toPx() // heavier bottom pair
        drawLine(col, Offset(m, m), Offset(m + len, m), top); drawLine(col, Offset(m, m), Offset(m, m + len), top)
        drawLine(col, Offset(w - m, m), Offset(w - m - len, m), top); drawLine(col, Offset(w - m, m), Offset(w - m, m + len), top)
        drawLine(col, Offset(m, h - m), Offset(m + len, h - m), bot); drawLine(col, Offset(m, h - m), Offset(m, h - m - len), bot)
        drawLine(col, Offset(w - m, h - m), Offset(w - m - len, h - m), bot); drawLine(col, Offset(w - m, h - m), Offset(w - m, h - m - len), bot)
    }
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

/** The sensed indoor/outdoor read, surfaced so the ground mode is verifiable on-device. */
private fun settingLabel(s: Setting): String = when (s) {
    Setting.OUTDOOR -> "OUTSIDE · SOLID GROUND"
    Setting.VEHICLE -> "IN TRANSIT · SOLID GROUND"
    Setting.INDOOR -> "INSIDE · GROUND GHOST"
    Setting.UNKNOWN -> "SENSING SURROUNDINGS…"
}

/** Cardinal label for a heading in degrees. */
private fun cardinal(deg: Float): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return dirs[(((deg / 45f).toInt()) % 8 + 8) % 8]
}
