package dev.mascwa.pulse.feature.tacnet

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.radio.DEFAULT_STATIONS
import dev.mascwa.pulse.data.radio.RadioBrowserRepository
import dev.mascwa.pulse.data.radio.RadioStation
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Backs the PIP-BOY RADIO feed. Playback itself lives in the process-wide [RadioController] (kept alive
 * by [RadioService]) so audio survives leaving the PIP-BOY/app; this ViewModel just exposes that state
 * and owns the on-demand **LOCAL** station lookup (location → reverse-geocode → Radio Browser), beside
 * the always-available **CURATED** SomaFM streams.
 */
class RadioViewModel(
    private val locationProvider: LocationProvider? = null,
    private val radioBrowser: RadioBrowserRepository? = null,
) : ViewModel() {

    /** Lifecycle of the on-demand local-station lookup (drives the LOCAL section's header/hint). */
    enum class LocalStatus { IDLE, LOADING, READY, EMPTY, ERROR, NO_LOCATION }

    /** The always-present curated streams. */
    val curatedStations: List<RadioStation> = DEFAULT_STATIONS

    private val _local = MutableStateFlow<List<RadioStation>>(emptyList())
    val localStations: StateFlow<List<RadioStation>> = _local.asStateFlow()

    private val _localStatus = MutableStateFlow(LocalStatus.IDLE)
    val localStatus: StateFlow<LocalStatus> = _localStatus.asStateFlow()

    /** Human-readable region the local list was resolved for, e.g. "United States · California". */
    private val _localPlace = MutableStateFlow<String?>(null)
    val localPlace: StateFlow<String?> = _localPlace.asStateFlow()

    /** Process-wide playback state (survives this ViewModel — playback continues in the background). */
    val state: StateFlow<RadioController.RadioState> get() = RadioController.state

    /** Tap a station: tune it (in the foreground-service-backed player), or stop if already playing it. */
    fun toggle(context: Context, station: RadioStation) = RadioController.toggle(context, station)

    /** Find nearby stations: location → reverse-geocode → Radio Browser. Idempotent while loading. */
    fun loadLocal() {
        val browser = radioBrowser
        val locator = locationProvider
        if (browser == null || locator == null) {
            _localStatus.value = LocalStatus.ERROR
            return
        }
        if (_localStatus.value == LocalStatus.LOADING) return
        viewModelScope.launch {
            _localStatus.value = LocalStatus.LOADING
            if (!locator.hasPermission()) {
                _localStatus.value = LocalStatus.NO_LOCATION
                return@launch
            }
            val loc = runCatching { locator.current() }.getOrNull()
            if (loc == null) {
                _localStatus.value = LocalStatus.NO_LOCATION
                return@launch
            }
            val place = runCatching { locator.describePlace(loc.latitude, loc.longitude) }.getOrNull()
            val cc = place?.countryCode
            if (cc.isNullOrBlank()) {
                _localStatus.value = LocalStatus.ERROR
                return@launch
            }
            _localPlace.value = listOfNotNull(place.country ?: cc, place.state).joinToString(" · ")
            val list = runCatching { browser.localStations(cc, place.state) }.getOrDefault(emptyList())
            _local.value = list
            _localStatus.value = if (list.isEmpty()) LocalStatus.EMPTY else LocalStatus.READY
        }
    }
}
