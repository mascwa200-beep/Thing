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
import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.orbital.SkyDigest
import dev.mascwa.pulse.data.orbital.TleRepository
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
    /**
     * Tailored "for you" recommendations from on-device usage (empty until a pattern forms).
     *
     * Now the floor under the Oracle rather than a peer of it — see the card in `HomeScreen`.
     */
    val recommendations: List<dev.mascwa.pulse.core.telemetry.Recommendation> = emptyList(),
    /**
     * The user's most-opened menu destinations, count-ordered — Home's launcher row. Route to the
     * directory's label. Menu-listed destinations only: the bottom-nav tabs are already one tap away,
     * so a chip for one would spend the row on nothing.
     */
    val mostUsed: List<Pair<String, String>> = emptyList(),
    /**
     * The Oracle's ranked read — the cross-signal foresight, already re-ranked by what you act on.
     *
     * Capped at [ORACLE_ON_HOME] because the count is not cosmetic: it is what
     * [dev.mascwa.pulse.data.oracle.OracleEngine.read] records as having been shown, and a rule that
     * never earns a row must not be scored as though it had its chance.
     */
    val insights: List<dev.mascwa.pulse.core.telemetry.Insight> = emptyList(),
)

/**
 * How many insights Home puts in front of you.
 *
 * Three, because Home is a glance and the Oracle's own ranking is the point — a fourth line pushes
 * the news lead below the fold to say something the top three already implied. The full stream is
 * one tap away on the Advisories screen.
 */
const val ORACLE_ON_HOME = 3

class HomeViewModel(
    private val news: NewsRepository,
    private val markets: MarketsRepository,
    private val weather: WeatherRepository,
    private val economy: EconomyRepository,
    private val fuel: FuelRepository,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
    private val orbital: OrbitalRepository,
    private val tle: TleRepository,
    private val space: SpaceWeatherRepository,
    private val radar: RadarRepository,
    private val selfEdit: SelfEditStore,
    private val usage: dev.mascwa.pulse.data.usage.UsageRepository,
    /**
     * The container, for the Oracle alone.
     *
     * Every other dependency here is passed individually and that is the right default. The Oracle
     * is the exception on purpose: it reads about eighteen stores to reason across them, so naming
     * them one by one would put eighteen parameters on this constructor to express "all of it".
     */
    private val container: dev.mascwa.pulse.di.AppContainer,
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

            // Tailored "for you" recommendations from on-device usage (no network).
            //
            // The profile highlight and the top-task nudge used to be computed here too. They were
            // passed to the card and never read by it — dead since some earlier edit — and the
            // Oracle now takes both as signals in its own right, so they are gone rather than
            // revived: a second, weaker opinion about the same data helps nobody.
            launch {
                val snap = runCatching { usage.snapshot() }.getOrNull()
                val recs = snap?.let { sn ->
                    runCatching {
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        dev.mascwa.pulse.core.telemetry.UsageInsights.recommend(
                            sn, hour, dev.mascwa.pulse.data.usage.FeatureCatalog.entries,
                        )
                    }.getOrDefault(emptyList())
                }.orEmpty()
                // The launcher row: most-opened MENU destinations, by count (the MENU's own strip is
                // recency-ordered — that one answers "what was I just doing", this one "what do I
                // always use"). Same eligibility rule as there: menu-listed routes only.
                val menuEntries = dev.mascwa.pulse.navigation.GROUPS
                    .flatMap { g -> g.entries }.associateBy { it.route }
                val most = snap?.features.orEmpty()
                    .filter { it.key in menuEntries }
                    .sortedByDescending { it.count }
                    .take(MOST_USED)
                    .map { f -> f.key to (menuEntries.getValue(f.key).label) }
                _state.update { it.copy(recommendations = recs, mostUsed = most) }
            }

            // The Oracle. Its own launch because it reasons over every store the others just filled
            // and must not hold the rest of the screen behind it; best-effort, because a screen that
            // fails to load over an advisory is a worse outcome than a screen with no advisory.
            launch {
                val insights = runCatching {
                    dev.mascwa.pulse.data.oracle.OracleEngine.read(container, s, visible = ORACLE_ON_HOME)
                }.getOrDefault(emptyList()).take(ORACLE_ON_HOME)
                _state.update { it.copy(insights = insights) }
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
        val lines = SkyDigest.lines(orb, sw, lat, lon, sighting = issSighting(lat, lon))
        _state.update { it.copy(skyLines = lines) }
    }

    /**
     * Where the ISS is in this sky, worked out on the device.
     *
     * The element set is cached for twelve hours and shared with the observatory screen, so this is
     * one request every half day at most and usually none at all — and the answer it gives is for
     * *now*, not for whenever a position was last fetched. Null when there are no elements to
     * propagate, which leaves [SkyDigest] to fall back to the fetched position if it is fresh.
     */
    private suspend fun issSighting(lat: Double, lon: Double): SatellitePasses.Sighting? =
        runCatching {
            val elements = tle.element(ISS_NORAD_ID) ?: return null
            SatellitePasses.sighting(
                elements,
                SatellitePasses.Site(lat, lon),
                System.currentTimeMillis(),
            )
        }.getOrNull()

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

    private companion object {
        /** The station's catalogue number, and the only object this digest speaks about. */
        const val ISS_NORAD_ID = 25544

        /** Launcher-row width: one comfortable scrolling row of chips at phone width. */
        const val MOST_USED = 6
    }
}
