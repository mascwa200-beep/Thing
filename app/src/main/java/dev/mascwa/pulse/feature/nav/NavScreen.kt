package dev.mascwa.pulse.feature.nav

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.hudCorners
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

// OpenFreeMap: keyless, no-registration vector tiles (OSM data). Phase-2 swaps to a custom dark style.
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun NavScreen(vm: NavViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val location by vm.location.collectAsState()
    val heading by vm.headingDeg.collectAsState()

    DisposableEffect(Unit) {
        vm.start()
        onDispose { vm.stop() }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { /* poll picks it up */ }
    LaunchedEffect(Unit) {
        if (!vm.hasPermission()) permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val mapView = rememberMapViewWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    // Follow GPS + face the heading (heading-up). Cheap instant move; the heading is pre-smoothed.
    LaunchedEffect(location, heading, map) {
        val m = map ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        m.moveCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(loc.latitude, loc.longitude))
                    .zoom(16.5)
                    .tilt(50.0)
                    .bearing(heading.toDouble())
                    .build(),
            ),
        )
    }

    PulseScaffold(
        title = "NAV",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize()) { mv ->
                mv.getMapAsync { ml ->
                    map = ml
                    if (ml.style == null) ml.setStyle(Style.Builder().fromUri(STYLE_URL))
                    ml.uiSettings.apply {
                        isRotateGesturesEnabled = false
                        isCompassEnabled = false
                        isLogoEnabled = false
                        isAttributionEnabled = true // keep the required OSM/OpenFreeMap attribution
                    }
                }
            }
            NavChrome(heading = heading, hasFix = location != null, c = c)
        }
    }
}

/** The cyberpunk overlay: range ring, corner brackets, a centred player chevron and a heading tick. */
@Composable
private fun NavChrome(heading: Float, hasFix: Boolean, c: NightwirePalette) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = minOf(size.width, size.height) * 0.34f
            drawCircle(c.accent.copy(alpha = 0.45f), r, Offset(cx, cy), style = Stroke(1.5f))
            drawCircle(c.accent.copy(alpha = 0.14f), r * 0.6f, Offset(cx, cy), style = Stroke(1f))
            // North tick (rotates opposite the heading so it always points to true north).
            rotate(-heading, Offset(cx, cy)) {
                drawLine(c.magenta, Offset(cx, cy - r), Offset(cx, cy - r - 14f), 3f)
            }
            // Player chevron at centre, pointing up (heading-up map).
            val chevron = Path().apply {
                moveTo(cx, cy - 16f)
                lineTo(cx - 11f, cy + 12f)
                lineTo(cx, cy + 4f)
                lineTo(cx + 11f, cy + 12f)
                close()
            }
            drawPath(chevron, if (hasFix) c.accent else c.faint)
            hudCorners(c.accent, 16.dp.toPx(), 1.5.dp.toPx(), 6.dp.toPx())
        }
        Text(
            "◢ NAV // ${heading.toInt()}° ${Geo.cardinal(heading.toDouble())}",
            fontFamily = ChakraPetch, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )
        if (!hasFix) {
            Text(
                "ACQUIRING GPS…",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }
}

/** A MapLibre [MapView] bound to the composition lifecycle (init before construction). */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Drive the current state immediately (the screen is already resumed when composed).
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}
