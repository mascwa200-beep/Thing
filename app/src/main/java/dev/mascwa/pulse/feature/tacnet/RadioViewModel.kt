package dev.mascwa.pulse.feature.tacnet

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.radio.DEFAULT_STATIONS
import dev.mascwa.pulse.data.radio.RadioBrowserRepository
import dev.mascwa.pulse.data.radio.RadioStation
import dev.mascwa.pulse.data.radio.TuneInRepository
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val settings: SettingsRepository? = null,
    private val tuneIn: TuneInRepository? = null,
) : ViewModel() {

    /** Lifecycle of the on-demand local-station lookup (drives the LOCAL section's header/hint). */
    enum class LocalStatus { IDLE, LOADING, READY, EMPTY, ERROR, NO_LOCATION }

    /** Lifecycle of a name search (drives the SEARCH RESULTS section). */
    enum class SearchStatus { IDLE, SEARCHING, READY, EMPTY, ERROR }

    /** The always-present curated streams. */
    val curatedStations: List<RadioStation> = DEFAULT_STATIONS

    /** Favourited stations (local or curated), persisted across launches; shown as a FAVOURITES section. */
    val favorites: StateFlow<List<RadioStation>> =
        settings?.settings?.map { it.favoriteRadio }?.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
            ?: MutableStateFlow<List<RadioStation>>(emptyList())

    /** Star/unstar a station (matched by stream URL). No-op if settings aren't wired. */
    fun toggleFavorite(station: RadioStation) {
        val repo = settings ?: return
        viewModelScope.launch {
            runCatching {
                repo.update { s ->
                    val exists = s.favoriteRadio.any { it.streamUrl == station.streamUrl }
                    val next = if (exists) {
                        s.favoriteRadio.filterNot { it.streamUrl == station.streamUrl }
                    } else {
                        s.favoriteRadio + station
                    }
                    s.copy(favoriteRadio = next)
                }
            }
        }
    }

    private val _local = MutableStateFlow<List<RadioStation>>(emptyList())
    val localStations: StateFlow<List<RadioStation>> = _local.asStateFlow()

    private val _localStatus = MutableStateFlow(LocalStatus.IDLE)
    val localStatus: StateFlow<LocalStatus> = _localStatus.asStateFlow()

    /** Human-readable region the local list was resolved for, e.g. "United States · California". */
    private val _localPlace = MutableStateFlow<String?>(null)
    val localPlace: StateFlow<String?> = _localPlace.asStateFlow()

    /** A browsable country for the WORLD section (ISO-2 code + display label). */
    data class RadioCountry(val code: String, val label: String)

    /** Lifecycle of the WORLD (browse-by-country) lookup. */
    enum class BrowseStatus { IDLE, LOADING, READY, EMPTY, ERROR }

    /** A spread of the world's broadcast regions to browse — Radio Browser covers ~all of them. */
    val countries: List<RadioCountry> = listOf(
        RadioCountry("US", "United States"), RadioCountry("GB", "United Kingdom"),
        RadioCountry("CA", "Canada"), RadioCountry("AU", "Australia"),
        RadioCountry("IE", "Ireland"), RadioCountry("DE", "Germany"),
        RadioCountry("FR", "France"), RadioCountry("ES", "Spain"),
        RadioCountry("IT", "Italy"), RadioCountry("NL", "Netherlands"),
        RadioCountry("SE", "Sweden"), RadioCountry("PL", "Poland"),
        RadioCountry("BR", "Brazil"), RadioCountry("MX", "Mexico"),
        RadioCountry("AR", "Argentina"), RadioCountry("JP", "Japan"),
        RadioCountry("KR", "South Korea"), RadioCountry("IN", "India"),
        RadioCountry("ZA", "South Africa"), RadioCountry("NG", "Nigeria"),
        RadioCountry("NZ", "New Zealand"), RadioCountry("CH", "Switzerland"),
    )

    private val _browse = MutableStateFlow<List<RadioStation>>(emptyList())
    val browseStations: StateFlow<List<RadioStation>> = _browse.asStateFlow()
    private val _browseStatus = MutableStateFlow(BrowseStatus.IDLE)
    val browseStatus: StateFlow<BrowseStatus> = _browseStatus.asStateFlow()
    private val _browseCountry = MutableStateFlow<String?>(null)
    val browseCountry: StateFlow<String?> = _browseCountry.asStateFlow()
    private var browseJob: kotlinx.coroutines.Job? = null

    /** Load the top stations for a country (the WORLD browse). */
    fun browse(country: RadioCountry) {
        val browser = radioBrowser
        _browseCountry.value = country.code
        if (browser == null) { _browseStatus.value = BrowseStatus.ERROR; return }
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            _browseStatus.value = BrowseStatus.LOADING
            val list = runCatching { browser.stationsByCountry(country.code) }.getOrDefault(emptyList())
            _browse.value = list
            _browseStatus.value = if (list.isEmpty()) BrowseStatus.EMPTY else BrowseStatus.READY
        }
    }

    private val _searchResults = MutableStateFlow<List<RadioStation>>(emptyList())
    val searchResults: StateFlow<List<RadioStation>> = _searchResults.asStateFlow()
    private val _searchStatus = MutableStateFlow(SearchStatus.IDLE)
    val searchStatus: StateFlow<SearchStatus> = _searchStatus.asStateFlow()
    private var searchJob: kotlinx.coroutines.Job? = null

    /** Process-wide playback state (survives this ViewModel — playback continues in the background). */
    val state: StateFlow<RadioController.RadioState> get() = RadioController.state

    /** Active sleep-timer minutes, or null when off. */
    val sleepMinutes: StateFlow<Int?> get() = RadioController.sleepMinutes

    /** Live now-playing track ("Artist - Song") for the tuned station, or null when unavailable. */
    val nowPlaying: StateFlow<String?> get() = RadioController.nowPlaying

    /** Arm/clear the sleep timer (null/0 clears) — auto-stops playback after the chosen minutes. */
    fun setSleep(context: android.content.Context, minutes: Int?) = RadioController.setSleep(context, minutes)

    /** Search any station worldwide by name. Blank query clears the results. */
    fun search(query: String) {
        val q = query.trim()
        if (q.isBlank()) { clearSearch(); return }
        val browser = radioBrowser
        if (browser == null) { _searchStatus.value = SearchStatus.ERROR; return }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _searchStatus.value = SearchStatus.SEARCHING
            // Radio Browser first; then append TuneIn hits (US commercial stations Radio Browser lacks,
            // e.g. WTRV), de-duped by stream. Additive — TuneIn failing just means fewer results.
            val browserList = runCatching { browser.searchStations(q) }.getOrDefault(emptyList())
            val tuneList = runCatching { tuneIn?.search(q) }.getOrNull().orEmpty()
            val seen = HashSet(browserList.map { it.streamUrl })
            val merged = browserList + tuneList.filter { seen.add(it.streamUrl) }
            _searchResults.value = merged
            _searchStatus.value = if (merged.isEmpty()) SearchStatus.EMPTY else SearchStatus.READY
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _searchStatus.value = SearchStatus.IDLE
    }

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
            // Truly location-sourced: geo search around the fix, country/state only as a fallback.
            val place = runCatching { locator.describePlace(loc.latitude, loc.longitude) }.getOrNull()
            _localPlace.value = listOfNotNull(place?.country ?: place?.countryCode, place?.state)
                .joinToString(" · ").ifBlank { "Near you" }
            val list = runCatching {
                browser.localStations(loc.latitude, loc.longitude, place?.countryCode, place?.state)
            }.getOrDefault(emptyList())
            _local.value = list
            _localStatus.value = if (list.isEmpty()) LocalStatus.EMPTY else LocalStatus.READY
        }
    }
}
