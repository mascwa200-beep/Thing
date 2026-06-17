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

    private val _auroraPct = MutableStateFlow<Int?>(null)
    val auroraPct: StateFlow<Int?> = _auroraPct.asStateFlow()

    private val _solarWindKmS = MutableStateFlow<Double?>(null)
    val solarWindKmS: StateFlow<Double?> = _solarWindKmS.asStateFlow()

    private val _bz = MutableStateFlow<Double?>(null)
    val bz: StateFlow<Double?> = _bz.asStateFlow()

    private val _stormLevel = MutableStateFlow("None")
    val stormLevel: StateFlow<String> = _stormLevel.asStateFlow()

    fun hasLocationPermission(): Boolean = location.hasPermission()

    init {
        viewModelScope.launch {
            while (true) {
                val loc = if (location.hasPermission()) runCatching { location.current() }.getOrNull() else null
                runCatching { space.fetch(false, loc?.latitude, loc?.longitude) }.getOrNull()?.let {
                    _kp.value = it.data.kp
                    _auroraPct.value = it.data.auroraProbabilityPct
                    _solarWindKmS.value = it.data.solarWindSpeed
                    _bz.value = it.data.bz
                    _stormLevel.value = it.data.stormLevel
                }
                delay(10 * 60 * 1000L)
            }
        }
    }
}
