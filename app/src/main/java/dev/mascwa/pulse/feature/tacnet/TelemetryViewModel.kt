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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class TelemetryViewModel(
    private val controller: TelemetryController,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
) : ViewModel() {

    val telemetry: StateFlow<Telemetry> = controller.telemetry

    /** The user's chosen operator portrait (content URI) for the STATUS>CND section, or "" if none. */
    val portraitUri: StateFlow<String> = settings.settings
        .map { it.operatorPortraitUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setPortrait(uri: String) = viewModelScope.launch {
        settings.update { it.copy(operatorPortraitUri = uri) }
    }

    private val _gps = MutableStateFlow<DeviceLocation?>(null)
    val gps: StateFlow<DeviceLocation?> = _gps.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var ticker: Job? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun start() {
        controller.start()
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            launch { if (location.hasPermission()) _gps.value = location.current() }
            while (true) {
                controller.refreshSystem()
                pushLog()
                delay(1500)
            }
        }
    }

    fun stop() {
        controller.stop()
        ticker?.cancel()
    }

    private fun pushLog() {
        val t = controller.telemetry.value
        val ts = fmt.format(Date())
        val baro = t.pressureHpa?.let { "%.1f".format(it) } ?: "--"
        val mag = t.magneticUt?.roundToInt()?.toString() ?: "--"
        val g = t.accelG?.let { "%.2f".format(it) } ?: "--"
        val lux = t.lightLux?.roundToInt()?.toString() ?: "--"
        val line = "[$ts] baro=${baro}hPa mag=${mag}uT g=$g lux=$lux net=${t.netType}"
        _log.update { (listOf(line) + it).take(40) }
    }
}
