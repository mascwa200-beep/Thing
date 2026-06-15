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
)

class HomeViewModel(
    private val news: NewsRepository,
    private val markets: MarketsRepository,
    private val weather: WeatherRepository,
    private val economy: EconomyRepository,
    private val fuel: FuelRepository,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init { load(force = false) }

    fun refresh() = load(force = true)

    private fun load(force: Boolean) {
        viewModelScope.launch {
            val s = settings.current()
            val sections = s.homeSections
            _state.update { it.copy(sections = sections) }

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
            }
        }
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
