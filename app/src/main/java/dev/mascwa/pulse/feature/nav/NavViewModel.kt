package dev.mascwa.pulse.feature.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.objectives.ObjectiveKind
import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.data.objectives.WaypointStore
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.places.Place
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Feeds the cyberpunk NAV map: a polled GPS fix (offline-capable, GMS-optional), the live true-north
 * heading for the heading-up camera, and the toggleable POI "legend" (Overpass categories rendered
 * as map markers). Sensors/poll run only while the screen calls [start]/[stop].
 */
/** A nearby safety incident pinned on the NAV map (folded in from the old Map screen). */
data class IncidentMarker(val latitude: Double, val longitude: Double, val title: String)

class NavViewModel(
    private val locationProvider: LocationProvider,
    private val compass: CompassController,
    private val overpass: OverpassRepository,
    private val settings: SettingsRepository,
    private val waypointStore: WaypointStore,
    private val safety: SafetyRepository,
) : ViewModel() {

    /** The objective/waypoint currently tracked on the map (gold/blue/white by kind), or null. */
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

    /** The POI the user tapped on the map (drives the detail card); null = nothing selected. */
    private val _selectedPoi = MutableStateFlow<Place?>(null)
    val selectedPoi: StateFlow<Place?> = _selectedPoi.asStateFlow()

    /** One-shot camera target from a successful place search (screen consumes it). */
    private val _flyTo = MutableStateFlow<Pair<Double, Double>?>(null)
    val flyTo: StateFlow<Pair<Double, Double>?> = _flyTo.asStateFlow()

    private val _searchMessage = MutableStateFlow<String?>(null)
    val searchMessage: StateFlow<String?> = _searchMessage.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { settings.current() }.getOrNull()?.let {
                _nav3d.value = it.nav3d
                _headingUp.value = it.navHeadingUp
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

    fun selectPoi(place: Place?) { _selectedPoi.value = place }

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
                runCatching { waypointStore.add(q, coords.first, coords.second, ObjectiveKind.PLAIN) }
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
            runCatching { waypointStore.add(place.name, place.latitude, place.longitude, ObjectiveKind.PLAIN) }
        }
        _selectedPoi.value = null
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

    /** Nearby safety incidents + whether the overlay is lit (folded in from the old Map screen). */
    private val _incidents = MutableStateFlow<List<IncidentMarker>>(emptyList())
    val incidents: StateFlow<List<IncidentMarker>> = _incidents.asStateFlow()
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
                    .map { IncidentMarker(it.latitude, it.longitude, it.title) }
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
            val out = _pois.value.toMutableMap()
            for (cat in cats) {
                out[cat] = runCatching {
                    overpass.fetch(cat.id, cat.filter, cat.radius, cat.label, lat, lon, false).data.places
                }.getOrDefault(emptyList())
            }
            out.keys.retainAll(cats) // drop markers for categories that were switched off
            _pois.value = out
            _scanning.value = false
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

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
