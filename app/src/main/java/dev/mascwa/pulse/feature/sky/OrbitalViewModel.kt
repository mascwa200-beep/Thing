package dev.mascwa.pulse.feature.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.orbital.OrbitalData
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OrbitalViewModel(
    private val repo: OrbitalRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {
    private val _state = MutableStateFlow<Async<OrbitalData>>(Async(loading = true))
    val state: StateFlow<Async<OrbitalData>> = _state.asStateFlow()

    init { load(force = false) }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            val loc = if (locationProvider.hasPermission()) locationProvider.current() else null
            _state.load(force) { repo.fetch(loc?.latitude, loc?.longitude, it) }
        }
    }
}
