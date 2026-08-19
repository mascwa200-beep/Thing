package dev.mascwa.pulse.feature.markets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.settings.WatchType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class MarketsUiState(
    val watchlist: Async<List<Quote>> = Async(loading = true),
    val crypto: Async<List<Quote>> = Async(loading = true),
)

class MarketsViewModel(
    private val repo: MarketsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val watch = MutableStateFlow<Async<List<Quote>>>(Async(loading = true))
    private val crypto = MutableStateFlow<Async<List<Quote>>>(Async(loading = true))

    private val _state = MutableStateFlow(MarketsUiState())
    val state: StateFlow<MarketsUiState> = _state.asStateFlow()

    /**
     * The selected sub-tab, VM-scoped so tabbing away and back does not reset it — a `remember{}`
     * in the screen dies with the composition, and `rememberSaveable` does not reliably survive
     * the bottom-nav's popUpTo save/restore dance. An ordinal, so the screen keeps owning its enum.
     */
    val tabIndex = MutableStateFlow(0)

    init {
        viewModelScope.launch { watch.collect { w -> _state.value = _state.value.copy(watchlist = w) } }
        viewModelScope.launch { crypto.collect { c -> _state.value = _state.value.copy(crypto = c) } }
        viewModelScope.launch {
            settings.settings
                .map { s -> Triple(s.currencyCode, s.watchlist, s.cryptoList) }
                .distinctUntilChanged()
                .collect { refresh(force = false) }
        }
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch { watch.load(force) { repo.fetchWatchlist(it) } }
        viewModelScope.launch { crypto.load(force) { repo.fetchCrypto(it) } }
    }

    fun grouped(quotes: List<Quote>): Map<WatchType, List<Quote>> =
        quotes.groupBy { runCatching { WatchType.valueOf(it.type) }.getOrDefault(WatchType.STOCK) }
}
