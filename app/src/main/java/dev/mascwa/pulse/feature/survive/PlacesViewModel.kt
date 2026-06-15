package dev.mascwa.pulse.feature.survive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.places.PlaceCategory
import dev.mascwa.pulse.data.places.PlacesResult
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlacesUiState(
    val category: PlaceCategory = PlaceCategory.HOSPITAL,
    val result: Async<PlacesResult> = Async(loading = true),
    val needsPermission: Boolean = false,
)

class PlacesViewModel(
    private val overpass: OverpassRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(PlacesUiState())
    val state: StateFlow<PlacesUiState> = _state.asStateFlow()

    private val result = MutableStateFlow<Async<PlacesResult>>(Async(loading = true))

    init {
        viewModelScope.launch { result.collect { r -> _state.update { it.copy(result = r) } } }
        load(force = false)
    }

    fun select(category: PlaceCategory) {
        if (category == _state.value.category) return
        _state.update { it.copy(category = category) }
        load(force = false)
    }

    fun refresh() = load(force = true)

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(needsPermission = !granted) }
        if (granted) load(force = true)
    }

    private fun load(force: Boolean) {
        viewModelScope.launch {
            if (!locationProvider.hasPermission()) {
                _state.update { it.copy(needsPermission = true) }
                return@launch
            }
            _state.update { it.copy(needsPermission = false) }
            val loc = locationProvider.current()
            if (loc == null) {
                result.update { it.copy(loading = false, error = "Couldn't get your location.") }
                return@launch
            }
            val cat = _state.value.category
            result.load(force) { overpass.fetch(cat, loc.latitude, loc.longitude, it) }
        }
    }
}
