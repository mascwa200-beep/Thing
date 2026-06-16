package dev.mascwa.pulse.feature.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.places.Place
import dev.mascwa.pulse.data.sensors.CompassController
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
class NavViewModel(
    private val locationProvider: LocationProvider,
    private val compass: CompassController,
    private val overpass: OverpassRepository,
) : ViewModel() {

    private val _location = MutableStateFlow<DeviceLocation?>(null)
    val location: StateFlow<DeviceLocation?> = _location.asStateFlow()

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

    private var pollJob: Job? = null
    private var scanJob: Job? = null

    fun hasPermission(): Boolean = locationProvider.hasPermission()

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
