package dev.mascwa.pulse.feature.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.space.SpaceWeather
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SpaceWeatherViewModel(
    private val repo: SpaceWeatherRepository,
    private val location: LocationProvider,
) : ViewModel() {
    private val _state = MutableStateFlow<Async<SpaceWeather>>(Async(loading = true))
    val state: StateFlow<Async<SpaceWeather>> = _state.asStateFlow()

    init { refresh(force = false) }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            val loc = if (location.hasPermission()) location.current() else null
            _state.load(force) { repo.fetch(it, loc?.latitude, loc?.longitude) }
        }
    }
}
