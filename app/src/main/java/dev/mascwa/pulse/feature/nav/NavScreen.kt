package dev.mascwa.pulse.feature.nav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.ComponentCallbacks2
import android.content.res.Configuration
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
import dev.mascwa.pulse.data.maps.MapLayerCatalog
import dev.mascwa.pulse.data.maps.RainViewerRepository
import dev.mascwa.pulse.data.objectives.ObjectiveKind
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.data.places.Place
import dev.mascwa.pulse.data.radar.Contact
import dev.mascwa.pulse.data.safety.Incident
import dev.mascwa.pulse.data.safety.IncidentType
import dev.mascwa.pulse.data.safety.Severity
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.core.telemetry.Geodesy
import dev.mascwa.pulse.core.telemetry.NavGuidance
import dev.mascwa.pulse.core.telemetry.Terminator
import dev.mascwa.pulse.core.telemetry.TrackLog
import dev.mascwa.pulse.feature.common.ChartSeries
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsTimeChart
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
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterDemSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Point
import dev.mascwa.pulse.core.telemetry.Seismic

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
private const val RELIEF_SOURCE = "nav-relief"
private const val RELIEF_LAYER = "nav-relief-shade"
private const val RAIN_SOURCE = "nav-rain"
private const val RAIN_LAYER = "nav-rain-tiles"
private const val TRAFFIC_SOURCE = "nav-traffic"
private const val TRAFFIC_LAYER = "nav-traffic-sym"
private const val TRAFFIC_ICON = "nav-plane"
private const val QUAKE_SOURCE = "nav-quake"
private const val QUAKE_LAYER = "nav-quake-heat"
private const val TRACK_SOURCE = "nav-track"
private const val TRACK_LAYER = "nav-track-line"
private const val MEASURE_SOURCE = "nav-measure"
private const val MEASURE_LINE_LAYER = "nav-measure-line"
private const val MEASURE_DOT_LAYER = "nav-measure-dot"
/** The trail behind you: a cool violet, distinct from the gold route ahead of you. */
private val TRACK_LINE = Color(0xFFB061FF)
/** The measuring line: a hot magenta that belongs to no other layer. */
private val MEASURE_LINE = Color(0xFFFF3864)
/** TileJSON version every hand-built TileSet declares; the spec requires the field. */
private const val TILEJSON_VERSION = "2.2.0"
private const val EMPTY_FC = "{\"type\":\"FeatureCollection\",\"features\":[]}"
private const val FOLLOW_ZOOM = 16.5
private const val FOLLOW_TILT = 50.0
// Cyberpunk 2077 map palette (tuned to the reference screenshots).
private val LAND = Color(0xFF080C18)          // near-black navy base (land / background)
// Landcover, kept just distinguishable from the base. Parks, woods, beaches and marsh used to be
// flattened into LAND by the catch-all below, which cost the map its green space entirely — and a
// park is where you walk, a wood is a landmark, and a marsh is somewhere not to. Each is lifted a
// few points off the base in its own hue: legible on a dark map without becoming a daylight atlas.
private val GREENSPACE = Color(0xFF0A1A12)     // parks, woods, grass, pitches, cemeteries
private val SANDSPACE = Color(0xFF16140C)      // beach, dune, bare rock
private val ICESPACE = Color(0xFF141A22)       // glacier, permanent snow
private val WETLAND = Color(0xFF0A1A1C)        // marsh, swamp, bog
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
        // Full-bleed map: the rail would eat width the chart genuinely needs.
        rail = false,
        onBack = onBack,
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
    val basemap by vm.basemap.collectAsState()
    val relief by vm.relief.collectAsState()
    val rain by vm.rain.collectAsState()
    val rainFrame by vm.rainFrame.collectAsState()
    val rainFrames by vm.rainFrames.collectAsState()
    val rainPlaying by vm.rainPlaying.collectAsState()
    val routeState by vm.routeState.collectAsState()
    val trafficOn by vm.traffic.collectAsState()
    val seismicOn by vm.seismic.collectAsState()
    val aircraft by vm.aircraft.collectAsState()
    val quakes by vm.quakes.collectAsState()
    val selectedIncident by vm.selectedIncident.collectAsState()
    val trackPoints by vm.trackPoints.collectAsState()
    val trackRecording by vm.trackRecording.collectAsState()
    val profile by vm.profile.collectAsState()
    var layersOpen by remember { mutableStateOf(false) }
    var posFormat by remember { mutableStateOf(PositionFormat.DECIMAL) }
    // Measuring is a mode rather than a gesture: while it is on, a tap adds a corner to the chain
    // instead of selecting whatever happens to be under your finger.
    var measuring by remember { mutableStateOf(false) }
    var measurePoints by remember { mutableStateOf(listOf<Pair<Double, Double>>()) }
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
                if (measuring) {
                    measurePoints = measurePoints + (latLng.latitude to latLng.longitude)
                    return@addOnMapClickListener true
                }
                val pt = ml.projection.toScreenLocation(latLng)
                // Objective icons are the user's own pins — they take tap priority over POIs.
                val objId = ml.queryRenderedFeatures(pt, OBJECTIVE_LAYER).firstOrNull()?.getStringProperty("id")
                if (objId != null) {
                    vm.selectPoi(null)
                    vm.selectIncident(null)
                    vm.selectWaypoint(objId)
                    return@addOnMapClickListener true
                }
                // Incidents next: they are events with a source to read, and they sit above the
                // ambient POI dots in what someone is likely to be reaching for.
                val incidentId = ml.queryRenderedFeatures(pt, INCIDENT_LAYER).firstOrNull()?.getStringProperty("id")
                if (incidentId != null) {
                    vm.selectPoi(null)
                    vm.selectWaypoint(null)
                    vm.selectIncident(incidentId)
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
                vm.selectIncident(null)
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
                // While measuring, the map belongs to the tape; dropping a pin under it would be
                // a second thing happening that nobody asked for.
                if (measuring) return@addOnMapLongClickListener true
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

    // The chosen world, and the relief shading over it. These reshape the style rather than just
    // pushing data into a source, so they are guarded: a style torn down between the effect being
    // scheduled and it running should cost the overlay, not the screen.
    LaunchedEffect(basemap, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        runCatching { applyBasemap(style, basemap) }
    }
    LaunchedEffect(relief, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        runCatching {
            style.getLayerAs<HillshadeLayer>(RELIEF_LAYER)?.setProperties(
                PropertyFactory.visibility(if (relief) Property.VISIBLE else Property.NONE),
            )
        }
    }
    // Precipitation. Each scan lives at its own address, so a new frame is a new source rather
    // than an update to the old one.
    LaunchedEffect(rainFrame, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        runCatching { applyRain(style, rainFrame) }
    }

    // Aircraft and quakes. Both sources are simply emptied when their overlay is off, which is
    // also what the view model publishes, so there is one source of truth for "not shown".
    LaunchedEffect(aircraft, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(TRAFFIC_SOURCE)?.setGeoJson(trafficGeoJson(aircraft))
    }
    LaunchedEffect(quakes, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(QUAKE_SOURCE)?.setGeoJson(quakeGeoJson(quakes))
    }

    LaunchedEffect(measurePoints, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(MEASURE_SOURCE)?.setGeoJson(measureGeoJson(measurePoints))
    }

    LaunchedEffect(trackPoints, map, styleReady) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(TRACK_SOURCE)?.setGeoJson(trackGeoJson(trackPoints))
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

                // Where you are, in whichever notation you last asked for.
                PositionReadout(
                    location = location,
                    format = posFormat,
                    c = c,
                    onCycle = {
                        val all = PositionFormat.entries
                        posFormat = all[(all.indexOf(posFormat) + 1) % all.size]
                    },
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 212.dp),
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
                    MapControlButton(active = layersOpen, c = c, label = "▤") { layersOpen = !layersOpen }
                    MapControlButton(active = measuring, c = c, label = "⇔") {
                        measuring = !measuring
                        // Measuring means you are studying the map, not being carried along by it,
                        // so the camera stops chasing the GPS fix out from under the tape.
                        if (measuring) follow = false
                        // Leaving the mode clears the chain: a measurement left lying on the map
                        // after you have moved on is just clutter you have to remember to dismiss.
                        if (!measuring) measurePoints = emptyList()
                    }
                    if (activeWaypoint != null) {
                        MapControlButton(active = false, c = c, icon = LcarsIcons.Close) { vm.clearWaypoint() }
                    }
                }

                // Bottom stack: optional POI detail card above the filter bar.
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (measuring) {
                        MeasureReadout(
                            points = measurePoints,
                            c = c,
                            onUndo = { measurePoints = measurePoints.dropLast(1) },
                            onClear = { measurePoints = emptyList() },
                        )
                    }
                    // What the road ahead climbs, once the route and its heights are known.
                    profile?.let { ElevationProfile(profile = it, c = c) }
                    // Live navigation readout — distance + driving ETA + turn arrow to the active objective.
                    readout?.let { r ->
                        NavReadoutBanner(
                            readout = r, heading = heading, routeState = routeState,
                            c = c, onTap = { vm.focusActive() },
                        )
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
                    selectedIncident?.let { incident ->
                        IncidentDetailCard(
                            incident = incident,
                            c = c,
                            onClose = { vm.selectIncident(null) },
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
                    if (layersOpen) {
                        LayersPanel(
                            basemap = basemap,
                            relief = relief,
                            rain = rain,
                            rainFrame = rainFrame,
                            rainFrames = rainFrames,
                            rainPlaying = rainPlaying,
                            traffic = trafficOn,
                            aircraftCount = aircraft.size,
                            seismic = seismicOn,
                            night = night,
                            trackRecording = trackRecording,
                            trackPoints = trackPoints,
                            c = c,
                            onBasemap = vm::setBasemap,
                            onRelief = vm::setRelief,
                            onRain = vm::setRain,
                            onRainPlayback = vm::toggleRainPlayback,
                            onTraffic = vm::setTraffic,
                            onSeismic = vm::setSeismic,
                            onNight = vm::setNight,
                            onTrackRecording = vm::setTrackRecording,
                            onClearTrack = vm::clearTrack,
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
        addRasterLayers(style)
        addNightLayer(style)
        addSeismicLayer(style)
        addTrackLayer(style)
        addMeasureLayer(style)
        addRouteLayer(style)
        addPoiLayer(style, c)
        addIncidentLayer(style, c)
        addWaypointLayer(style, c)
        addObjectiveIcons(style)
        addObjectiveLayer(style)
        addTrafficLayer(style, c)
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
    val green = GREENSPACE.toArgb()
    val sand = SANDSPACE.toArgb()
    val ice = ICESPACE.toArgb()
    val wet = WETLAND.toArgb()
    val label = c.ink.toArgb()
    style.layers.forEach { layer ->
        val id = layer.id.lowercase()
        when (layer) {
            is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor(land))
            is FillExtrusionLayer -> layer.setProperties(
                PropertyFactory.fillExtrusionColor(red),
                PropertyFactory.fillExtrusionOpacity(0.6f),
            )
            // Order matters: the specific landcover words are tested before the green catch-all,
            // because an OpenMapTiles layer is commonly named "landcover-sand" or "landcover-ice"
            // and the broader match would otherwise swallow both.
            is FillLayer -> when {
                "water" in id -> layer.setProperties(PropertyFactory.fillColor(water))
                "building" in id -> layer.setProperties(
                    PropertyFactory.fillColor(red),
                    PropertyFactory.fillOpacity(0.45f),
                    PropertyFactory.fillOutlineColor(redEdge),
                )
                "sand" in id || "beach" in id || "dune" in id || "bare" in id ->
                    layer.setProperties(PropertyFactory.fillColor(sand))
                "ice" in id || "glacier" in id || "snow" in id ->
                    layer.setProperties(PropertyFactory.fillColor(ice))
                "wetland" in id || "marsh" in id || "swamp" in id || "bog" in id ->
                    layer.setProperties(PropertyFactory.fillColor(wet))
                "park" in id || "wood" in id || "forest" in id || "grass" in id ||
                    "landcover" in id || "golf" in id || "pitch" in id || "garden" in id ||
                    "cemetery" in id || "scrub" in id || "farmland" in id ->
                    layer.setProperties(PropertyFactory.fillColor(green))
                else -> layer.setProperties(PropertyFactory.fillColor(land)) // built-up landuse blends in
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

/**
 * The raster basemaps and overlays, all created up front and switched by visibility.
 *
 * A MapLibre source's URL is fixed once it exists, so choosing between three basemaps means having
 * all three present rather than rewriting one. That costs nothing while they are hidden: tiles are
 * only fetched for a source some visible layer actually references.
 *
 * They go in *below* everything this screen draws and *above* the vector basemap, so a raster
 * basemap covers the styled map underneath while the markers, routes and night wash stay on top.
 */
private fun addRasterLayers(style: Style) {
    if (style.getSource(RELIEF_SOURCE) != null) return
    for (base in MapLayerCatalog.Basemap.entries) {
        val url = base.tileUrl ?: continue
        val tiles = TileSet(TILEJSON_VERSION, url).apply { maxZoom = base.maxZoom }
        style.addSource(RasterSource(basemapSourceId(base), tiles, base.tileSize))
        style.addLayer(
            RasterLayer(basemapLayerId(base), basemapSourceId(base)).withProperties(
                PropertyFactory.visibility(Property.NONE),
                // Imagery and topo maps are both brighter than this app; take the edge off so the
                // cyan routes and coloured pins still read against them.
                PropertyFactory.rasterBrightnessMax(0.86f),
                PropertyFactory.rasterSaturation(-0.12f),
                PropertyFactory.rasterFadeDuration(220f),
            ),
        )
    }
    // Elevation tiles carry height in their pixels; the encoding tells the renderer how to read it,
    // and getting it wrong yields plausible-looking nonsense rather than an error.
    val dem = TileSet(TILEJSON_VERSION, MapLayerCatalog.TERRAIN_DEM_URL).apply {
        maxZoom = MapLayerCatalog.TERRAIN_DEM_MAX_ZOOM
        encoding = MapLayerCatalog.TERRAIN_DEM_ENCODING
    }
    style.addSource(RasterDemSource(RELIEF_SOURCE, dem, 256))
    style.addLayer(
        HillshadeLayer(RELIEF_LAYER, RELIEF_SOURCE).withProperties(
            PropertyFactory.visibility(Property.NONE),
            PropertyFactory.hillshadeExaggeration(0.55f),
            PropertyFactory.hillshadeShadowColor(Color(0xFF000814).toArgb()),
            PropertyFactory.hillshadeHighlightColor(Color(0xFF2DE2E6).toArgb()),
            PropertyFactory.hillshadeAccentColor(Color(0xFF0B1A2E).toArgb()),
        ),
    )
}

private fun basemapSourceId(b: MapLayerCatalog.Basemap) = "nav-base-${b.name.lowercase()}"
private fun basemapLayerId(b: MapLayerCatalog.Basemap) = "nav-base-${b.name.lowercase()}-tiles"

/** Show the chosen raster basemap and hide the others; NIGHTWIRE simply hides them all. */
private fun applyBasemap(style: Style, chosen: MapLayerCatalog.Basemap) {
    for (base in MapLayerCatalog.Basemap.entries) {
        if (base.tileUrl == null) continue
        style.getLayerAs<RasterLayer>(basemapLayerId(base))?.setProperties(
            PropertyFactory.visibility(if (base == chosen) Property.VISIBLE else Property.NONE),
        )
    }
}

/**
 * Swap in a precipitation frame, or clear the overlay when [frame] is null.
 *
 * Each frame lives at its own URL, so a new one means a new source rather than an update. The
 * layer is torn down first: removing a source still referenced by a layer is refused.
 */
private fun applyRain(style: Style, frame: RainViewerRepository.RadarFrame?) {
    style.removeLayer(RAIN_LAYER)
    style.removeSource(RAIN_SOURCE)
    if (frame == null) return
    val tiles = TileSet(TILEJSON_VERSION, frame.tileUrl).apply { maxZoom = 12f }
    style.addSource(RasterSource(RAIN_SOURCE, tiles, 512))
    val rain = RasterLayer(RAIN_LAYER, RAIN_SOURCE).withProperties(
        PropertyFactory.rasterOpacity(0.62f),
        PropertyFactory.rasterFadeDuration(300f),
    )
    // Above the ground, below the things you navigate by: rain should not hide your own route.
    if (style.getLayer(ROUTE_CASING_LAYER) != null) style.addLayerBelow(rain, ROUTE_CASING_LAYER)
    else style.addLayer(rain)
}

/**
 * The measuring line: a chain of tapped points with a dot at each corner.
 *
 * Drawn above everything else this screen owns, because while you are measuring it is the thing
 * you are looking at.
 */
private fun addMeasureLayer(style: Style) {
    if (style.getSource(MEASURE_SOURCE) != null) return
    style.addSource(GeoJsonSource(MEASURE_SOURCE))
    style.addLayer(
        LineLayer(MEASURE_LINE_LAYER, MEASURE_SOURCE).withProperties(
            PropertyFactory.lineColor(MEASURE_LINE.toArgb()),
            PropertyFactory.lineWidth(2.5f),
            PropertyFactory.lineDasharray(arrayOf(2.5f, 1.5f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        ),
    )
    style.addLayer(
        CircleLayer(MEASURE_DOT_LAYER, MEASURE_SOURCE).withProperties(
            PropertyFactory.circleRadius(4.5f),
            PropertyFactory.circleColor(MEASURE_LINE.toArgb()),
            PropertyFactory.circleStrokeWidth(1.5f),
            PropertyFactory.circleStrokeColor(LAND.toArgb()),
        ),
    )
}

/**
 * The measuring chain as both a line and its corner points.
 *
 * A LineString alone would draw the line with nothing at the corners, and a circle layer over a
 * LineString draws nothing at all — a circle layer needs point geometry. So the collection carries
 * both, and each layer picks up the geometry it can render.
 */
private fun measureGeoJson(points: List<Pair<Double, Double>>): String {
    if (points.isEmpty()) return EMPTY_FC
    val features = StringBuilder()
    points.forEachIndexed { i, (lat, lon) ->
        if (i > 0) features.append(',')
        features.append("{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\"," +
            "\"coordinates\":[").append(lon).append(',').append(lat).append("]}}")
    }
    if (points.size >= 2) {
        val line = points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
        features.append(",{\"type\":\"Feature\",\"properties\":{},\"geometry\":" +
            "{\"type\":\"LineString\",\"coordinates\":[$line]}}")
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/**
 * The breadcrumb trail: where you have been, as opposed to where you are going.
 *
 * Deliberately a different colour and thinner than the route line — one is a record and the other
 * is an instruction, and confusing them on a map you are navigating by would be bad.
 */
private fun addTrackLayer(style: Style) {
    if (style.getSource(TRACK_SOURCE) != null) return
    style.addSource(GeoJsonSource(TRACK_SOURCE))
    style.addLayer(
        LineLayer(TRACK_LAYER, TRACK_SOURCE).withProperties(
            PropertyFactory.lineColor(TRACK_LINE.toArgb()),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineOpacity(0.75f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
}

private fun trackGeoJson(points: List<TrackLog.TrackPoint>): String {
    if (points.size < 2) return EMPTY_FC
    val sb = StringBuilder()
    points.forEachIndexed { i, p ->
        if (i > 0) sb.append(',')
        sb.append('[').append(p.longitudeDeg).append(',').append(p.latitudeDeg).append(']')
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":" +
        "{\"type\":\"LineString\",\"coordinates\":[$sb]},\"properties\":{}}]}"
}

/**
 * Aircraft overhead, and recent earthquakes as a heatmap.
 *
 * The quakes are a heatmap rather than dots on purpose: a scatter of points says where events were
 * recorded, while a magnitude-weighted density says where the ground is actually restless, which is
 * the question anyone looking at this is asking.
 */
private fun addTrafficLayer(style: Style, c: NightwirePalette) {
    if (style.getSource(TRAFFIC_SOURCE) != null) return
    style.addImage(TRAFFIC_ICON, planeBitmap(c.sky.toArgb()))
    style.addSource(GeoJsonSource(TRAFFIC_SOURCE))
    style.addLayer(
        SymbolLayer(TRAFFIC_LAYER, TRAFFIC_SOURCE).withProperties(
            PropertyFactory.iconImage(TRAFFIC_ICON),
            // Rotate with the map, not the screen: an aircraft symbol that keeps pointing the same
            // way while the map turns is worse than no symbol at all.
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(9f),
            PropertyFactory.textColor(c.sky.toArgb()),
            PropertyFactory.textHaloColor(LAND.toArgb()),
            PropertyFactory.textHaloWidth(1.2f),
            PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
            // Labels, unlike the symbols, are allowed to collide — so a busy sky thins its own
            // callsigns out instead of turning into a wall of text.
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textOptional(true),
        ),
    )
}

private fun addSeismicLayer(style: Style) {
    if (style.getSource(QUAKE_SOURCE) != null) return
    style.addSource(GeoJsonSource(QUAKE_SOURCE))
    style.addLayer(
        HeatmapLayer(QUAKE_LAYER, QUAKE_SOURCE).withProperties(
            // Magnitude is logarithmic, so the weight ramp is steep on purpose: a 6 should not read
            // as merely three times a 2.
            PropertyFactory.heatmapWeight(
                Expression.interpolate(
                    Expression.linear(), Expression.get("mag"),
                    Expression.stop(1.0, 0.08), Expression.stop(4.0, 0.35), Expression.stop(7.0, 1.0),
                ),
            ),
            PropertyFactory.heatmapIntensity(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(0, 1.0), Expression.stop(10, 2.6),
                ),
            ),
            PropertyFactory.heatmapColor(
                Expression.interpolate(
                    Expression.linear(), Expression.heatmapDensity(),
                    Expression.stop(0.0, Expression.rgba(0, 0, 0, 0)),
                    Expression.stop(0.25, Expression.rgba(45, 226, 230, 0.35)),
                    Expression.stop(0.55, Expression.rgba(255, 197, 66, 0.55)),
                    Expression.stop(1.0, Expression.rgba(255, 42, 78, 0.85)),
                ),
            ),
            // A fixed pixel radius would make a continent-wide picture at low zoom and specks at
            // high zoom; growing it with the zoom keeps the blobs about the same size on the ground.
            PropertyFactory.heatmapRadius(
                Expression.interpolate(
                    Expression.linear(), Expression.zoom(),
                    Expression.stop(0, 14.0), Expression.stop(8, 40.0), Expression.stop(14, 70.0),
                ),
            ),
            PropertyFactory.heatmapOpacity(0.85f),
        ),
    )
}

/** Aircraft symbols: a plain delta pointing up, rotated to the track by the layer. */
private fun planeBitmap(argb: Int): android.graphics.Bitmap {
    val size = 30
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = argb
        style = android.graphics.Paint.Style.FILL
    }
    val path = android.graphics.Path().apply {
        moveTo(size / 2f, 3f)
        lineTo(size - 6f, size - 5f)
        lineTo(size / 2f, size - 10f)
        lineTo(6f, size - 5f)
        close()
    }
    canvas.drawPath(path, paint)
    return bmp
}

private fun trafficGeoJson(contacts: List<Contact>): String {
    if (contacts.isEmpty()) return EMPTY_FC
    val features = StringBuilder()
    contacts.forEachIndexed { i, ct ->
        if (i > 0) features.append(',')
        features.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(ct.longitude).append(',').append(ct.latitude)
            .append("]},\"properties\":{\"bearing\":").append(ct.trackDeg ?: 0.0)
            .append(",\"label\":").append(jsonString(ct.label)).append("}}")
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

private fun quakeGeoJson(contacts: List<Contact>): String {
    if (contacts.isEmpty()) return EMPTY_FC
    val features = StringBuilder()
    var written = 0
    for (ct in contacts) {
        // A quake with no magnitude cannot be weighted, and weighting it as zero would quietly
        // shrink the picture. Leave it out and let the layer describe what it can measure.
        val mag = ct.magnitude ?: continue
        if (written > 0) features.append(',')
        written++
        features.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(ct.longitude).append(',').append(ct.latitude)
            .append("]},\"properties\":{\"mag\":").append(mag).append("}}")
    }
    return if (written == 0) EMPTY_FC else "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

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

/** Amber incident markers — larger + ringed so they read as hazards apart from the POI dots. */
private fun addIncidentLayer(style: Style, c: NightwirePalette) {
    if (style.getSource(INCIDENT_SOURCE) != null) return
    style.addSource(GeoJsonSource(INCIDENT_SOURCE))
    style.addLayer(
        CircleLayer(INCIDENT_LAYER, INCIDENT_SOURCE).withProperties(
            // Per-feature, because the feed says how bad each one is and a uniform amber threw
            // that away.
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleRadius(Expression.get("size")),
            PropertyFactory.circleStrokeColor(c.void.toArgb()),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleOpacity(0.9f),
        ),
    )
}

/** FeatureCollection for the incident overlay (empty when the overlay is off). */
private fun incidentGeoJson(incidents: List<Incident>): String {
    val features = StringBuilder()
    incidents.forEachIndexed { i, it ->
        if (i > 0) features.append(',')
        val severity = runCatching { Severity.valueOf(it.severity) }.getOrDefault(Severity.LOW)
        features.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(it.longitude).append(',').append(it.latitude)
            .append("]},\"properties\":{\"id\":").append(jsonString(it.id))
            .append(",\"color\":\"").append(severityHex(severity))
            .append("\",\"size\":").append(severityRadius(severity)).append("}}")
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/** Severity as colour: the same green→red ramp the rest of the app reads as "how bad". */
private fun severityHex(s: Severity): String = when (s) {
    Severity.LOW -> "#5CFF8F"
    Severity.MODERATE -> "#FFC542"
    Severity.HIGH -> "#FF8A3D"
    Severity.EXTREME -> "#FF2A4E"
}

private fun severityRadius(s: Severity): Float = when (s) {
    Severity.LOW -> 5f
    Severity.MODERATE -> 7f
    Severity.HIGH -> 9f
    Severity.EXTREME -> 11f
}

/** The tapped incident, with everything the feed actually said about it. */
@Composable
private fun IncidentDetailCard(
    incident: Incident,
    c: NightwirePalette,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val severity = runCatching { Severity.valueOf(incident.severity) }.getOrDefault(Severity.LOW)
    val type = runCatching { IncidentType.valueOf(incident.type) }.getOrDefault(IncidentType.OTHER)
    val tint = Color(android.graphics.Color.parseColor(severityHex(severity)))
    // NeonPanel puts its content in a Box, so everything below has to sit inside one layout or the
    // lines would stack on top of each other.
    NeonPanel(Modifier.fillMaxWidth(), corners = true) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${type.label.uppercase()} · ${severity.name}",
                    fontFamily = ChakraPetch, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                    modifier = Modifier.clickable(onClick = onClose).padding(4.dp),
                )
            }
            Text(
                incident.title,
                fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                buildList {
                    add(Geo.formatDistance(incident.distanceMeters))
                    add(Geo.cardinal(incident.bearing))
                    incident.magnitude?.let { add("M%.1f".format(java.util.Locale.US, it)) }
                    if (incident.timeEpochMs > 0) add(minutesAgo(incident.timeEpochMs))
                    add(incident.source)
                }.joinToString(" · "),
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Same facts as the safety list, from the same tested core, so the two surfaces
            // cannot describe one earthquake differently.
            val facts = Seismic.compactFacts(
                depthKm = incident.depthKm,
                tsunami = incident.tsunami,
                pagerAlert = incident.pagerAlert,
                magType = incident.magType,
            )
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString("  ·  "),
                    fontFamily = JetBrainsMono, fontSize = 10.sp,
                    color = if (incident.tsunami) c.magenta else c.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            incident.magnitude?.let { m ->
                incident.depthKm?.let { d ->
                    Text(
                        Seismic.impact(m, d),
                        fontFamily = ChakraPetch, fontSize = 12.sp, color = c.ink,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            incident.url?.takeIf { it.isNotBlank() }?.let { url ->
                Text(
                    "▸ READ THE REPORT",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.accent,
                    modifier = Modifier.padding(top = 8.dp).clickable { openUrlExternally(context, url) },
                )
            }
        }
    }
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
private fun NavReadoutBanner(
    readout: NavReadout,
    heading: Float,
    routeState: NavViewModel.RouteState,
    c: NightwirePalette,
    onTap: () -> Unit,
) {
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
                    // The arrow says which way to turn; this says how far round. Both come from
                    // NavGuidance, which has been able to phrase it since it was written and was
                    // only ever asked for the glyph.
                    buildList {
                        add(readout.distanceText)
                        add(readout.etaText ?: "direct")
                        NavGuidance.turnHint(readout.bearingDeg, heading.toDouble())?.let { add(it) }
                    }.joinToString(" · "),
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.sky,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // The actual next turn, once the router has told us what the road does. It leads
                // rather than follows: "Turn right onto The Mall in 170 m" is the instruction, and
                // the bearing above it is the compass reading that stands in when there is none.
                readout.maneuverText?.let { text ->
                    Text(
                        "▸ ${text.uppercase()}",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        color = c.ink, maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    readout.thenText?.let { then ->
                        Text(
                            "then $then",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
                // Where the road actually ends. Amber rather than the readout's cyan, because this
                // qualifies the numbers directly above it rather than adding to them.
                readout.reachNote?.let {
                    Text(
                        it,
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
            // ⚠️ "ROUTING…" used to show for every reason the road route was absent, forever. An
            // unreachable destination, a rate-limited server and a genuine no-route are all
            // finished states, and the distance beside this line is already a real straight-line
            // reading — so say which of the two you are looking at.
            if (!readout.viaRoad) {
                when (routeState) {
                    NavViewModel.RouteState.UNAVAILABLE -> Text(
                        "◢ NO ROAD ROUTE — DIRECT LINE",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber,
                    )
                    NavViewModel.RouteState.RESOLVING -> Text(
                        "◢ ROUTING…",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    )
                    else -> Unit
                }
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
/** Open a source report in whatever the device uses for links; a missing browser is not a crash. */
private fun openUrlExternally(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

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

/**
 * The layer drawer: which world the map draws, and what is laid over it.
 *
 * Each tile service is credited by name. Two of these licences require it, and in any case a map
 * ought to say where its picture came from.
 */
@Composable
private fun LayersPanel(
    basemap: MapLayerCatalog.Basemap,
    relief: Boolean,
    rain: Boolean,
    rainFrame: RainViewerRepository.RadarFrame?,
    rainFrames: List<RainViewerRepository.RadarFrame>,
    rainPlaying: Boolean,
    traffic: Boolean,
    aircraftCount: Int,
    seismic: Boolean,
    night: Boolean,
    trackRecording: Boolean,
    trackPoints: List<TrackLog.TrackPoint>,
    c: NightwirePalette,
    onBasemap: (MapLayerCatalog.Basemap) -> Unit,
    onRelief: (Boolean) -> Unit,
    onRain: (Boolean) -> Unit,
    onRainPlayback: () -> Unit,
    onTraffic: (Boolean) -> Unit,
    onSeismic: (Boolean) -> Unit,
    onNight: (Boolean) -> Unit,
    onTrackRecording: (Boolean) -> Unit,
    onClearTrack: () -> Unit,
) {
    val shape = lcarsBlockShape(sweep = 14.dp, corner = LcarsCorner.TopStart)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.panel.copy(alpha = 0.95f))
            .border(1.dp, c.accent.copy(alpha = 0.7f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("BASEMAP", fontFamily = ChakraPetch, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.accent)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapLayerCatalog.Basemap.entries.forEach { base ->
                LayerChip(
                    label = base.label,
                    detail = base.blurb,
                    on = base == basemap,
                    c = c,
                    onClick = { onBasemap(base) },
                )
            }
        }
        Text(
            basemap.attribution,
            fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
        )

        Text(
            "OVERLAYS",
            fontFamily = ChakraPetch, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.accent,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LayerChip(
                label = "RAIN",
                // The frame's own timestamp, so an overlay held over from a failed refresh still
                // says how old the picture is instead of implying it is current.
                detail = when {
                    !rain -> "Precipitation radar"
                    rainFrame == null -> "No frame yet"
                    else -> "Scanned ${minutesAgo(rainFrame.timeEpochMs)}"
                },
                on = rain,
                c = c,
                onClick = { onRain(!rain) },
            )
            LayerChip(
                label = "TRAFFIC",
                detail = if (traffic) "${aircraftCount} overhead" else "Aircraft overhead",
                on = traffic,
                c = c,
                onClick = { onTraffic(!traffic) },
            )
            LayerChip("SEISMIC", "Recent quakes, by strength", seismic, c) { onSeismic(!seismic) }
            LayerChip("RELIEF", "Hillshaded terrain", relief, c) { onRelief(!relief) }
            LayerChip("NIGHT", "Where the Sun has set", night, c) { onNight(!night) }
        }
        // Whether the rain is coming towards you or going away — which a single frame cannot say,
        // and which is the only reason anyone opens a rain radar. Hidden unless there is a sequence
        // to run: one frame is a picture, not a loop.
        if (rain && rainFrames.size > 1) {
            val spanMinutes = (rainFrames.last().timeEpochMs - rainFrames.first().timeEpochMs) / 60_000L
            val position = rainFrames.indexOfFirst { it.timeEpochMs == rainFrame?.timeEpochMs }
            LayerChip(
                label = if (rainPlaying) "❚❚ PAUSE" else "▶ REPLAY",
                detail = if (rainPlaying && position >= 0) {
                    "${minutesAgo(rainFrames[position].timeEpochMs)} · ${position + 1}/${rainFrames.size}"
                } else {
                    "Last ${spanMinutes / 60} h, ${rainFrames.size} scans"
                },
                on = rainPlaying,
                c = c,
                onClick = onRainPlayback,
            )
        }

        Text(
            "TRACK",
            fontFamily = ChakraPetch, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = c.accent,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LayerChip(
                label = if (trackRecording) "RECORDING" else "RECORD",
                detail = trackSummary(trackPoints),
                on = trackRecording,
                c = c,
                onClick = { onTrackRecording(!trackRecording) },
            )
            if (trackPoints.isNotEmpty()) {
                LayerChip("ERASE", "Forget the trail", false, c, onClick = onClearTrack)
            }
        }
        val credits = buildList {
            if (rain) add(MapLayerCatalog.RAIN_ATTRIBUTION)
            if (relief) add(MapLayerCatalog.TERRAIN_DEM_ATTRIBUTION)
        }
        if (credits.isNotEmpty()) {
            Text(credits.joinToString(" · "), fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
        }
    }
}

@Composable
private fun LayerChip(
    label: String,
    detail: String,
    on: Boolean,
    c: NightwirePalette,
    onClick: () -> Unit,
) {
    val shape = lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)
    Column(
        Modifier
            .clip(shape)
            .background(if (on) c.accent.copy(alpha = 0.18f) else c.raise.copy(alpha = 0.5f))
            .border(1.dp, if (on) c.accent else c.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontFamily = ChakraPetch, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (on) c.accent else c.ink,
        )
        Text(detail, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
    }
}

/** How far the trail has come, and how long it took. */
private fun trackSummary(points: List<TrackLog.TrackPoint>): String {
    if (points.size < 2) return "Nothing recorded yet"
    val distance = Geo.formatDistance(TrackLog.distanceMeters(points))
    val minutes = (TrackLog.durationMs(points) / 60_000L).toInt()
    val climb = TrackLog.ascentMeters(points).roundToInt()
    return buildList {
        add(distance)
        if (minutes >= 1) add(if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min")
        if (climb >= 10) add("↑${climb} m")
    }.joinToString(" · ")
}

/** "4 min ago" / "just now" — a frame's age, in the plainest words available. */
private fun minutesAgo(epochMs: Long): String {
    val mins = ((System.currentTimeMillis() - epochMs) / 60_000L).toInt()
    return when {
        mins <= 0 -> "just now"
        mins == 1 -> "1 min ago"
        mins < 90 -> "$mins min ago"
        else -> "${mins / 60} h ago"
    }
}

/**
 * Where you are, in the notation you asked for.
 *
 * Decimal degrees are what a phone shows and what a URL wants; degrees-minutes-seconds is what a
 * paper chart is ruled in; MGRS is what you read over a radio, because a grid reference survives
 * being spoken and a string of decimals does not. Tapping cycles between them.
 */
@Composable
private fun PositionReadout(
    location: DeviceLocation?,
    format: PositionFormat,
    c: NightwirePalette,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (location == null) return
    val shape = lcarsBlockShape(sweep = 10.dp, corner = LcarsCorner.TopStart)
    val text = when (format) {
        PositionFormat.DECIMAL ->
            "%.5f, %.5f".format(java.util.Locale.US, location.latitude, location.longitude)
        PositionFormat.DMS ->
            "${dms(location.latitude, "N", "S")}  ${dms(location.longitude, "E", "W")}"
        // Null outside the UTM bands, which is the polar regions -- say so rather than print a
        // grid reference that does not exist there.
        PositionFormat.MGRS ->
            Geodesy.toMgrs(location.latitude, location.longitude) ?: "OUTSIDE THE UTM GRID"
    }
    Column(
        modifier
            .clip(shape)
            .background(c.panel.copy(alpha = 0.9f))
            .border(1.dp, c.accent.copy(alpha = 0.5f), shape)
            .clickable(onClick = onCycle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(format.label, fontFamily = ChakraPetch, fontSize = 8.sp, color = c.accent)
        Text(text, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink)
    }
}

/** Which notation the position readout is showing. */
private enum class PositionFormat(val label: String) {
    DECIMAL("LAT / LON"),
    DMS("DEG MIN SEC"),
    MGRS("MGRS"),
}

/**
 * One axis in degrees, minutes and seconds, with the hemisphere letter.
 *
 * Rounded to tenths of an arc-second *first*, then split. Splitting first and rounding each part
 * afterwards is the obvious way to write this and it prints things like `179°59'60.0"`, because
 * 59.96 seconds rounds up to a full minute that has nowhere to go.
 */
private fun dms(value: Double, positive: String, negative: String): String {
    val sign = if (value < 0) negative else positive
    val tenths = Math.round(kotlin.math.abs(value) * 36_000.0)
    val sec = (tenths % 600L) / 10.0
    val totalMinutes = tenths / 600L
    return "%d°%02d'%04.1f\"%s".format(
        java.util.Locale.US, totalMinutes / 60L, totalMinutes % 60L, sec, sign,
    )
}

/**
 * What the route ahead climbs, drawn against distance rather than time.
 *
 * The chart kit's horizontal axis is a Long because it was written for real time; here the number
 * is metres, which is why it is given its own label formatter rather than a clock.
 */
@Composable
private fun ElevationProfile(profile: RouteElevation, c: NightwirePalette) {
    val points = remember(profile) {
        profile.distancesM.indices
            .take(profile.elevationsM.size)
            .map { profile.distancesM[it].toLong() to profile.elevationsM[it] }
    }
    if (points.size < 2) return
    val shape = lcarsBlockShape(sweep = 10.dp, corner = LcarsCorner.TopStart)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.panel.copy(alpha = 0.92f))
            .border(1.dp, c.accent.copy(alpha = 0.5f), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PROFILE",
                fontFamily = ChakraPetch, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.accent,
                modifier = Modifier.weight(1f),
            )
            // Null when the route is flat enough that saying anything would be noise.
            profile.summary.describe()?.let {
                Text(it, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
            }
        }
        LcarsTimeChart(
            series = listOf(ChartSeries(label = "Elevation", points = points, color = c.sky, filled = true)),
            modifier = Modifier.fillMaxWidth().height(70.dp).padding(top = 4.dp),
            yTicks = 3,
            xTicks = 3,
            valueFormat = { "${it.roundToInt()}" },
            xFormat = { Geo.formatDistance(it.toDouble()) },
        )
    }
}

/**
 * The measuring readout: how long the chain is, and which way its ends lie.
 *
 * Distances are great-circle, so a chain drawn across a continent is the real distance rather than
 * the length of the line as projected on screen — which at high latitudes are very different
 * numbers.
 */
@Composable
private fun MeasureReadout(
    points: List<Pair<Double, Double>>,
    c: NightwirePalette,
    onUndo: () -> Unit,
    onClear: () -> Unit,
) {
    val shape = lcarsBlockShape(sweep = 10.dp, corner = LcarsCorner.TopStart)
    val total = remember(points) {
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += Geodesy.distanceMeters(
                points[i - 1].first, points[i - 1].second, points[i].first, points[i].second,
            )
        }
        sum
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.panel.copy(alpha = 0.93f))
            .border(1.dp, MEASURE_LINE.copy(alpha = 0.8f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("MEASURE", fontFamily = ChakraPetch, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = MEASURE_LINE)
            Text(
                when {
                    points.isEmpty() -> "Tap the map to start"
                    points.size == 1 -> "Tap again to measure from here"
                    else -> {
                        val bearing = Geodesy.initialBearing(
                            points.first().first, points.first().second,
                            points.last().first, points.last().second,
                        )
                        "${Geo.formatDistance(total)} · ${bearing.roundToInt()}° ${Geodesy.cardinal(bearing)} · " +
                            "${points.size - 1} leg${if (points.size == 2) "" else "s"}"
                    }
                },
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (points.isNotEmpty()) {
            Text(
                "UNDO",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                modifier = Modifier.clickable(onClick = onUndo).padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                "CLEAR",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 6.dp, vertical = 4.dp),
            )
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
    // A map holds tile bitmaps, and the renderer will happily keep them until the system kills the
    // process for it. MapView has an onLowMemory that nothing was calling: the Activity callback it
    // normally rides on never reaches a view hosted inside a composable, so it is subscribed here.
    DisposableEffect(mapView) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory()
            }
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onConfigurationChanged(newConfig: Configuration) {}
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
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
