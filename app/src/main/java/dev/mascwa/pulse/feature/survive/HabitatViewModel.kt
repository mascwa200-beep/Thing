package dev.mascwa.pulse.feature.survive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.AnimalHabitats
import dev.mascwa.pulse.core.telemetry.Habitat
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The offline animal-habitat read for the user's current location. */
data class HabitatUiState(
    val loading: Boolean = true,
    val needsPermission: Boolean = false,
    val error: String? = null,
    val habitat: Habitat? = null,
    /** Best-effort region label (from the GPS fix's reverse-geocode; raw coords when offline). */
    val placeName: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    /** The animal id selected by tapping the map, or null. */
    val selectedId: String? = null,
)

/**
 * Drives the offline wildlife map: gets a GPS fix (satellite fix needs no network), classifies the
 * biome/continent and resolves the region's fauna via the pure [AnimalHabitats] core, and holds the
 * tapped-animal selection. Everything here works with no connection — a fix + a deterministic lookup.
 */
class HabitatViewModel(
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(HabitatUiState())
    val state: StateFlow<HabitatUiState> = _state.asStateFlow()

    init { load() }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(needsPermission = !granted) }
        if (granted) load()
    }

    fun refresh() = load()

    fun select(id: String?) = _state.update { it.copy(selectedId = if (it.selectedId == id) null else id) }

    private fun load() {
        viewModelScope.launch {
            if (!locationProvider.hasPermission()) {
                _state.update { it.copy(loading = false, needsPermission = true) }
                return@launch
            }
            _state.update { it.copy(needsPermission = false, loading = it.habitat == null, error = null) }
            val loc = locationProvider.current()
            if (loc == null) {
                _state.update { it.copy(loading = false, error = "Couldn't get a location fix. Move to open sky and retry.") }
                return@launch
            }
            val habitat = AnimalHabitats.habitatFor(loc.latitude, loc.longitude)
            _state.update {
                it.copy(
                    loading = false, error = null, habitat = habitat,
                    placeName = loc.name, lat = loc.latitude, lon = loc.longitude,
                )
            }
        }
    }
}
