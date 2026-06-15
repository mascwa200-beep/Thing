package dev.mascwa.pulse.feature.compass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CompassViewModel(
    private val compass: CompassController,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    val reading: StateFlow<CompassController.Reading> = compass.reading

    private val _location = MutableStateFlow<DeviceLocation?>(null)
    val location: StateFlow<DeviceLocation?> = _location.asStateFlow()

    private val _needsPermission = MutableStateFlow(!locationProvider.hasPermission())
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    init { resolveLocation() }

    fun start() = compass.start()
    fun stop() = compass.stop()

    fun onPermissionResult(granted: Boolean) {
        _needsPermission.value = !granted
        if (granted) resolveLocation()
    }

    private fun resolveLocation() {
        viewModelScope.launch {
            if (locationProvider.hasPermission()) {
                _needsPermission.value = false
                locationProvider.current()?.let { loc ->
                    _location.value = loc
                    compass.setLocation(loc.latitude, loc.longitude, 0.0)
                }
            } else {
                _needsPermission.value = true
            }
        }
    }

    override fun onCleared() {
        compass.stop()
    }
}
