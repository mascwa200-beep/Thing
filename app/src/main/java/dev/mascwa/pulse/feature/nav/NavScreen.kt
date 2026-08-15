package dev.mascwa.pulse.feature.nav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.objectives.ObjectiveKind
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.data.places.Place
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.core.telemetry.NavGuidance
import dev.mascwa.pulse.core.telemetry.Terminator
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.hudCorners
import dev.mascwa.pulse.feature.common.lcarsBlockShape
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
private const val WAYPOINT_SOURCE = "nav-waypoint"
private const val WAYPOINT_LAYER = "nav-waypoint-dot"
private const val OBJECTIVE_SOURCE = "nav-objective"
private const val OBJECTIVE_LAYER = "nav-objective-icon"
private const val ROUTE_SOURCE = "nav-route"
private const val ROUTE_CASING_LAYER = "nav-route-casing"
private const val ROUTE_LAYER = "nav-route-line"
// Navigation path colours (Cyberpunk-style): a bright gold line over a white casing.
private val ROUTE_GOLD = Color(0xFFFFD23F)
private val ROUTE_CASING = Color(0xFFEAF2FF)
private const val INCIDENT_SOURCE = "nav-incident"
private const val INCIDENT_LAYER = "nav-incident-dot"
private const val NIGHT_SOURCE = "nav-night"
private const val NIGHT_LAYER = "nav-night-fill"
private const val SUN_SOURCE = "nav-subsolar"
private const val SUN_LAYER = "nav-subsolar-dot"
private const val EMPTY_FC = "{\"type\":\"FeatureCollection\",\"features\":[]}"
private const val FOLLOW_ZOOM = 16.5
private const val FOLLOW_TILT = 50.0
// Cyberpunk 2077 map palette (tuned to the reference screenshots).
private val LAND = Color(0xFF080C18)          // near-black navy base (land / parks / background)
private val WATER = Color(0xFF0B1A2E)          // slightly lifted navy so water reads distinct from land
private val BUILDING = Color(0xFFFF2A4E)       // red building mass
private val BUILDING_EDGE = Color(0xFFFF6E8C)  // lighter red footprint outline
private val ROAD = Color(0xFF2DE2E6)           // glowing cyan road network
// Day/night overlay: a cold wash over the night side, a warm dot at the subsolar point.
private val NIGHT_FILL = Color(0xFF040814)
private val SUN_GOLD = Color(0xFFFFC24B)
/** Web Mercator cannot place the poles; every projected latitude stops here. */
private const val MERCATOR_LIMIT = 85.05112878

/** The NAV map. */
@Composable
fun NavScreen(vm: NavViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    PulseScaffold(
        title = "NAV",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(LcarsIcons.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
        },
    ) { innerPadding ->
        NavBody(vm, Modifier.padding(innerPadding))
    }
}

/** The scaffold-free NAV map body — the live MapLibre map. Extracted so it can be hosted both
 *  standalone ([NavScreen]) and inside another screen.
 *
 *  It used to take an ObjectivesViewModel for a MAP | OBJECTIVES sub-switch that has since been
 *  removed; the parameter outlived the feature and was threaded through two signatures without ever
 *  being read. Waypoints come from NavViewModel's own WaypointStore. */
@Composable
fun NavBody(vm: NavViewModel, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val location by vm.location.collectAsState()
    val heading by vm.headingDeg.collectAsState()
    val enabled by vm.enabled.collectAsState()
    val pois by vm.pois.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val nav3d by vm.nav3d.collectAsState()
    val headingUp by vm.headingUp.collectAsState()
    val night by vm.night.collectAsState()
    val selectedPoi by vm.selectedPoi.collectAsState()
    val selectedWaypoint by vm.selectedWaypoint.collectAsState()
    val activeWaypoint by vm.activeWaypoint.collectAsState()
    val allWaypoints by vm.allWaypoints.collectAsState()
    val activeWaypointId by vm.activeWaypointId.collectAsState()
    val route by vm.route.collectAsState()
    val flyTo by vm.flyTo.collectAsState()
    val readout by vm.readout.collectAsState()
    val searchMessage by vm.searchMessage.collectAsState()
    val incidents by vm.incidents.collectAsState()
    val showIncidents by vm.showIncidents.collectAsState()
    val scanNotice by vm.scanNotice.collectAsState()
    var query by remember { mutableStateOf("") }

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
    // Metres per screen pixel at the map centre (MapLibre's own scale-bar input), refreshed on camera idle.
    var scaleMpp by remember { mutableStateOf(0.0) }
    // The basemap has loaded and this screen's layers exist. Every effect below that touches a source
    // keys on it: the map object is published before the style finishes, so an effect that fired in
    // that gap used to find a null style and give up — leaving whatever it was meant to draw missing
    // until something unrelated happened to re-trigger it.
    var styleReady by remember { mutableStateOf(false) }
    // Bumped to force a retry after a failed basemap load.
    var styleAttempt by remember { mutableStateOf(0) }

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
            installNavStyle(ml, c) { styleReady = true }
            ml.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
            ml.addOnMapClickListener { latLng ->
                val pt = ml.projection.toScreenLocation(latLng)
                // Objective icons are the user's own pins — they take tap priority over POIs.
                val objId = ml.queryRenderedFeatures(pt, OBJECTIVE_LAYER).firstOrNull()?.getStringProperty("id")
                if (objId != null) {
                    vm.selectPoi(null)
                    vm.selectWaypoint(objId)
                    return@addOnMapClickListener true
                }
                // Match on the feature's own key, not its name. Place carries no id, and a name
                // is not unique — tapping one branch of a coffee chain used to open whichever
                // branch happened to come first in the list.
                val pid = ml.queryRenderedFeatures(pt, POI_LAYER).firstOrNull()?.getStringProperty("pid")
                val hit = pid?.let { key ->
                    vm.pois.value.entries.firstNotNullOfOrNull { (cat, places) ->
                        places.firstOrNull { poiKey(cat, it) == key }
                    }
                }
                vm.selectWaypoint(null)
                vm.selectPoi(hit)
                hit != null
            }
            // Keep the scale bar in sync with the zoom/centre once the camera settles. Metres per dp via
            // MapLibre's own web-mercator maths (512-px tiles; pixelRatio = density → logical px == dp).
            ml.addOnCameraIdleListener {
                scaleMpp = runCatching {
                    val cam = ml.cameraPosition
                    val lat = cam.target?.latitude ?: 0.0
                    40_075_016.686 * Math.cos(Math.toRadians(lat)) / (512.0 * 2.0.pow(cam.zoom))
                }.getOrDefault(0.0)
            }
            // Long-press anywhere drops a waypoint there + opens its card (track / remove).
            ml.addOnMapLongClickListener { latLng ->
                vm.dropWaypointAt(latLng.latitude, latLng.longitude)
                true
            }
        }
    }

    // Retry the basemap on demand. MapLibre's style callback has no failure branch, so a load that
    // never arrives can only be recovered by asking again.
    LaunchedEffect(styleAttempt) {
        if (styleAttempt == 0) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        styleReady = false
        installNavStyle(m, c) { styleReady = true }
    }

    // Say something once the basemap is clearly not coming. Waiting a beat first keeps the message
    // off the screen during a normal, slightly slow tile fetch.
    var styleSlow by remember { mutableStateOf(false) }
    LaunchedEffect(styleReady, styleAttempt) {
        styleSlow = false
        if (styleReady) return@LaunchedEffect
        kotlinx.coroutines.delay(9_000)
        styleSlow = true
    }

    // Keep the player marker pinned to the live GPS fix even while free-roaming.
    LaunchedEffect(location, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        val loc = location ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(Point.fromLngLat(loc.longitude, loc.latitude))
    }

    // Rebuild the POI marker layer whenever the enabled categories or fetched results change.
    LaunchedEffect(pois, enabled, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        val src = style.getSourceAs<GeoJsonSource>(POI_SOURCE) ?: return@LaunchedEffect
        src.setGeoJson(poiGeoJson(enabled, pois))
    }

    // Render the active objective waypoint (coloured by kind) + the road-following route to it. The
    // route line only appears once the road geometry resolves (no straight-line placeholder).
    LaunchedEffect(activeWaypoint, route, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(WAYPOINT_SOURCE)?.setGeoJson(waypointGeoJson(activeWaypoint))
        style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(routeLineGeoJson(route))
    }

    // Render every tracked objective as a per-kind icon (★ MAIN / ◆ SIDE / ● WORK), active emphasised.
    LaunchedEffect(allWaypoints, activeWaypointId, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(OBJECTIVE_SOURCE)?.setGeoJson(objectiveGeoJson(allWaypoints, activeWaypointId))
    }

    // Redraw the day/night terminator while it is lit. It slides west a quarter of a degree per
    // minute, so a minute between updates is imperceptible; switching it off empties the source
    // rather than leaving stale geometry behind.
    LaunchedEffect(night, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        val fill = style.getSourceAs<GeoJsonSource>(NIGHT_SOURCE) ?: return@LaunchedEffect
        val sun = style.getSourceAs<GeoJsonSource>(SUN_SOURCE)
        if (!night) {
            fill.setGeoJson(EMPTY_FC)
            sun?.setGeoJson(EMPTY_FC)
            return@LaunchedEffect
        }
        while (true) {
            val now = System.currentTimeMillis()
            fill.setGeoJson(nightGeoJson(now))
            sun?.setGeoJson(subSolarGeoJson(now))
            kotlinx.coroutines.delay(60_000)
        }
    }

    // Render nearby safety incidents (amber) when the overlay is lit.
    LaunchedEffect(incidents, showIncidents, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(INCIDENT_SOURCE)
            ?.setGeoJson(incidentGeoJson(if (showIncidents) incidents else emptyList()))
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

    // A successful place search: stop following and fly the camera to the geocoded location.
    LaunchedEffect(flyTo, map) {
        val target = flyTo ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        follow = false
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(target.first, target.second))
                    .zoom(FOLLOW_ZOOM)
                    .tilt(if (nav3d) FOLLOW_TILT else 0.0)
                    .bearing(0.0)
                    .build(),
            ),
        )
        vm.consumeFlyTo()
    }

    Box(modifier.fillMaxSize()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            NavChrome(
                hasFix = location != null,
                basemapMissing = styleSlow && !styleReady,
                onRetryBasemap = { styleAttempt++ },
                c = c,
            )

                // Compass readout (folded in from the old Compass screen): live true-north heading.
                NavCompass(
                    heading = heading,
                    headingUp = headingUp,
                    c = c,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 104.dp),
                )

                // Map scale bar (matches MapLibre's own scale-bar maths).
                ScaleBar(
                    metersPerPixel = scaleMpp,
                    c = c,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 168.dp),
                )

                // Search bar — geocode a place, drop a waypoint, fly there.
                NavSearchBar(
                    query = query,
                    onQuery = { query = it; vm.clearSearchMessage() },
                    onSearch = { if (query.isNotBlank()) vm.search(query) },
                    message = searchMessage,
                    c = c,
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = 12.dp, end = 64.dp, top = 52.dp),
                )

                // Right-edge control cluster.
                Column(
                    Modifier.align(Alignment.TopEnd).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MapControlButton(active = follow, c = c, icon = Icons.Filled.MyLocation) {
                        follow = true
                        val loc = location
                        val m = map
                        if (loc != null && m != null) {
                            m.animateCamera(CameraUpdateFactory.newCameraPosition(followCamera(loc.latitude, loc.longitude, heading, nav3d, headingUp)))
                        }
                    }
                    MapControlButton(active = false, c = c, icon = Icons.Filled.Add) { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
                    MapControlButton(active = false, c = c, icon = Icons.Filled.Remove) { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
                    MapControlButton(active = nav3d, c = c, label = if (nav3d) "3D" else "2D") { vm.set3d(!nav3d) }
                    MapControlButton(active = headingUp, c = c, icon = Icons.Filled.Navigation) { vm.setHeadingUp(!headingUp) }
                    MapControlButton(active = showIncidents, c = c, icon = Icons.Filled.Warning) {
                        centerOf(map, location)?.let { vm.toggleIncidents(it.first, it.second) }
                    }
                    MapControlButton(active = night, c = c, label = "☾") { vm.setNight(!night) }
                    if (activeWaypoint != null) {
                        MapControlButton(active = false, c = c, icon = LcarsIcons.Close) { vm.clearWaypoint() }
                    }
                }

                // Bottom stack: optional POI detail card above the filter bar.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Live navigation readout — distance + driving ETA + turn arrow to the active objective.
                    readout?.let { r ->
                        NavReadoutBanner(readout = r, heading = heading, c = c, onTap = { vm.focusActive() })
                    }
                    selectedWaypoint?.let { wp ->
                        WaypointDetailCard(
                            waypoint = wp,
                            location = location,
                            active = wp.id == activeWaypointId,
                            c = c,
                            onClose = { vm.selectWaypoint(null) },
                            onTrack = { vm.trackWaypoint(wp.id) },
                            onRemove = { vm.removeWaypoint(wp.id) },
                        )
                    }
                    selectedPoi?.let { poi ->
                        PoiDetailCard(
                            poi = poi,
                            location = location,
                            c = c,
                            onClose = { vm.selectPoi(null) },
                            onSetWaypoint = { vm.setWaypointFromPoi(poi) },
                        )
                    }
                    scanNotice?.let { notice ->
                        NavNotice(
                            message = notice.message,
                            isError = notice.isError,
                            c = c,
                            onDismiss = { vm.clearScanNotice() },
                        )
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

/**
 * Load the basemap and install every layer this screen owns, in draw order.
 *
 * [onReady] fires only when the style genuinely loads. MapLibre's callback is success-only — there
 * is no error branch — so a failed tile fetch simply never calls back, which is what the screen
 * watches for to tell the user the basemap is missing rather than leaving a blank rectangle.
 *
 * Order matters: the night wash goes in before the marker layers so it sits underneath the things
 * you tap.
 */
private fun installNavStyle(ml: MapLibreMap, c: NightwirePalette, onReady: () -> Unit) {
    ml.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
        cyberpunkify(style, c)
        ensureBuildingExtrusion(style)
        addNightLayer(style)
        addRouteLayer(style)
        addPoiLayer(style, c)
        addIncidentLayer(style, c)
        addWaypointLayer(style, c)
        addObjectiveIcons(style)
        addObjectiveLayer(style)
        addPlayerMarker(style, c)
        onReady()
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
                .append(",\"pid\":").append(jsonString(poiKey(cat, p)))
                .append(",\"cat\":\"").append(cat.id).append("\"}}")
        }
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/**
 * A stable identity for a POI.
 *
 * [Place] has no id field, so this is built from the one thing that genuinely distinguishes two
 * places: where they are. Written into the feature and compared back verbatim, so there is no
 * float round-trip to disagree about.
 */
private fun poiKey(cat: NavCategory, p: Place): String =
    "${cat.id}:${p.longitude},${p.latitude}"

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
    val land = LAND.toArgb()
    val red = BUILDING.toArgb()
    val redEdge = BUILDING_EDGE.toArgb()
    val cyan = ROAD.toArgb()
    val water = WATER.toArgb()
    val label = c.ink.toArgb()
    style.layers.forEach { layer ->
        val id = layer.id.lowercase()
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(land))
            is FillExtrusionLayer -> layer.setProperties(
                PropertyFactory.fillExtrusionColor(red),
                PropertyFactory.fillExtrusionOpacity(0.6f),
            )
            is FillLayer -> when {
                "water" in id -> layer.setProperties(PropertyFactory.fillColor(water))
                "building" in id -> layer.setProperties(
                    PropertyFactory.fillColor(red),
                    PropertyFactory.fillOpacity(0.45f),
                    PropertyFactory.fillOutlineColor(redEdge),
                )
                else -> layer.setProperties(PropertyFactory.fillColor(land)) // land/parks blend into the base
            }
            is LineLayer -> when {
                "water" in id || "river" in id || "waterway" in id -> layer.setProperties(PropertyFactory.lineColor(water))
                "building" in id -> layer.setProperties(PropertyFactory.lineColor(redEdge))
                "boundary" in id || "admin" in id -> layer.setProperties(PropertyFactory.lineColor(c.muted.toArgb()))
                // roads / rail / paths: glowing cyan (keep the style's zoom-based width, add a soft glow).
                else -> layer.setProperties(PropertyFactory.lineColor(cyan), PropertyFactory.lineBlur(1.4f))
            }
            is SymbolLayer -> layer.setProperties(
                PropertyFactory.textColor(label),
                PropertyFactory.textHaloColor(land),
                PropertyFactory.textHaloWidth(1.3f),
            )
            is CircleLayer -> layer.setProperties(PropertyFactory.circleColor(cyan))
        }
    }
}

/** Make every building a red 3D block: recolour + lower the min-zoom of any extrusion layers, or add
 *  one over the OpenMapTiles "building" source layer if the style has none. Defensive: a wrong source
 *  name degrades to "no extra buildings", never a crash. */
private fun ensureBuildingExtrusion(style: Style) {
    val red = BUILDING.toArgb()
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

/** The navigation path from the player to the active waypoint: a bright gold line over a white casing
 *  (rounded, glowing, no minimap). Two layers on one source. */
private fun addRouteLayer(style: Style) {
    if (style.getSource(ROUTE_SOURCE) != null) return
    style.addSource(GeoJsonSource(ROUTE_SOURCE))
    // White casing underneath (wider) — reads as the route outline.
    style.addLayer(
        LineLayer(ROUTE_CASING_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(ROUTE_CASING.toArgb()),
            PropertyFactory.lineWidth(8f),
            PropertyFactory.lineOpacity(0.85f),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
        ),
    )
    // Gold path on top (narrower) with a soft glow.
    style.addLayer(
        LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
            PropertyFactory.lineColor(ROUTE_GOLD.toArgb()),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineOpacity(0.95f),
            PropertyFactory.lineBlur(1.5f),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
        ),
    )
}

/** A soft coloured halo behind the ACTIVE objective's icon — its "you are tracking this" emphasis. */
private fun addWaypointLayer(style: Style, c: NightwirePalette) {
    if (style.getSource(WAYPOINT_SOURCE) != null) return
    style.addSource(GeoJsonSource(WAYPOINT_SOURCE))
    style.addLayer(
        CircleLayer(WAYPOINT_LAYER, WAYPOINT_SOURCE).withProperties(
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleRadius(20f),
            PropertyFactory.circleOpacity(0.22f),
            PropertyFactory.circleStrokeColor(Expression.get("color")),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeOpacity(0.85f),
        ),
    )
}

/** Register the three procedurally-drawn objective glyphs (★ MAIN gold, ◆ SIDE white, ● WORK green).
 *  Bitmaps, not a glyph font — robust on any map style (no font-stack dependency) and crisp at any zoom. */
private fun addObjectiveIcons(style: Style) {
    if (style.getImage("obj-main") != null) return
    style.addImage("obj-main", objectiveBitmap(ObjectiveKind.MAIN))
    style.addImage("obj-side", objectiveBitmap(ObjectiveKind.SIDE))
    style.addImage("obj-work", objectiveBitmap(ObjectiveKind.WORK))
}

/** Every tracked objective as its per-kind icon; the active one is scaled up for emphasis. */
private fun addObjectiveLayer(style: Style) {
    if (style.getSource(OBJECTIVE_SOURCE) != null) return
    style.addSource(GeoJsonSource(OBJECTIVE_SOURCE))
    style.addLayer(
        SymbolLayer(OBJECTIVE_LAYER, OBJECTIVE_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconSize(
                Expression.switchCase(
                    Expression.eq(Expression.get("active"), Expression.literal(true)), Expression.literal(1.2f),
                    Expression.literal(0.85f),
                ),
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ),
    )
}

/** Draw a single objective glyph bitmap: a filled shape with a dark contrast outline. */
private fun objectiveBitmap(kind: ObjectiveKind): android.graphics.Bitmap {
    val size = 84
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val r = size * 0.32f
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = kind.colorArgb.toInt()
        style = android.graphics.Paint.Style.FILL
    }
    val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF06121A.toInt() // void-navy contrast ring so the icon reads on the dark map
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = size * 0.085f
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    when (kind) {
        ObjectiveKind.MAIN -> {
            val path = starPath(cx, cy, r, r * 0.42f)
            canvas.drawPath(path, outline); canvas.drawPath(path, fill)
        }
        ObjectiveKind.SIDE -> {
            val path = android.graphics.Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
            }
            canvas.drawPath(path, outline); canvas.drawPath(path, fill)
        }
        ObjectiveKind.WORK -> {
            val dot = r * 0.78f
            canvas.drawCircle(cx, cy, dot, outline); canvas.drawCircle(cx, cy, dot, fill)
        }
    }
    return bmp
}

/** A 5-point star path centred at [cx],[cy] (outer/inner radii), top point up. */
private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): android.graphics.Path {
    val path = android.graphics.Path()
    val points = 5
    val step = Math.PI / points
    var angle = -Math.PI / 2 // start at the top
    for (i in 0 until points * 2) {
        val rad = if (i % 2 == 0) outer else inner
        val x = cx + (rad * Math.cos(angle)).toFloat()
        val y = cy + (rad * Math.sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        angle += step
    }
    path.close()
    return path
}

/** GeoJSON for ALL tracked objectives: each carries its per-kind icon name + an active flag. */
private fun objectiveGeoJson(waypoints: List<Waypoint>, activeId: String?): String {
    val features = StringBuilder()
    waypoints.forEachIndexed { i, wp ->
        if (i > 0) features.append(',')
        val icon = when (wp.kind) {
            ObjectiveKind.MAIN -> "obj-main"
            ObjectiveKind.SIDE -> "obj-side"
            ObjectiveKind.WORK -> "obj-work"
        }
        features.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(wp.longitude).append(',').append(wp.latitude)
            .append("]},\"properties\":{\"icon\":\"").append(icon)
            .append("\",\"id\":").append(jsonString(wp.id))
            .append(",\"active\":").append(wp.id == activeId).append("}}")
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/** GeoJSON for the active waypoint marker (coloured by kind), or an empty collection when none. */
private fun waypointGeoJson(wp: Waypoint?): String {
    if (wp == null) return EMPTY_FC
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":" +
        "{\"type\":\"Point\",\"coordinates\":[${wp.longitude},${wp.latitude}]}," +
        "\"properties\":{\"color\":\"${wp.kind.colorHex}\"}}]}"
}

/** GeoJSON LineString for the navigation path: ONLY the road-snapped [route] once it resolves,
 *  else an empty collection. We never draw the straight player→waypoint line — the path always reads
 *  as a real road route, so opening the map shows nothing for the brief moment before routing returns
 *  rather than flashing a straight diagonal that then snaps to roads. */
private fun routeLineGeoJson(route: List<Pair<Double, Double>>): String {
    if (route.size < 2) return EMPTY_FC
    val sb = StringBuilder()
    route.forEachIndexed { i, (lat, lon) ->
        if (i > 0) sb.append(',')
        sb.append('[').append(lon).append(',').append(lat).append(']')
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":" +
        "{\"type\":\"LineString\",\"coordinates\":[$sb]},\"properties\":{}}]}"
}

/** Category POI markers, coloured per-feature via the data-driven "color" property. */
/**
 * The day/night terminator, as a filled night hemisphere plus a dot where the Sun is overhead.
 *
 * Added before every marker layer so the shading sits under the things you actually tap. The fill
 * is deliberately faint: it is a piece of context, not a mask.
 */
private fun addNightLayer(style: Style) {
    if (style.getSource(NIGHT_SOURCE) != null) return
    style.addSource(GeoJsonSource(NIGHT_SOURCE))
    style.addLayer(
        FillLayer(NIGHT_LAYER, NIGHT_SOURCE).withProperties(
            PropertyFactory.fillColor(NIGHT_FILL.toArgb()),
            PropertyFactory.fillOpacity(0.42f),
        ),
    )
    style.addSource(GeoJsonSource(SUN_SOURCE))
    style.addLayer(
        CircleLayer(SUN_LAYER, SUN_SOURCE).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(SUN_GOLD.toArgb()),
            PropertyFactory.circleOpacity(0.9f),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(SUN_GOLD.copy(alpha = 0.35f).toArgb()),
        ),
    )
}

/**
 * The night side as a GeoJSON polygon.
 *
 * Latitudes are clamped to the Web Mercator limit: the projection sends the poles to infinity, and
 * the core's ring closes *across* the dark pole, so an unclamped 90 would be a coordinate the map
 * cannot place.
 */
private fun nightGeoJson(epochMs: Long): String {
    val ring = Terminator.nightPolygon(epochMs, stepDeg = 2.0)
    if (ring.size < 4) return EMPTY_FC
    val coords = ring.joinToString(",") { (lat, lon) ->
        "[${lon},${lat.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT)}]"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}," +
        "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[$coords]]}}]}"
}

/** The point the Sun is directly above right now — noon, somewhere. */
private fun subSolarGeoJson(epochMs: Long): String {
    val s = Terminator.subSolarPoint(epochMs)
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{}," +
        "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${s.longitudeDeg}," +
        "${s.latitudeDeg.coerceIn(-MERCATOR_LIMIT, MERCATOR_LIMIT)}]}}]}"
}

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

/** Amber incident markers — larger + ringed so they read as hazards apart from the POI dots. */
private fun addIncidentLayer(style: Style, c: NightwirePalette) {
    if (style.getSource(INCIDENT_SOURCE) != null) return
    style.addSource(GeoJsonSource(INCIDENT_SOURCE))
    style.addLayer(
        CircleLayer(INCIDENT_LAYER, INCIDENT_SOURCE).withProperties(
            PropertyFactory.circleColor(c.amber.toArgb()),
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleStrokeColor(c.void.toArgb()),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleOpacity(0.9f),
        ),
    )
}

/** FeatureCollection for the incident overlay (empty when the overlay is off). */
private fun incidentGeoJson(incidents: List<IncidentMarker>): String {
    val features = StringBuilder()
    incidents.forEachIndexed { i, it ->
        if (i > 0) features.append(',')
        features.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(it.longitude).append(',').append(it.latitude)
            .append("]},\"properties\":{\"name\":").append(jsonString(it.title)).append("}}")
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/** Square HUD-styled map control button — shows [icon] when given, otherwise the text [label]. */
@Composable
private fun MapControlButton(
    active: Boolean,
    c: NightwirePalette,
    icon: ImageVector? = null,
    label: String? = null,
    onClick: () -> Unit,
) {
    val tint = if (active) c.accent else c.ink
    val shape = lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(c.panel.copy(alpha = 0.82f))
            .border(1.dp, tint, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        } else {
            Text(label.orEmpty(), fontFamily = JetBrainsMono, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint)
        }
    }
}

/**
 * A map scale bar. [metersPerPixel] is metres per MapLibre *logical* pixel (512-tile web-mercator). Since
 * MapLibre's pixelRatio defaults to the display density, one logical pixel == one Android dp — so the
 * value is metres-per-dp and the bar is drawn in dp with no density factor. Picks a "nice" 1/2/5×10ⁿ
 * distance that fits the target width.
 */
@Composable
private fun ScaleBar(metersPerPixel: Double, c: NightwirePalette, modifier: Modifier = Modifier) {
    if (metersPerPixel <= 0.0) return
    val maxMeters = 96.0 * metersPerPixel                 // a ~96dp-wide bar (mpp is metres per dp)
    val nice = niceDistance(maxMeters)
    val barDp = (nice / metersPerPixel).toFloat().dp
    Column(modifier) {
        Text(formatScale(nice), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.ink)
        Box(
            Modifier.padding(top = 2.dp).width(barDp).height(4.dp)
                .background(c.ink.copy(alpha = 0.85f)),
        )
    }
}

private fun niceDistance(max: Double): Double {
    if (max <= 0.0) return 1.0
    val pow = 10.0.pow(floor(log10(max)))
    val n = max / pow
    val nice = when {
        n >= 5.0 -> 5.0
        n >= 2.0 -> 2.0
        else -> 1.0
    }
    return nice * pow
}

private fun formatScale(meters: Double): String =
    if (meters >= 1000.0) {
        val km = meters / 1000.0
        if (km == km.toLong().toDouble()) "${km.toLong()} km" else "%.1f km".format(km)
    } else {
        "${meters.toLong()} m"
    }

/** Live navigation banner: relative turn arrow + objective + distance · driving ETA (or "direct"). */
@Composable
private fun NavReadoutBanner(readout: NavReadout, heading: Float, c: NightwirePalette, onTap: () -> Unit) {
    val arrow = NavGuidance.relativeArrow(readout.bearingDeg, heading.toDouble())
    NeonPanel(Modifier.fillMaxWidth().clickable(onClick = onTap), corners = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, fontFamily = JetBrainsMono, fontSize = 24.sp, color = c.amber,
                modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "◎ ${readout.label}".uppercase(),
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = c.ink, maxLines = 1,
                )
                Text(
                    readout.distanceText + " · " + (readout.etaText ?: "direct"),
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.sky,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!readout.viaRoad) {
                Text("◢ ROUTING…", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
            }
        }
    }
}

/** Detail card for a tapped POI: name, distance, address, and a SET WAYPOINT action. */
@Composable
private fun PoiDetailCard(
    poi: Place,
    location: DeviceLocation?,
    c: NightwirePalette,
    onClose: () -> Unit,
    onSetWaypoint: () -> Unit,
) {
    val dist = location?.let { Geo.formatDistance(Geo.distanceMeters(it.latitude, it.longitude, poi.latitude, poi.longitude)) }
    val context = LocalContext.current
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
            val buttonShape = lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .clip(buttonShape)
                    .background(c.amber.copy(alpha = 0.16f))
                    .border(1.dp, c.amber, buttonShape)
                    .clickable(onClick = onSetWaypoint)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("◢ SET WAYPOINT", fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.amber)
            }
            Box(
                Modifier
                    .clip(buttonShape)
                    .border(1.dp, c.sky, buttonShape)
                    .clickable { openLocationExternally(context, poi.name, poi.latitude, poi.longitude) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("↗ MAPS", fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.sky)
            }
            }
        }
    }
}

/** Detail card for a tapped objective icon: name, kind, distance, and TRACK / REMOVE actions. */
@Composable
private fun WaypointDetailCard(
    waypoint: Waypoint,
    location: DeviceLocation?,
    active: Boolean,
    c: NightwirePalette,
    onClose: () -> Unit,
    onTrack: () -> Unit,
    onRemove: () -> Unit,
) {
    val kindColor = Color(waypoint.kind.colorArgb)
    val context = LocalContext.current
    val dist = location?.let {
        Geo.formatDistance(Geo.distanceMeters(it.latitude, it.longitude, waypoint.latitude, waypoint.longitude))
    }
    NeonPanel(Modifier.fillMaxWidth(), corners = true, borderColor = kindColor) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(kindColor))
                Text(
                    waypoint.label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    color = c.ink, modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Text(
                    "✕", fontFamily = JetBrainsMono, fontSize = 14.sp, color = c.muted,
                    modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
                )
            }
            val meta = buildString {
                append(waypoint.kind.name)
                dist?.let { append(" · ").append(it) }
            }
            Text(meta, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.sky, modifier = Modifier.padding(top = 2.dp))
            waypoint.note?.let {
                Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp))
            }
            val buttonShape = lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .clip(buttonShape)
                        .background((if (active) c.amber else c.accent).copy(alpha = 0.16f))
                        .border(1.dp, if (active) c.amber else c.accent, buttonShape)
                        .clickable(enabled = !active, onClick = onTrack)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (active) "◉ TRACKED" else "◢ TRACK",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (active) c.amber else c.accent,
                    )
                }
                Box(
                    Modifier
                        .clip(buttonShape)
                        .border(1.dp, c.sky, buttonShape)
                        .clickable { openLocationExternally(context, waypoint.label, waypoint.latitude, waypoint.longitude) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("↗ MAPS", fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.sky)
                }
                Box(
                    Modifier
                        .clip(buttonShape)
                        .border(1.dp, c.muted, buttonShape)
                        .clickable(onClick = onRemove)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("✕ REMOVE", fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.muted)
                }
            }
        }
    }
}

/**
 * Open a waypoint in an external maps app via a `geo:` intent (OsmAnd, Organic Maps, Google Maps — for
 * full turn-by-turn). If no maps app handles it, fall back to a share sheet with the coordinates + an
 * OpenStreetMap link. Fully defensive — does nothing on failure.
 */
private fun openLocationExternally(context: Context, label: String, lat: Double, lon: Double) {
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(geo); true }.getOrDefault(false)) return
    val text = "$label\n$lat, $lon\nhttps://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, "Share location").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    val chipShape = lcarsBlockShape(sweep = 10.dp, corner = LcarsCorner.TopStart)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .clip(chipShape)
                .background(c.panel.copy(alpha = 0.92f))
                .border(1.dp, c.accent, chipShape)
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
                    .clip(chipShape)
                    .background(c.panel.copy(alpha = if (on) 0.92f else 0.7f))
                    .border(1.dp, if (on) dot else c.muted, chipShape)
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

/** The cyberpunk HUD frame: corner brackets + a GPS-acquiring notice. */
/** A compact heading readout — the compass feature, folded onto the NAV map. */
@Composable
private fun NavCompass(heading: Float, headingUp: Boolean, c: NightwirePalette, modifier: Modifier = Modifier) {
    // In heading-up mode the map rotates, so North on screen sits at -heading; north-up keeps N up.
    val northRotation = if (headingUp) -heading else 0f
    val shape = lcarsBlockShape(sweep = 10.dp, corner = LcarsCorner.TopStart)
    Row(
        modifier
            .clip(shape)
            .background(c.panel.copy(alpha = 0.9f))
            .border(1.dp, c.accent.copy(alpha = 0.6f), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(26.dp)) {
            val r = size.minDimension / 2f
            val ctr = Offset(size.width / 2f, size.height / 2f)
            drawCircle(c.line, radius = r, center = ctr, style = Stroke(width = 1.5.dp.toPx()))
            rotate(northRotation, ctr) {
                drawLine(c.magenta, ctr, Offset(ctr.x, ctr.y - r * 0.85f), strokeWidth = 2.dp.toPx())
                drawLine(c.muted, ctr, Offset(ctr.x, ctr.y + r * 0.7f), strokeWidth = 1.5.dp.toPx())
            }
        }
        Column {
            Text(
                "${heading.roundToInt() % 360}°",
                fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink, fontWeight = FontWeight.Medium,
            )
            Text(Geo.cardinal(heading.toDouble()), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.accent)
        }
    }
}

@Composable
private fun NavChrome(
    hasFix: Boolean,
    basemapMissing: Boolean,
    onRetryBasemap: () -> Unit,
    c: NightwirePalette,
) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            hudCorners(c.accent, 16.dp.toPx(), 1.5.dp.toPx(), 6.dp.toPx())
        }
        if (!hasFix) {
            Text(
                "ACQUIRING GPS…",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            )
        }
        // The map tiles never arrived. Everything else on this screen still works — the compass,
        // the heading, saved waypoints — so this says what is missing rather than taking over.
        if (basemapMissing) {
            val shape = lcarsBlockShape(sweep = 14.dp, corner = LcarsCorner.TopStart)
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(shape)
                    .background(c.panel.copy(alpha = 0.94f))
                    .border(1.dp, c.amber, shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("NO MAP TILES", fontFamily = ChakraPetch, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.amber)
                Text(
                    "The basemap needs a connection the first time.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "▸ TRY AGAIN",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.accent,
                    modifier = Modifier.padding(top = 10.dp).clickable(onClick = onRetryBasemap).padding(6.dp),
                )
            }
        }
    }
}

/** A one-line map notice (scan outcome), tapped to dismiss. */
@Composable
private fun NavNotice(message: String, isError: Boolean, c: NightwirePalette, onDismiss: () -> Unit) {
    val tint = if (isError) c.amber else c.muted
    val shape = lcarsBlockShape(sweep = 10.dp, corner = LcarsCorner.TopStart)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.panel.copy(alpha = 0.92f))
            .border(1.dp, tint.copy(alpha = 0.7f), shape)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (isError) "⚠" else "·", fontFamily = JetBrainsMono, fontSize = 11.sp, color = tint)
        Text(message, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink, modifier = Modifier.weight(1f))
        Text("✕", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
    }
}

/** A search field that geocodes a place and flies to it; shows a brief "not found" message. */
@Composable
private fun NavSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    message: String?,
    c: NightwirePalette,
    modifier: Modifier,
) {
    val shape = lcarsBlockShape(sweep = 10.dp, corner = LcarsCorner.TopStart)
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(c.panel.copy(alpha = 0.9f))
                .border(1.dp, c.accent.copy(alpha = 0.6f), shape)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("Search a place…", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted)
                    }
                    inner()
                },
            )
            Text("⌕", fontFamily = JetBrainsMono, fontSize = 16.sp, color = c.accent, modifier = Modifier.clickable(onClick = onSearch).padding(6.dp))
        }
        if (message != null) {
            Text(message, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
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
