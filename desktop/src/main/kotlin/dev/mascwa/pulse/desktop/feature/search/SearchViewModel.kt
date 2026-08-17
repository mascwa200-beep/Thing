package dev.mascwa.pulse.desktop.feature.search

import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.search.DesktopSearchIndex
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.telemetry.DeviceSearch
import dev.mascwa.pulse.desktop.telemetry.EmergencyTriage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<DeviceSearch.Result> = emptyList(),
    val corpus: List<Pair<DeviceSearch.RecordKind, Int>> = emptyList(),
    /** A recognised emergency in what is being typed, shown above every other result. */
    val emergency: EmergencyTriage.Emergency? = null,
    val searched: Boolean = false,
)

/**
 * Drives the Search screen.
 *
 * The corpus is gathered once and reused for every keystroke — rebuilding it per character would
 * re-read the whole index to answer one letter. [refresh] exists for when it is genuinely stale.
 */
class SearchViewModel(
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val study: StudyStore,
) {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    @Volatile private var records: List<DeviceSearch.Record> = emptyList()
    private var typingJob: Job? = null

    init { refresh() }

    fun refresh() {
        scope.launch {
            val gathered = runCatching { DesktopSearchIndex.records(library, study) }.getOrDefault(emptyList())
            if (gathered.isNotEmpty()) {
                records = gathered
                _state.value = _state.value.copy(corpus = DeviceSearch.corpusSummary(gathered))
            }
        }
    }

    fun onQueryChanged(query: String) {
        typingJob?.cancel()
        if (query.isBlank()) {
            _state.value = _state.value.copy(query = query, results = emptyList(), emergency = null, searched = false)
            return
        }
        // Set before the debounce: an emergency is a table lookup costing nothing, and it is the one
        // answer that should not wait for a typing pause.
        _state.value = _state.value.copy(query = query, emergency = EmergencyTriage.match(query))
        typingJob = scope.launch {
            delay(DEBOUNCE_MS)
            val hits = runCatching { DeviceSearch.search(records, query) }.getOrDefault(emptyList())
            _state.value = _state.value.copy(results = hits, searched = true)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 160L
    }
}
