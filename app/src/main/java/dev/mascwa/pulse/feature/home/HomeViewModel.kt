package dev.mascwa.pulse.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.economy.EconomyDashboard
import dev.mascwa.pulse.data.economy.EconomyRepository
import dev.mascwa.pulse.data.fuel.FuelData
import dev.mascwa.pulse.data.fuel.FuelRepository
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.data.news.Article
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.news.NewsRepository
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.orbital.SkyDigest
import dev.mascwa.pulse.data.radar.RadarData
import dev.mascwa.pulse.data.radar.RadarRepository
import dev.mascwa.pulse.data.selfedit.ActionType
import dev.mascwa.pulse.data.selfedit.SelfEditStore
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.settings.HomeSection
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.data.weather.WeatherRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val sections: List<HomeSection> = HomeSection.entries.toList(),
    val headlines: Async<List<Article>> = Async(loading = true),
    val markets: Async<List<Quote>> = Async(loading = true),
    val weather: Async<WeatherData> = Async(loading = true),
    val economy: Async<EconomyDashboard> = Async(loading = true),
    val fuel: Async<FuelData> = Async(loading = true),
    val politics: Async<List<Article>> = Async(loading = true),
    val tech: Async<List<Article>> = Async(loading = true),
    val popculture: Async<List<Article>> = Async(loading = true),
    val skyLines: List<String> = emptyList(),
    /** One-line J.A.R.V.I.S. status for the home assistant card (brain + resident/wake flags). */
    val jarvisStatus: String = "",
    /** Live aircraft near the user (TACNET/ADS-B) for the home flight card; null until fetched. */
    val radar: Async<RadarData> = Async(loading = true),
    /** Staged self-code changes awaiting the user's approval (nudge on the home assistant card). */
    val pendingCode: Int = 0,
    /** Tailored "for you" recommendations from on-device usage (empty until a pattern forms). */
    val recommendations: List<dev.mascwa.pulse.core.telemetry.Recommendation> = emptyList(),
    /** A one-line profile highlight ("Working on: …" / "Following: …"); null when unknown. */
    val profileHighlight: String? = null,
)

class HomeViewModel(
    private val news: NewsRepository,
    private val markets: MarketsRepository,
    private val weather: WeatherRepository,
    private val economy: EconomyRepository,
    private val fuel: FuelRepository,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
    private val orbital: OrbitalRepository,
    private val space: SpaceWeatherRepository,
    private val radar: RadarRepository,
    private val selfEdit: SelfEditStore,
    private val usage: dev.mascwa.pulse.data.usage.UsageRepository,
    private val profile: dev.mascwa.pulse.data.profile.ProfileStore,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        load(force = false)
        // Live count of self-code changes awaiting approval, surfaced on the assistant card.
        viewModelScope.launch {
            selfEdit.state.collect { se ->
                val n = se.pendingActions.count { it.type == ActionType.CODE_PR }
                _state.update { it.copy(pendingCode = n) }
            }
        }
    }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            val s = settings.current()
            val sections = s.homeSections
            _state.update { it.copy(sections = sections, jarvisStatus = jarvisStatus(s)) }

            // Tailored "for you" recommendations + profile highlight from on-device data (no network).
            launch {
                val recs = runCatching {
                    val snap = usage.snapshot()
                    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    dev.mascwa.pulse.core.telemetry.UsageInsights.recommend(
                        snap, hour, dev.mascwa.pulse.data.usage.FeatureCatalog.entries,
                    )
                }.getOrDefault(emptyList())
                val highlight = runCatching {
                    dev.mascwa.pulse.core.telemetry.UserProfile.highlight(profile.all())
                }.getOrNull()
                _state.update { it.copy(recommendations = recs, profileHighlight = highlight) }
            }

            // Above-the-fold first (instant from cache, snappier cold start)…
            if (HomeSection.HEADLINES in sections) launchInto(force, { f -> news.fetchCategory(NewsCategory.TOP, f) }) { st, a -> st.copy(headlines = a) }
            if (HomeSection.MARKETS in sections) launchInto(force, { f -> markets.fetchAll(f) }) { st, a -> st.copy(markets = a) }
            if (HomeSection.WEATHER in sections) launch { loadWeather(force, s) }

            // …then stagger the secondary sections so they don't all hit the
            // network at frame 0 (reduces cold-start contention/jank).
            launch {
                kotlinx.coroutines.delay(450)
                if (HomeSection.ECONOMY in sections || HomeSection.INFLATION in sections)
                    launchInto(force, { f -> economy.fetchDashboard(f) }) { st, a -> st.copy(economy = a) }
                if (HomeSection.FUEL in sections) launchInto(force, { f -> fuel.fetch(f) }) { st, a -> st.copy(fuel = a) }
                if (HomeSection.POLITICS in sections) launchInto(force, { f -> news.fetchCategory(NewsCategory.POLITICS, f) }) { st, a -> st.copy(politics = a) }
                if (HomeSection.TECH in sections) launchInto(force, { f -> news.fetchCategory(NewsCategory.TECH, f) }) { st, a -> st.copy(tech = a) }
                if (HomeSection.POPCULTURE in sections) launchInto(force, { f -> news.fetchCategory(NewsCategory.POPCULTURE, f) }) { st, a -> st.copy(popculture = a) }
                loadSky(force, s)
                loadRadar(force, s)
            }
        }
    }

    /** One-line assistant status from settings (no engine dependency): which brain is active and whether
     *  it's resident / listening for the wake word. */
    private fun jarvisStatus(s: dev.mascwa.pulse.data.settings.AppSettings): String {
        val j = s.jarvis
        val brain = if (j.cloudActive) "CLOUD · ${j.cloudProvider.label.uppercase()}" else "ON-DEVICE"
        val flags = buildList {
            if (j.residentService) add("RESIDENT")
            if (j.wakeWord) add("WAKE WORD")
            if (j.selfCodingEnabled) add("SELF-CODING")
        }
        return (listOf(brain) + flags).joinToString(" · ")
    }

    /** "Today in the sky" digest line — orbital + space weather (keyless). */
    private suspend fun loadSky(force: Boolean, s: dev.mascwa.pulse.data.settings.AppSettings) {
        val (lat, lon) = run {
            if (s.useDeviceLocation && location.hasPermission()) {
                location.current()?.let { return@run it.latitude to it.longitude }
            }
            val saved = s.savedLocations.getOrNull(s.selectedLocationIndex) ?: s.savedLocations.firstOrNull()
            if (saved != null) saved.latitude to saved.longitude else 51.5074 to -0.1278
        }
        val orb = runCatching { orbital.fetch(lat, lon, force).data }.getOrNull() ?: return
        val sw = runCatching { space.fetch(false, lat, lon).data }.getOrNull()
        val lines = SkyDigest.lines(orb, sw, lat, lon)
        _state.update { it.copy(skyLines = lines) }
    }

    /** Live aircraft near the user — only when we have a real device-location origin (otherwise plotting
     *  around a default point would be misleading, so the card stays hidden). */
    private suspend fun loadRadar(force: Boolean, s: dev.mascwa.pulse.data.settings.AppSettings) {
        if (!(s.useDeviceLocation && location.hasPermission())) return
        val loc = location.current() ?: return
        val flow = MutableStateFlow<Async<RadarData>>(Async(loading = true))
        viewModelScope.launch { flow.collect { a -> _state.update { it.copy(radar = a) } } }
        flow.load(force) { radar.fetch(loc.latitude, loc.longitude, it) }
    }

    private fun <T> launchInto(
        force: Boolean,
        fetch: suspend (Boolean) -> dev.mascwa.pulse.core.util.Fetched<T>,
        assign: (HomeUiState, Async<T>) -> HomeUiState,
    ) {
        viewModelScope.launch {
            val flow = MutableStateFlow<Async<T>>(Async(loading = true))
            launch { flow.collect { a -> _state.update { assign(it, a) } } }
            flow.load(force, fetch)
        }
    }

    private suspend fun loadWeather(force: Boolean, s: dev.mascwa.pulse.data.settings.AppSettings) {
        val (lat, lon, name) = run {
            if (s.useDeviceLocation && location.hasPermission()) {
                location.current()?.let { return@run Triple(it.latitude, it.longitude, it.name) }
            }
            val saved = s.savedLocations.getOrNull(s.selectedLocationIndex)
                ?: s.savedLocations.firstOrNull()
            if (saved != null) Triple(saved.latitude, saved.longitude, saved.name)
            else Triple(51.5074, -0.1278, "London")
        }
        val flow = MutableStateFlow<Async<WeatherData>>(Async(loading = true))
        viewModelScope.launch { flow.collect { a -> _state.update { it.copy(weather = a) } } }
        flow.load(force) { weather.fetch(lat, lon, name, it) }
    }
}
