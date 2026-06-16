package dev.mascwa.pulse.feature.nav

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

// OpenFreeMap: keyless, no-registration vector tiles (OSM data). We load it then recolour every
// layer into the NIGHTWIRE/cyberpunk look at runtime (red buildings, cyan roads, void background).
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val USER_SOURCE = "nav-user"
private const val USER_LAYER = "nav-user-dot"
private const val FOLLOW_ZOOM = 16.5
private const val FOLLOW_TILT = 50.0
private val WATER = Color(0xFF06121F)   // deep navy so water reads as "off" against the red city

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
    // True = camera tracks GPS heading-up; flips to false the moment the user pans/zooms/rotates.
    var follow by remember { mutableStateOf(true) }

    // One-time map wiring: enable free-roam gestures, load + cyberpunk-ify the style, add the player
    // marker, and drop follow-mode as soon as the user drives the camera by hand.
    LaunchedEffect(mapView) {
        mapView.getMapAsync { ml ->
            map = ml
            ml.uiSettings.apply {
                isScrollGesturesEnabled = true
                isZoomGesturesEnabled = true
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = true
                isDoubleTapGesturesEnabled = true
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true // keep the required OSM/OpenFreeMap attribution
            }
            ml.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                cyberpunkify(style, c)
                addPlayerMarker(style, c)
            }
            ml.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
        }
    }

    // Keep the player marker pinned to the live GPS fix even while free-roaming.
    LaunchedEffect(location, map) {
        val style = map?.style ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(Point.fromLngLat(loc.longitude, loc.latitude))
    }

    // While tracking, follow GPS heading-up. Once the user pans, this no-ops until they recenter.
    LaunchedEffect(location, heading, follow, map) {
        if (!follow) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newCameraPosition(followCamera(loc.latitude, loc.longitude, heading)))
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
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            NavChrome(heading = heading, hasFix = location != null, c = c)
            RecenterButton(
                following = follow,
                c = c,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                onClick = {
                    follow = true
                    val loc = location
                    val m = map
                    if (loc != null && m != null) {
                        m.animateCamera(
                            CameraUpdateFactory.newCameraPosition(followCamera(loc.latitude, loc.longitude, heading)),
                        )
                    }
                },
            )
        }
    }
}

private fun followCamera(lat: Double, lon: Double, heading: Float): CameraPosition =
    CameraPosition.Builder()
        .target(LatLng(lat, lon))
        .zoom(FOLLOW_ZOOM)
        .tilt(FOLLOW_TILT)
        .bearing(heading.toDouble())
        .build()

/** Recolour the loaded OSM style into the cyberpunk palette: void background, red buildings, cyan
 *  road network, dimmed water/land, and readable labels. Works on whatever layers the style ships. */
private fun cyberpunkify(style: Style, c: NightwirePalette) {
    val void = c.void.toArgb()
    val red = c.magenta.toArgb()
    val cyan = c.sky.toArgb()
    val water = WATER.toArgb()
    val label = c.ink.toArgb()
    style.layers.forEach { layer ->
        val id = layer.id.lowercase()
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(void))
            is FillExtrusionLayer -> layer.setProperties(
                PropertyFactory.fillExtrusionColor(red),
                PropertyFactory.fillExtrusionOpacity(0.55f),
            )
            is FillLayer -> when {
                "water" in id -> layer.setProperties(PropertyFactory.fillColor(water))
                "building" in id -> layer.setProperties(PropertyFactory.fillColor(red), PropertyFactory.fillOpacity(0.32f))
                else -> layer.setProperties(PropertyFactory.fillColor(void)) // land/parks blend into the void
            }
            is LineLayer -> when {
                "water" in id || "river" in id || "waterway" in id -> layer.setProperties(PropertyFactory.lineColor(water))
                "building" in id -> layer.setProperties(PropertyFactory.lineColor(red))
                "boundary" in id || "admin" in id -> layer.setProperties(PropertyFactory.lineColor(c.muted.toArgb()))
                else -> layer.setProperties(PropertyFactory.lineColor(cyan)) // roads / rail / paths
            }
            is SymbolLayer -> layer.setProperties(
                PropertyFactory.textColor(label),
                PropertyFactory.textHaloColor(void),
                PropertyFactory.textHaloWidth(1.3f),
            )
            is CircleLayer -> layer.setProperties(PropertyFactory.circleColor(cyan))
        }
    }
}

/** The player position as a glowing cyan dot pinned to the map (correct while panning). */
private fun addPlayerMarker(style: Style, c: NightwirePalette) {
    if (style.getSource(USER_SOURCE) != null) return
    style.addSource(GeoJsonSource(USER_SOURCE))
    style.addLayer(
        CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
            PropertyFactory.circleColor(c.sky.toArgb()),
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleStrokeColor(c.void.toArgb()),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
}

@Composable
private fun RecenterButton(following: Boolean, c: NightwirePalette, modifier: Modifier, onClick: () -> Unit) {
    val tint = if (following) c.accent else c.ink
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(c.panel.copy(alpha = 0.82f))
            .border(1.dp, tint, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            if (following) "◎ TRACKING" else "◎ RECENTER",
            fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint,
        )
    }
}

/** The cyberpunk HUD frame: corner brackets, a heading readout, and a GPS-acquiring notice. */
@Composable
private fun NavChrome(heading: Float, hasFix: Boolean, c: NightwirePalette) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
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
