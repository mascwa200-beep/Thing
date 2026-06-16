package dev.mascwa.pulse.feature.economy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.economy.EconomyDashboard
import dev.mascwa.pulse.data.economy.EconomyIndicator
import dev.mascwa.pulse.data.economy.EconomyRepository
import dev.mascwa.pulse.data.economy.IndicatorSeries
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EconomyUiState(
    val country: String = "US",
    val dashboard: Async<EconomyDashboard> = Async(loading = true),
    // Inflation gets its own long history series for the dedicated screen.
    val inflation: Async<IndicatorSeries> = Async(loading = true),
)

class EconomyViewModel(
    private val repo: EconomyRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(EconomyUiState())
    val state: StateFlow<EconomyUiState> = _state.asStateFlow()

    private val dash = MutableStateFlow<Async<EconomyDashboard>>(Async(loading = true))
    private val infl = MutableStateFlow<Async<IndicatorSeries>>(Async(loading = true))

    init {
        viewModelScope.launch { dash.collect { d -> _state.update { it.copy(dashboard = d) } } }
        viewModelScope.launch { infl.collect { i -> _state.update { it.copy(inflation = i) } } }
        viewModelScope.launch {
            val country = settings.settings.first().countryCode.ifBlank { "US" }
            _state.update { it.copy(country = country) }
            refresh(force = false)
        }
    }

    fun setCountry(code: String) {
        _state.update { it.copy(country = code) }
        refresh(force = true)
    }

    fun refresh(force: Boolean = true) {
        val country = _state.value.country
        viewModelScope.launch { dash.load(force) { repo.fetchDashboard(it, country) } }
        viewModelScope.launch { infl.load(force) { repo.fetchSeries(EconomyIndicator.INFLATION, it, country) } }
    }
}
