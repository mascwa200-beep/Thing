package dev.mascwa.pulse.feature.safety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.safety.SafetyRepository
import dev.mascwa.pulse.data.safety.SafetyResult
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SafetyViewModel(
    private val repo: SafetyRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _result = MutableStateFlow<Async<SafetyResult>>(Async(loading = true))
    val result: StateFlow<Async<SafetyResult>> = _result.asStateFlow()

    private val _needsPermission = MutableStateFlow(false)
    val needsPermission: StateFlow<Boolean> = _needsPermission.asStateFlow()

    init { load(force = false) }

    fun refresh() = load(force = true)

    fun onPermissionResult(granted: Boolean) {
        _needsPermission.value = !granted
        if (granted) load(force = true)
    }

    private fun load(force: Boolean) {
        viewModelScope.launch {
            if (!locationProvider.hasPermission()) {
                _needsPermission.value = true
                return@launch
            }
            _needsPermission.value = false
            val loc = locationProvider.current()
            if (loc == null) {
                _result.value = _result.value.copy(loading = false, error = "Couldn't get your location.")
                return@launch
            }
            _result.load(force) { repo.fetch(loc.latitude, loc.longitude, it) }
        }
    }
}
