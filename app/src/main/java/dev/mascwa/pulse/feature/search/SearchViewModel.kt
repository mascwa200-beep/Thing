package dev.mascwa.pulse.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.data.search.DeviceSearchIndex
import dev.mascwa.pulse.data.settings.SearchEngine
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class SearchViewModel(
    private val settings: SettingsRepository,
    private val container: AppContainer,
) : ViewModel() {

    private val _engine = MutableStateFlow(SearchEngine.DUCKDUCKGO)
    val engine: StateFlow<SearchEngine> = _engine.asStateFlow()

    /** What this device itself can answer, ranked. Empty until something is typed. */
    private val _results = MutableStateFlow<List<DeviceSearch.Result>>(emptyList())
    val results: StateFlow<List<DeviceSearch.Result>> = _results.asStateFlow()

    /** "577 guides · 12 notes · 4 tasks" — so the box can say what it is actually searching. */
    private val _corpus = MutableStateFlow<List<Pair<DeviceSearch.RecordKind, Int>>>(emptyList())
    val corpus: StateFlow<List<Pair<DeviceSearch.RecordKind, Int>>> = _corpus.asStateFlow()

    private val _searched = MutableStateFlow(false)
    /** Whether a query has actually been run, so "nothing found" is distinguishable from "not yet". */
    val searched: StateFlow<Boolean> = _searched.asStateFlow()

    /**
     * The gathered corpus, built once and reused for every keystroke.
     *
     * Rebuilding per keystroke would re-read seven stores to answer one letter. The stores are
     * append-mostly and this screen is short-lived, so a snapshot taken when it opens is current
     * enough — and [refresh] exists for the case where it is not.
     */
    private var records: List<DeviceSearch.Record> = emptyList()
    private var typingJob: Job? = null

    init {
        viewModelScope.launch { _engine.value = settings.current().searchEngine }
        refresh()
    }

    /** Re-gather what is searchable. Runs off the main thread; failures leave the last snapshot. */
    fun refresh() {
        viewModelScope.launch {
            val gathered = withContext(Dispatchers.Default) {
                runCatching { DeviceSearchIndex.records(container) }.getOrDefault(emptyList())
            }
            if (gathered.isNotEmpty()) {
                records = gathered
                _corpus.value = DeviceSearch.corpusSummary(gathered)
            }
        }
    }

    /**
     * Search the device as the user types.
     *
     * Debounced, because ranking runs over the whole corpus and a fast typist would otherwise queue
     * one full pass per character. The delay is short enough to feel immediate and long enough that
     * a word typed at speed costs one pass rather than five.
     */
    fun onQueryChanged(query: String) {
        typingJob?.cancel()
        if (query.isBlank()) {
            _results.value = emptyList()
            _searched.value = false
            return
        }
        typingJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            val hits = withContext(Dispatchers.Default) {
                runCatching { DeviceSearch.search(records, query) }.getOrDefault(emptyList())
            }
            _results.value = hits
            _searched.value = true
        }
    }

    fun setEngine(e: SearchEngine) {
        _engine.value = e
        viewModelScope.launch { settings.update { it.copy(searchEngine = e) } }
    }

    fun urlFor(query: String): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return _engine.value.urlTemplate.format(q)
    }

    private companion object {
        const val DEBOUNCE_MS = 160L
    }
}
