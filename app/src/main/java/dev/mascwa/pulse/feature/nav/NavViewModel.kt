package dev.mascwa.pulse.feature.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.RouteProgress
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.objectives.ObjectiveKind
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.data.maps.MapLayerCatalog
import dev.mascwa.pulse.data.maps.RainViewerRepository
import dev.mascwa.pulse.data.objectives.WaypointStore
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.places.Place
import dev.mascwa.pulse.data.places.RoutingRepository
import dev.mascwa.pulse.data.radar.Contact
import dev.mascwa.pulse.data.radar.ContactKind
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.data.safety.Incident
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Feeds the cyberpunk NAV map: a polled GPS fix (offline-capable, GMS-optional), the live true-north
 * heading for the heading-up camera, and the toggleable POI "legend" (Overpass categories rendered
 * as map markers). Sensors/poll run only while the screen calls [start]/[stop].
 */
/**
 * The outcome of the last POI scan.
 *
 * Every category fetch used to collapse to `emptyList()` on failure, so a stretch of countryside
 * with no cafés and a dead Overpass server produced exactly the same blank map. This says which
 * one happened.
 */
data class ScanNotice(val message: String, val isError: Boolean)

/** Live navigation readout for the active objective shown as a banner on the NAV map. */
data class NavReadout(
    val label: String,
    val distanceText: String,
    /** Driving ETA once a road route resolves; null = straight-line ("direct") only. */
    val etaText: String?,
    val viaRoad: Boolean,
    /** True-north bearing to the objective, for the relative turn arrow. */
    val bearingDeg: Double,
)

class NavViewModel(
    private val locationProvider: LocationProvider,
    private val compass: CompassController,
    private val overpass: OverpassRepository,
    private val settings: SettingsRepository,
    private val waypointStore: WaypointStore,
    private val safety: SafetyRepository,
    private val routing: RoutingRepository,
    private val rainViewer: RainViewerRepository,
    private val radar: RadarRepository,
) : ViewModel() {

    /** The objective/waypoint currently tracked on the map (gold main / white side / green work), or null. */
    val activeWaypoint: StateFlow<Waypoint?> =
        waypointStore.active.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Every saved objective/waypoint — rendered on the map as a per-kind icon (active one emphasised). */
    val allWaypoints: StateFlow<List<Waypoint>> =
        waypointStore.waypoints.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The active waypoint's id (drives icon emphasis without re-deriving from the list). */
    val activeWaypointId: StateFlow<String?> =
        waypointStore.activeId.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _location = MutableStateFlow<DeviceLocation?>(null)
    val location: StateFlow<DeviceLocation?> = _location.asStateFlow()

    /** 3D tilted view (vs flat 2D) and heading-up rotation (vs north-up) — persisted in settings. */
    private val _nav3d = MutableStateFlow(true)
    val nav3d: StateFlow<Boolean> = _nav3d.asStateFlow()
    private val _headingUp = MutableStateFlow(false)
    val headingUp: StateFlow<Boolean> = _headingUp.asStateFlow()

    /** Shade the night half of the world (the day/night terminator) — persisted like the other view modes. */
    private val _night = MutableStateFlow(false)
    val night: StateFlow<Boolean> = _night.asStateFlow()

    /** Which world the map draws: the vector style, satellite imagery, or topographic tiles. */
    private val _basemap = MutableStateFlow(MapLayerCatalog.Basemap.NIGHTWIRE)
    val basemap: StateFlow<MapLayerCatalog.Basemap> = _basemap.asStateFlow()

    /** Hillshaded relief from elevation tiles, drawn over whichever basemap is chosen. */
    private val _relief = MutableStateFlow(false)
    val relief: StateFlow<Boolean> = _relief.asStateFlow()

    /** Live precipitation radar. Null frame = the overlay is off or RainViewer had nothing. */
    private val _rain = MutableStateFlow(false)
    val rain: StateFlow<Boolean> = _rain.asStateFlow()
    private val _rainFrame = MutableStateFlow<RainViewerRepository.RadarFrame?>(null)
    val rainFrame: StateFlow<RainViewerRepository.RadarFrame?> = _rainFrame.asStateFlow()
    private var rainJob: Job? = null

    /**
     * Aircraft overhead and recent earthquakes, both drawn on the map.
     *
     * One fetch serves both — the radar repository returns aircraft, the station and quakes
     * together — so the two overlays share a single refresh loop rather than each running one.
     */
    private val _traffic = MutableStateFlow(false)
    val traffic: StateFlow<Boolean> = _traffic.asStateFlow()
    private val _seismic = MutableStateFlow(false)
    val seismic: StateFlow<Boolean> = _seismic.asStateFlow()
    private val _aircraft = MutableStateFlow<List<Contact>>(emptyList())
    val aircraft: StateFlow<List<Contact>> = _aircraft.asStateFlow()
    private val _quakes = MutableStateFlow<List<Contact>>(emptyList())
    val quakes: StateFlow<List<Contact>> = _quakes.asStateFlow()
    private var radarJob: Job? = null

    /** The POI the user tapped on the map (drives the detail card); null = nothing selected. */
    private val _selectedPoi = MutableStateFlow<Place?>(null)
    val selectedPoi: StateFlow<Place?> = _selectedPoi.asStateFlow()

    /** The objective icon the user tapped on the map (drives its detail card); null = none. */
    private val _selectedWaypointId = MutableStateFlow<String?>(null)
    val selectedWaypoint: StateFlow<Waypoint?> =
        combine(_selectedWaypointId, waypointStore.waypoints) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** One-shot camera target from a successful place search (screen consumes it). */
    private val _flyTo = MutableStateFlow<Pair<Double, Double>?>(null)
    val flyTo: StateFlow<Pair<Double, Double>?> = _flyTo.asStateFlow()

    private val _searchMessage = MutableStateFlow<String?>(null)
    val searchMessage: StateFlow<String?> = _searchMessage.asStateFlow()

    /** Road-snapped path (lat,lon points following streets) from the player to the active waypoint —
     *  empty when nothing is tracked or routing failed (the screen falls back to a straight line). */
    private val _route = MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val route: StateFlow<List<Pair<Double, Double>>> = _route.asStateFlow()
    /** Road distance + driving duration for the active route (null until/unless routing resolves). */
    private val _routeInfo = MutableStateFlow<RoutingRepository.RoadRoute?>(null)
    private var lastRouteWpId: String? = null
    private var lastRouteStart: DeviceLocation? = null

    /**
     * Live navigation readout for the active objective: how far + (if a road route resolved) the driving
     * ETA, plus the bearing to it for the turn arrow. Falls back to straight-line distance ("direct")
     * before the road route is known, so the map always tells you how far you have to go.
     */
    val readout: StateFlow<NavReadout?> =
        combine(activeWaypoint, _location, _routeInfo, _route) { wp, loc, info, routePts ->
            if (wp == null || loc == null) return@combine null
            val straight = Geo.distanceMeters(loc.latitude, loc.longitude, wp.latitude, wp.longitude)
            // Live distance remaining along the road route — counts down on every GPS tick without
            // re-routing. Falls back to OSRM's total, then straight-line.
            val remaining = if (info != null && routePts.size >= 2)
                RouteProgress.remainingMeters(routePts, loc.latitude, loc.longitude) else null
            val meters = remaining ?: info?.distanceMeters ?: straight
            val etaText = when {
                info != null && remaining != null && info.distanceMeters > 0 ->
                    formatEta(info.durationSeconds * (remaining / info.distanceMeters))
                info != null -> formatEta(info.durationSeconds)
                else -> null
            }
            NavReadout(
                label = wp.label,
                distanceText = Geo.formatDistance(meters),
                etaText = etaText,
                viaRoad = info != null,
                bearingDeg = Geo.bearingDegrees(loc.latitude, loc.longitude, wp.latitude, wp.longitude),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            runCatching { settings.current() }.getOrNull()?.let {
                _nav3d.value = it.nav3d
                _headingUp.value = it.navHeadingUp
                _night.value = it.navNight
                _relief.value = it.navRelief
                _basemap.value = runCatching { MapLayerCatalog.Basemap.valueOf(it.navBasemap) }
                    .getOrDefault(MapLayerCatalog.Basemap.NIGHTWIRE)
                // Restoring the rain overlay has to go through the setter: the layer needs a frame,
                // and only the setter starts the loop that fetches one.
                if (it.navRain) setRain(true)
                _traffic.value = it.navTraffic
                _seismic.value = it.navSeismic
                if (it.navTraffic || it.navSeismic) restartRadar()
            }
        }
        // Keep a road-following route to the active waypoint, refreshed when it changes or the player
        // moves a meaningful distance — throttled so we don't hammer the routing server on each GPS tick.
        viewModelScope.launch {
            combine(activeWaypoint, _location) { wp, loc -> wp to loc }.collectLatest { (wp, loc) ->
                if (wp == null || loc == null) {
                    _route.value = emptyList()
                    _routeInfo.value = null
                    lastRouteWpId = null
                    lastRouteStart = null
                    return@collectLatest
                }
                // A different objective: drop the previous path at once so the old road route never
                // lingers on the map while the new one is computed (we redraw only when it resolves).
                if (wp.id != lastRouteWpId) { _route.value = emptyList(); _routeInfo.value = null }
                val movedFar = lastRouteStart?.let {
                    Geo.distanceMeters(it.latitude, it.longitude, loc.latitude, loc.longitude) > 60
                } ?: true
                if (wp.id == lastRouteWpId && !movedFar && _route.value.isNotEmpty()) return@collectLatest
                delay(350) // debounce GPS chatter before hitting the routing server
                val r = runCatching { routing.route(loc.latitude, loc.longitude, wp.latitude, wp.longitude) }.getOrNull()
                if (r != null && r.points.size >= 2) {
                    _route.value = r.points
                    _routeInfo.value = r
                    lastRouteWpId = wp.id
                    lastRouteStart = loc
                }
            }
        }
    }

    fun set3d(on: Boolean) {
        _nav3d.value = on
        viewModelScope.launch { runCatching { settings.update { s -> s.copy(nav3d = on) } } }
    }

    fun setHeadingUp(on: Boolean) {
        _headingUp.value = on
        viewModelScope.launch { runCatching { settings.update { s -> s.copy(navHeadingUp = on) } } }
    }

    fun setNight(on: Boolean) {
        _night.value = on
        viewModelScope.launch { runCatching { settings.update { s -> s.copy(navNight = on) } } }
    }

    fun setBasemap(b: MapLayerCatalog.Basemap) {
        _basemap.value = b
        viewModelScope.launch { runCatching { settings.update { s -> s.copy(navBasemap = b.name) } } }
    }

    fun setRelief(on: Boolean) {
        _relief.value = on
        viewModelScope.launch { runCatching { settings.update { s -> s.copy(navRelief = on) } } }
    }

    /**
     * Turn the precipitation overlay on or off.
     *
     * While it is on the frame is refreshed on a slow loop — the radar scans every ten minutes, and
     * the repository holds a floor of its own, so this cannot become a poll.
     */
    fun setRain(on: Boolean) {
        _rain.value = on
        viewModelScope.launch { runCatching { settings.update { s -> s.copy(navRain = on) } } }
        rainJob?.cancel()
        if (!on) {
            _rainFrame.value = null
            return
        }
        rainJob = viewModelScope.launch {
            while (isActive) {
                _rainFrame.value = runCatching { rainViewer.latest() }.getOrNull()
                delay(5 * 60_000L)
            }
        }
    }

    fun setTraffic(on: Boolean) {
        _traffic.value = on
        viewModelScope.launch { runCatching { settings.update { s -> s.copy(navTraffic = on) } } }
        restartRadar()
    }

    fun setSeismic(on: Boolean) {
        _seismic.value = on
        viewModelScope.launch { runCatching { settings.update { s -> s.copy(navSeismic = on) } } }
        restartRadar()
    }

    /**
     * Keep the aircraft and quake overlays fed, at a pace set by what is actually shown.
     *
     * Aircraft move; earthquakes have already happened. So the loop runs fast only while traffic is
     * on, and drops to a crawl when the map is only showing seismic history. With both overlays off
     * it stops entirely and drops what it was holding, so a hidden layer costs nothing.
     */
    private fun restartRadar() {
        radarJob?.cancel()
        if (!_traffic.value && !_seismic.value) {
            _aircraft.value = emptyList()
            _quakes.value = emptyList()
            return
        }
        radarJob = viewModelScope.launch {
            while (isActive) {
                val loc = _location.value
                if (loc != null) {
                    val data = runCatching { radar.fetch(loc.latitude, loc.longitude, false).data }.getOrNull()
                    if (data != null) {
                        _aircraft.value = if (_traffic.value) {
                            data.contacts.filter { it.kind == ContactKind.AIRCRAFT.name }
                        } else {
                            emptyList()
                        }
                        _quakes.value = if (_seismic.value) {
                            data.contacts.filter { it.kind == ContactKind.QUAKE.name }
                        } else {
                            emptyList()
                        }
                    }
                }
                delay(if (_traffic.value) TRAFFIC_REFRESH_MS else SEISMIC_REFRESH_MS)
            }
        }
    }

    /** Re-centre the map on the active objective (tapping the nav readout banner). */
    fun focusActive() {
        val wp = activeWaypoint.value ?: return
        _flyTo.value = wp.latitude to wp.longitude
    }

    private fun formatEta(seconds: Double): String {
        val mins = (seconds / 60.0).roundToInt()
        return when {
            mins < 1 -> "<1 min"
            mins < 60 -> "$mins min"
            else -> "${mins / 60} h ${mins % 60} min"
        }
    }

    fun selectPoi(place: Place?) { _selectedPoi.value = place }

    /** Select/deselect a tapped objective icon (its detail card). */
    fun selectWaypoint(id: String?) { _selectedWaypointId.value = id }

    /** Make a tapped objective the active tracked waypoint (route + halo follow it). */
    fun trackWaypoint(id: String) {
        viewModelScope.launch { runCatching { waypointStore.setActive(id) } }
    }

    /** Delete a tapped objective and close its card. */
    fun removeWaypoint(id: String) {
        _selectedWaypointId.value = null
        viewModelScope.launch { runCatching { waypointStore.remove(id) } }
    }

    /** Search for a place by name/address: geocode it, drop a (white) waypoint there, and fly to it. */
    fun search(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            _searchMessage.value = null
            val coords = runCatching { locationProvider.geocode(q) }.getOrNull()
            if (coords == null) {
                _searchMessage.value = "Couldn't find \"$q\"."
            } else {
                runCatching { waypointStore.add(q, coords.first, coords.second, ObjectiveKind.SIDE) }
                _flyTo.value = coords
            }
        }
    }

    /** Clear the one-shot fly-to once the screen has animated to it. */
    fun consumeFlyTo() { _flyTo.value = null }

    fun clearSearchMessage() { _searchMessage.value = null }

    /** Set the tapped POI as the active map waypoint (plain/white) and close the detail card. */
    fun setWaypointFromPoi(place: Place) {
        viewModelScope.launch {
            runCatching { waypointStore.add(place.name, place.latitude, place.longitude, ObjectiveKind.SIDE) }
        }
        _selectedPoi.value = null
    }

    /** Long-press on the map → drop a waypoint at that point and open its card (to track or remove it). */
    fun dropWaypointAt(lat: Double, lon: Double) {
        viewModelScope.launch {
            val label = String.format(java.util.Locale.US, "Pin %.4f, %.4f", lat, lon)
            val wp = runCatching { waypointStore.add(label, lat, lon, ObjectiveKind.SIDE) }.getOrNull()
            if (wp != null) {
                _selectedPoi.value = null
                _selectedWaypointId.value = wp.id
            }
        }
    }

    /** Stop tracking the active waypoint (clears the marker + route from the map). */
    fun clearWaypoint() {
        viewModelScope.launch { runCatching { waypointStore.setActive(null) } }
    }

    /** Smoothed true-north heading in degrees (0..360); drives the heading-up camera. */
    val headingDeg: StateFlow<Float> =
        compass.reading
            .map { it.trueAzimuth }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /** Currently-enabled POI categories (the lit-up legend chips). */
    private val _enabled = MutableStateFlow<Set<NavCategory>>(emptySet())
    val enabled: StateFlow<Set<NavCategory>> = _enabled.asStateFlow()

    /** Fetched POIs per category; the screen renders markers for the [enabled] ones. */
    private val _pois = MutableStateFlow<Map<NavCategory, List<Place>>>(emptyMap())
    val pois: StateFlow<Map<NavCategory, List<Place>>> = _pois.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** How the last scan went — null when it went fine and the markers speak for themselves. */
    private val _scanNotice = MutableStateFlow<ScanNotice?>(null)
    val scanNotice: StateFlow<ScanNotice?> = _scanNotice.asStateFlow()

    fun clearScanNotice() { _scanNotice.value = null }

    /** One category's fetch: whether it answered at all, and how many places it had. */
    private data class CategoryOutcome(val reached: Boolean, val count: Int)

    /** Nearby safety incidents + whether the overlay is lit (folded in from the old Map screen). */
    /**
      * Nearby safety incidents, carried whole.
      *
      * They used to be flattened to a coordinate and a title on the way in, which threw away the
      * type, severity, magnitude, time and source link the feed had already delivered — so the map
      * could draw a dot and nothing else, and tapping one had nothing to show.
      */
    private val _incidents = MutableStateFlow<List<Incident>>(emptyList())
    val incidents: StateFlow<List<Incident>> = _incidents.asStateFlow()

    /** The incident dot the user tapped, if any. */
    private val _selectedIncidentId = MutableStateFlow<String?>(null)
    val selectedIncident: StateFlow<Incident?> =
        combine(_selectedIncidentId, _incidents) { id, list -> list.firstOrNull { it.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun selectIncident(id: String?) { _selectedIncidentId.value = id }
    private val _showIncidents = MutableStateFlow(false)
    val showIncidents: StateFlow<Boolean> = _showIncidents.asStateFlow()
    private var incidentJob: Job? = null

    private var pollJob: Job? = null
    private var scanJob: Job? = null

    fun hasPermission(): Boolean = locationProvider.hasPermission()

    /** Toggle the incident overlay; fetch around [lat],[lon] the first time it's lit. */
    fun toggleIncidents(lat: Double, lon: Double) {
        val now = !_showIncidents.value
        _showIncidents.value = now
        if (now && _incidents.value.isEmpty()) scanIncidents(lat, lon)
    }

    /** Fetch nearby safety incidents (quakes/disasters/alerts) around [lat],[lon]. */
    fun scanIncidents(lat: Double, lon: Double) {
        incidentJob?.cancel()
        incidentJob = viewModelScope.launch {
            _incidents.value = runCatching {
                safety.fetch(lat, lon, false).data.incidents
                    .filter { it.distanceMeters > 0 }
                    .take(40)
            }.getOrDefault(emptyList())
        }
    }

    /** Toggle a category and (re)scan the area around [lat],[lon] for the enabled set. */
    fun toggle(category: NavCategory, lat: Double, lon: Double) {
        _enabled.value = _enabled.value.toMutableSet().apply { if (!add(category)) remove(category) }
        scan(lat, lon)
    }

    /** Fetch every enabled category around [lat],[lon] (the map centre) and publish the markers. */
    fun scan(lat: Double, lon: Double) {
        val cats = _enabled.value
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _scanning.value = true
            _scanNotice.value = null
            try {
                // Drop markers for categories switched off before anything new arrives.
                _pois.update { current -> current.filterKeys { it in cats } }
                // The categories go together rather than one after another. Each was previously a
                // separate awaited round trip, so a first scan of six categories cost six times a
                // single latency. The gate lives in the repository, so this stays polite to
                // Overpass, and each result is published as it lands — markers appear
                // progressively instead of all at the end.
                val outcomes = coroutineScope {
                    cats.map { cat ->
                        async {
                            val fetched = runCatching {
                                overpass.fetch(cat.id, cat.filter, cat.radius, cat.label, lat, lon, false)
                            }.getOrNull()
                            val places = fetched?.data?.places ?: emptyList()
                            // Re-check: a category can be switched off while its request is in
                            // flight, and a late arrival must not resurrect its markers.
                            if (cat in _enabled.value) {
                                _pois.update { current -> current + (cat to places) }
                            }
                            // Counted after every request settles rather than incremented from
                            // inside them — these run concurrently.
                            CategoryOutcome(reached = fetched != null, count = places.size)
                        }
                    }.awaitAll()
                }
                val unreachable = outcomes.count { !it.reached }
                _scanNotice.value = when {
                    outcomes.isEmpty() -> null
                    unreachable == outcomes.size -> ScanNotice("Couldn't reach the map data service.", true)
                    unreachable > 0 -> ScanNotice("$unreachable of ${outcomes.size} layers didn't load.", true)
                    outcomes.sumOf { it.count } == 0 -> ScanNotice("Nothing of that kind around here.", false)
                    else -> null
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun start() {
        compass.start()
        if (pollJob?.isActive != true) {
            pollJob = viewModelScope.launch {
                while (isActive) {
                    if (locationProvider.hasPermission()) {
                        runCatching { locationProvider.current() }.getOrNull()?.let { loc ->
                            _location.value = loc
                            compass.setLocation(loc.latitude, loc.longitude, 0.0)
                        }
                    }
                    delay(2500)
                }
            }
        }
    }

    fun stop() {
        compass.stop()
        pollJob?.cancel()
        pollJob = null
    }

    private companion object {
        /** Aircraft move; this is the same cadence the radar scope itself uses. */
        const val TRAFFIC_REFRESH_MS = 20_000L
        /** Earthquakes have already happened. Refreshing often would only cost battery. */
        const val SEISMIC_REFRESH_MS = 5 * 60_000L
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
