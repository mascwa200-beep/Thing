package dev.mascwa.pulse.feature.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
 * Feeds the cyberpunk NAV map: a polled GPS fix (offline-capable, GMS-optional) plus the live
 * true-north heading for the heading-up camera. Sensors/poll run only while the screen calls
 * [start]/[stop] — nothing runs in the background.
 */
class NavViewModel(
    private val locationProvider: LocationProvider,
    private val compass: CompassController,
) : ViewModel() {

    private val _location = MutableStateFlow<DeviceLocation?>(null)
    val location: StateFlow<DeviceLocation?> = _location.asStateFlow()

    /** Smoothed true-north heading in degrees (0..360); drives the heading-up camera. */
    val headingDeg: StateFlow<Float> =
        compass.reading
            .map { it.trueAzimuth }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    private var pollJob: Job? = null

    fun hasPermission(): Boolean = locationProvider.hasPermission()

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
