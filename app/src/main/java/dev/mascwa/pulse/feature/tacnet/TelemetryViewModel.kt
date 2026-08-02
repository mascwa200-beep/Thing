package dev.mascwa.pulse.feature.tacnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.data.sensors.TelemetryController
import dev.mascwa.pulse.data.settings.SettingsRepository
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
import kotlinx.coroutines.launch

/** The STATS ▸ STATUS device readout: live sensors + a GPS fix. Pure device telemetry — no game state. */
class TelemetryViewModel(
    private val controller: TelemetryController,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
) : ViewModel() {

    val telemetry: StateFlow<Telemetry> = controller.telemetry

    private val _gps = MutableStateFlow<DeviceLocation?>(null)
    val gps: StateFlow<DeviceLocation?> = _gps.asStateFlow()

    /** The user's chosen operator portrait (content URI) for the STATUS>CND section, or "" if none. */
    val portraitUri: StateFlow<String> = settings.settings
        .map { it.operatorPortraitUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setPortrait(uri: String) = viewModelScope.launch {
        settings.update { it.copy(operatorPortraitUri = uri) }
    }

    private var ticker: Job? = null

    fun start() {
        controller.start()
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            if (location.hasPermission()) {
                location.current()?.let { _gps.value = it }
            }
            while (true) {
                delay(GPS_POLL_MS)
                if (location.hasPermission()) {
                    location.current()?.let { _gps.value = it }
                }
            }
        }
    }

    fun stop() {
        controller.stop()
        ticker?.cancel()
    }

    private companion object {
        const val GPS_POLL_MS = 10_000L
    }
}
