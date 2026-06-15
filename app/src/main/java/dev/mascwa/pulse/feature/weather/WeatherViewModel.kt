package dev.mascwa.pulse.feature.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.settings.SavedLocation
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.data.weather.WeatherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WeatherUiState(
    val data: Async<WeatherData> = Async(loading = true),
    val useDeviceLocation: Boolean = true,
    val needsLocationPermission: Boolean = false,
    val savedLocations: List<SavedLocation> = emptyList(),
    val selectedIndex: Int = 0,
    val query: String = "",
    val searchResults: List<SavedLocation> = emptyList(),
    val searching: Boolean = false,
)

class WeatherViewModel(
    private val repo: WeatherRepository,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val data = MutableStateFlow<Async<WeatherData>>(Async(loading = true))
    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { data.collect { d -> _state.update { it.copy(data = d) } } }
        viewModelScope.launch {
            val s = settings.current()
            _state.update {
                it.copy(
                    useDeviceLocation = s.useDeviceLocation,
                    savedLocations = s.savedLocations,
                    selectedIndex = s.selectedLocationIndex,
                )
            }
            resolveAndLoad(force = false)
        }
    }

    fun refresh() = resolveAndLoad(force = true)

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(needsLocationPermission = !granted) }
        if (granted) resolveAndLoad(force = true)
    }

    private fun resolveAndLoad(force: Boolean) {
        viewModelScope.launch {
            val s = settings.current()
            _state.update {
                it.copy(
                    useDeviceLocation = s.useDeviceLocation,
                    savedLocations = s.savedLocations,
                    selectedIndex = s.selectedLocationIndex,
                )
            }
            if (s.useDeviceLocation) {
                if (location.hasPermission()) {
                    _state.update { it.copy(needsLocationPermission = false) }
                    val dev = location.current()
                    if (dev != null) {
                        data.load(force) { repo.fetch(dev.latitude, dev.longitude, dev.name, it) }
                        return@launch
                    }
                } else {
                    _state.update { it.copy(needsLocationPermission = true) }
                }
            }
            // Fall back to a saved location, or a sensible default.
            val saved = s.savedLocations.getOrNull(s.selectedLocationIndex)
                ?: s.savedLocations.firstOrNull()
                ?: DEFAULT_LOCATION
            data.load(force) { repo.fetch(saved.latitude, saved.longitude, saved.name, it) }
        }
    }

    fun search(query: String) {
        _state.update { it.copy(query = query) }
        if (query.length < 2) {
            _state.update { it.copy(searchResults = emptyList(), searching = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val results = runCatching { repo.searchLocations(query) }.getOrDefault(emptyList())
            _state.update { it.copy(searchResults = results, searching = false) }
        }
    }

    fun addAndSelect(loc: SavedLocation) {
        viewModelScope.launch {
            settings.update { s ->
                val list = (s.savedLocations + loc).distinctBy { it.name to it.latitude }
                s.copy(
                    savedLocations = list,
                    selectedLocationIndex = list.indexOf(loc).coerceAtLeast(0),
                    useDeviceLocation = false,
                )
            }
            _state.update { it.copy(query = "", searchResults = emptyList()) }
            resolveAndLoad(force = true)
        }
    }

    fun selectSaved(index: Int) {
        viewModelScope.launch {
            settings.update { it.copy(selectedLocationIndex = index, useDeviceLocation = false) }
            resolveAndLoad(force = true)
        }
    }

    fun removeSaved(index: Int) {
        viewModelScope.launch {
            settings.update { s ->
                val list = s.savedLocations.toMutableList().also { if (index in it.indices) it.removeAt(index) }
                s.copy(
                    savedLocations = list,
                    selectedLocationIndex = s.selectedLocationIndex.coerceAtMost(list.lastIndex.coerceAtLeast(0)),
                )
            }
            resolveAndLoad(force = false)
        }
    }

    fun useDeviceLocation() {
        viewModelScope.launch {
            settings.update { it.copy(useDeviceLocation = true) }
            resolveAndLoad(force = true)
        }
    }

    companion object {
        val DEFAULT_LOCATION = SavedLocation("London", "United Kingdom", 51.5074, -0.1278, "auto")
    }
}
