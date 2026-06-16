package dev.mascwa.pulse.feature.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Feeds the global HUD strip its live planetary K-index (cached, ~10 min). */
class HudViewModel(
    private val space: SpaceWeatherRepository,
    private val location: LocationProvider,
) : ViewModel() {

    private val _kp = MutableStateFlow<Double?>(null)
    val kp: StateFlow<Double?> = _kp.asStateFlow()

    fun hasLocationPermission(): Boolean = location.hasPermission()

    init {
        viewModelScope.launch {
            while (true) {
                runCatching { space.fetch(false) }.getOrNull()?.let { _kp.value = it.data.kp }
                delay(10 * 60 * 1000L)
            }
        }
    }
}
