package dev.mascwa.pulse.feature.nav

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import dev.mascwa.pulse.data.places.Place
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.feature.common.NeonPanel
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
import org.maplibre.android.style.expressions.Expression
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
private const val POI_SOURCE = "nav-poi"
private const val POI_LAYER = "nav-poi-dot"
private const val FOLLOW_ZOOM = 16.5
private const val FOLLOW_TILT = 50.0
private val WATER = Color(0xFF06121F)   // deep navy so water reads as "off" against the red city

@Composable
fun NavScreen(vm: NavViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val location by vm.location.collectAsState()
    val heading by vm.headingDeg.collectAsState()
    val enabled by vm.enabled.collectAsState()
    val pois by vm.pois.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val nav3d by vm.nav3d.collectAsState()
    val headingUp by vm.headingUp.collectAsState()
    val selectedPoi by vm.selectedPoi.collectAsState()

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
    // True = camera tracks GPS; flips to false the moment the user pans/zooms/rotates by hand.
    var follow by remember { mutableStateOf(true) }

    // One-time map wiring: free-roam gestures, cyberpunk style + red 3D buildings, the player marker,
    // tap-to-select POIs, and dropping follow-mode as soon as the user drives the camera by hand.
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
                ensureBuildingExtrusion(style, c)
                addPoiLayer(style, c)
                addPlayerMarker(style, c)
            }
            ml.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
            ml.addOnMapClickListener { latLng ->
                val pt = ml.projection.toScreenLocation(latLng)
                val name = ml.queryRenderedFeatures(pt, POI_LAYER).firstOrNull()?.getStringProperty("name")
                val hit = name?.let { n -> vm.pois.value.values.flatten().firstOrNull { it.name == n } }
                vm.selectPoi(hit)
                hit != null
            }
        }
    }

    // Keep the player marker pinned to the live GPS fix even while free-roaming.
    LaunchedEffect(location, map) {
        val style = map?.style ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(Point.fromLngLat(loc.longitude, loc.latitude))
    }

    // Rebuild the POI marker layer whenever the enabled categories or fetched results change.
    LaunchedEffect(pois, enabled, map) {
        val style = map?.style ?: return@LaunchedEffect
        val src = style.getSourceAs<GeoJsonSource>(POI_SOURCE) ?: return@LaunchedEffect
        src.setGeoJson(poiGeoJson(enabled, pois))
    }

    // While tracking, follow GPS in the current mode (3D/2D, heading-up/north-up).
    LaunchedEffect(location, heading, follow, nav3d, headingUp, map) {
        if (!follow) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        m.moveCamera(CameraUpdateFactory.newCameraPosition(followCamera(loc.latitude, loc.longitude, heading, nav3d, headingUp)))
    }

    // Mode toggled while free-roaming: re-tilt / re-bearing in place without recentering.
    LaunchedEffect(nav3d, headingUp, map) {
        if (follow) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val cur = m.cameraPosition
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(cur.target)
                    .zoom(cur.zoom)
                    .tilt(if (nav3d) FOLLOW_TILT else 0.0)
                    .bearing(if (headingUp) heading.toDouble() else 0.0)
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
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            NavChrome(heading = heading, hasFix = location != null, c = c)

            // Right-edge control cluster.
            Column(
                Modifier.align(Alignment.TopEnd).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapControlButton("◎", active = follow, c = c) {
                    follow = true
                    val loc = location
                    val m = map
                    if (loc != null && m != null) {
                        m.animateCamera(CameraUpdateFactory.newCameraPosition(followCamera(loc.latitude, loc.longitude, heading, nav3d, headingUp)))
                    }
                }
                MapControlButton("+", active = false, c = c) { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
                MapControlButton("−", active = false, c = c) { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
                MapControlButton(if (nav3d) "3D" else "2D", active = nav3d, c = c) { vm.set3d(!nav3d) }
                MapControlButton("⟲", active = headingUp, c = c) { vm.setHeadingUp(!headingUp) }
            }

            // Bottom stack: optional POI detail card above the filter bar.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selectedPoi?.let { poi ->
                    PoiDetailCard(poi = poi, location = location, c = c, onClose = { vm.selectPoi(null) })
                }
                FilterBar(
                    enabled = enabled,
                    counts = pois.mapValues { it.value.size },
                    scanning = scanning,
                    onScan = { centerOf(map, location)?.let { vm.scan(it.first, it.second) } },
                    onToggle = { cat -> centerOf(map, location)?.let { vm.toggle(cat, it.first, it.second) } },
                    c = c,
                )
            }
        }
    }
}

/** Camera for follow mode: tilt/bearing derive from the view mode (north-up = bearing 0). */
private fun followCamera(lat: Double, lon: Double, heading: Float, nav3d: Boolean, headingUp: Boolean): CameraPosition =
    CameraPosition.Builder()
        .target(LatLng(lat, lon))
        .zoom(FOLLOW_ZOOM)
        .tilt(if (nav3d) FOLLOW_TILT else 0.0)
        .bearing(if (headingUp) heading.toDouble() else 0.0)
        .build()

/** Best map reference point for a POI scan: the current map centre, else the GPS fix. */
private fun centerOf(map: MapLibreMap?, location: DeviceLocation?): Pair<Double, Double>? {
    map?.cameraPosition?.target?.let { return it.latitude to it.longitude }
    location?.let { return it.latitude to it.longitude }
    return null
}

/** Build a GeoJSON FeatureCollection string for the enabled categories' POIs (fed to the source as
 *  a string to avoid a hard dependency on the gson-typed Feature API). */
private fun poiGeoJson(enabled: Set<NavCategory>, pois: Map<NavCategory, List<Place>>): String {
    val features = StringBuilder()
    var first = true
    for (cat in enabled) {
        for (p in pois[cat] ?: emptyList()) {
            if (!first) features.append(',')
            first = false
            features.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(p.longitude).append(',').append(p.latitude)
                .append("]},\"properties\":{\"color\":\"").append(cat.colorHex)
                .append("\",\"name\":").append(jsonString(p.name))
                .append(",\"cat\":\"").append(cat.id).append("\"}}")
        }
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/** Minimal JSON string escaping for POI names. */
private fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) when (ch) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
    }
    return sb.append('"').toString()
}

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
                PropertyFactory.fillExtrusionOpacity(0.6f),
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

/** Make every building a red 3D block: recolour + lower the min-zoom of any extrusion layers, or add
 *  one over the OpenMapTiles "building" source layer if the style has none. Defensive: a wrong source
 *  name degrades to "no extra buildings", never a crash. */
private fun ensureBuildingExtrusion(style: Style, c: NightwirePalette) {
    val red = c.magenta.toArgb()
    val existing = style.layers.filterIsInstance<FillExtrusionLayer>()
    if (existing.isNotEmpty()) {
        existing.forEach { layer ->
            layer.minZoom = 13f
            layer.setProperties(
                PropertyFactory.fillExtrusionColor(red),
                PropertyFactory.fillExtrusionOpacity(0.6f),
            )
        }
        return
    }
    runCatching {
        val layer = FillExtrusionLayer("nav-buildings-3d", "openmaptiles").withSourceLayer("building")
        layer.minZoom = 13f
        layer.setProperties(
            PropertyFactory.fillExtrusionColor(red),
            PropertyFactory.fillExtrusionOpacity(0.6f),
            PropertyFactory.fillExtrusionHeight(Expression.get("render_height")),
            PropertyFactory.fillExtrusionBase(Expression.get("render_min_height")),
        )
        style.addLayer(layer)
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

/** Category POI markers, coloured per-feature via the data-driven "color" property. */
private fun addPoiLayer(style: Style, c: NightwirePalette) {
    if (style.getSource(POI_SOURCE) != null) return
    style.addSource(GeoJsonSource(POI_SOURCE))
    style.addLayer(
        CircleLayer(POI_LAYER, POI_SOURCE).withProperties(
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleRadius(5.5f),
            PropertyFactory.circleStrokeColor(c.void.toArgb()),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleOpacity(0.95f),
        ),
    )
}

/** Square HUD-styled map control button (recenter / zoom / 2D-3D / rotate). */
@Composable
private fun MapControlButton(label: String, active: Boolean, c: NightwirePalette, onClick: () -> Unit) {
    val tint = if (active) c.accent else c.ink
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.panel.copy(alpha = 0.82f))
            .border(1.dp, tint, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint)
    }
}

/** Detail card for a tapped POI: name, distance, address. (SET WAYPOINT arrives in Phase 3.) */
@Composable
private fun PoiDetailCard(poi: Place, location: DeviceLocation?, c: NightwirePalette, onClose: () -> Unit) {
    val dist = location?.let { Geo.formatDistance(Geo.distanceMeters(it.latitude, it.longitude, poi.latitude, poi.longitude)) }
    NeonPanel(Modifier.fillMaxWidth(), corners = true) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    poi.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = c.ink, modifier = Modifier.weight(1f),
                )
                Text(
                    "✕", fontFamily = JetBrainsMono, fontSize = 14.sp, color = c.muted,
                    modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
                )
            }
            if (dist != null) {
                Text("◢ $dist", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.sky, modifier = Modifier.padding(top = 2.dp))
            }
            poi.address?.let {
                Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** The cyberpunk filter bar: a SCAN action + a scrollable row of toggleable POI category buttons. */
@Composable
private fun FilterBar(
    enabled: Set<NavCategory>,
    counts: Map<NavCategory, Int>,
    scanning: Boolean,
    onScan: () -> Unit,
    onToggle: (NavCategory) -> Unit,
    c: NightwirePalette,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(c.panel.copy(alpha = 0.92f))
                .border(1.dp, c.accent, RoundedCornerShape(10.dp))
                .clickable(enabled = !scanning, onClick = onScan)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (scanning) "SCANNING…" else "⟳ SCAN", fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.accent)
        }
        NavCategory.entries.forEach { cat ->
            val on = cat in enabled
            val dot = Color(cat.colorArgb)
            Column(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.panel.copy(alpha = if (on) 0.92f else 0.7f))
                    .border(1.dp, if (on) dot else c.muted, RoundedCornerShape(10.dp))
                    .clickable { onToggle(cat) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(cat.icon, contentDescription = cat.label, tint = if (on) dot else c.muted, modifier = Modifier.size(20.dp))
                Text(
                    if (on) "${cat.label} ${counts[cat] ?: 0}" else cat.label,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = if (on) c.ink else c.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
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
