package dev.mascwa.pulse.feature.tacnet

import android.media.AudioAttributes
import android.media.MediaPlayer
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
 * Drives the PIP-BOY RADIO feed: a small, contextless [MediaPlayer] streaming one station at a time.
 * Held in the ViewModel so audio survives PIP-BOY sub-tab switches; released on [onCleared]. Defensive
 * throughout — a bad URL / network drop lands in [Status.ERROR], never a crash.
 *
 * Two station groups: **LOCAL** — stations near the device (Radio Browser, by reverse-geocoded
 * country/state), loaded on demand; and **CURATED** — the always-available SomaFM streams. The tuned
 * station is tracked by identity, so it stays lit as the local list streams in. Background playback (a
 * foreground service) is a deliberate follow-up; for now audio plays while the PIP-BOY is open.
 */
class RadioViewModel(
    private val locationProvider: LocationProvider? = null,
    private val radioBrowser: RadioBrowserRepository? = null,
) : ViewModel() {

    enum class Status { IDLE, TUNING, ON_AIR, ERROR }

    /** Lifecycle of the on-demand local-station lookup (drives the LOCAL section's header/hint). */
    enum class LocalStatus { IDLE, LOADING, READY, EMPTY, ERROR, NO_LOCATION }

    /** Tuned station tracked by identity (not list index) so it survives the LOCAL list loading in. */
    data class RadioState(val tuned: RadioStation? = null, val status: Status = Status.IDLE)

    /** The always-present curated streams. */
    val curatedStations: List<RadioStation> = DEFAULT_STATIONS

    private val _local = MutableStateFlow<List<RadioStation>>(emptyList())
    val localStations: StateFlow<List<RadioStation>> = _local.asStateFlow()

    private val _localStatus = MutableStateFlow(LocalStatus.IDLE)
    val localStatus: StateFlow<LocalStatus> = _localStatus.asStateFlow()

    /** Human-readable region the local list was resolved for, e.g. "United States · California". */
    private val _localPlace = MutableStateFlow<String?>(null)
    val localPlace: StateFlow<String?> = _localPlace.asStateFlow()

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private var player: MediaPlayer? = null

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

    /** Tap a station: tune it, or stop if it's the one already tuning/on air. */
    fun toggle(station: RadioStation) {
        val s = _state.value
        if (s.tuned?.streamUrl == station.streamUrl && (s.status == Status.ON_AIR || s.status == Status.TUNING)) {
            stop()
        } else {
            tune(station)
        }
    }

    private fun tune(station: RadioStation) {
        releasePlayer()
        _state.value = RadioState(station, Status.TUNING)
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(station.streamUrl)
                setOnPreparedListener { it.start(); _state.value = RadioState(station, Status.ON_AIR) }
                setOnErrorListener { _, _, _ -> _state.value = RadioState(station, Status.ERROR); true }
                prepareAsync()
            }
        }.onFailure { _state.value = RadioState(station, Status.ERROR) }
    }

    fun stop() {
        releasePlayer()
        _state.value = RadioState(null, Status.IDLE)
    }

    private fun releasePlayer() {
        runCatching { player?.reset(); player?.release() }
        player = null
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}
