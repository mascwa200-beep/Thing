package dev.mascwa.pulse.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.places.PlaceCategory
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MarkerKind { YOU, INCIDENT, HOSPITAL, SHELTER }

data class MapMarker(
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val snippet: String,
    val kind: MarkerKind,
)

data class MapUiState(
    val centerLat: Double? = null,
    val centerLon: Double? = null,
    val markers: List<MapMarker> = emptyList(),
    val loading: Boolean = true,
    val needsPermission: Boolean = false,
)

class MapViewModel(
    private val safety: SafetyRepository,
    private val overpass: OverpassRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    init { load() }

    fun refresh() = load()

    fun onPermissionResult(granted: Boolean) {
        _state.value = _state.value.copy(needsPermission = !granted)
        if (granted) load()
    }

    private fun load() {
        viewModelScope.launch {
            if (!locationProvider.hasPermission()) {
                _state.value = _state.value.copy(needsPermission = true, loading = false)
                return@launch
            }
            _state.value = _state.value.copy(loading = true, needsPermission = false)
            val loc = locationProvider.current()
            if (loc == null) {
                _state.value = _state.value.copy(loading = false)
                return@launch
            }
            val markers = coroutineScope {
                val incidentsD = async {
                    runCatching { safety.fetch(loc.latitude, loc.longitude, false).data.incidents }
                        .getOrDefault(emptyList())
                        .filter { it.distanceMeters > 0 }
                        .take(40)
                        .map { MapMarker(it.latitude, it.longitude, it.title,
                            "${Geo.formatDistance(it.distanceMeters)} · ${it.source}", MarkerKind.INCIDENT) }
                }
                val hospD = async {
                    runCatching { overpass.fetch(PlaceCategory.HOSPITAL, loc.latitude, loc.longitude, false).data.places }
                        .getOrDefault(emptyList())
                        .take(25)
                        .map { MapMarker(it.latitude, it.longitude, it.name,
                            Geo.formatDistance(it.distanceMeters), MarkerKind.HOSPITAL) }
                }
                val shelterD = async {
                    runCatching { overpass.fetch(PlaceCategory.SHELTER, loc.latitude, loc.longitude, false).data.places }
                        .getOrDefault(emptyList())
                        .take(25)
                        .map { MapMarker(it.latitude, it.longitude, it.name,
                            Geo.formatDistance(it.distanceMeters), MarkerKind.SHELTER) }
                }
                buildList {
                    add(MapMarker(loc.latitude, loc.longitude, "You", loc.name, MarkerKind.YOU))
                    addAll(incidentsD.await())
                    addAll(hospD.await())
                    addAll(shelterD.await())
                }
            }
            _state.value = MapUiState(
                centerLat = loc.latitude, centerLon = loc.longitude,
                markers = markers, loading = false, needsPermission = false,
            )
        }
    }
}
