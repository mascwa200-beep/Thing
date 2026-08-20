package dev.mascwa.pulse.desktop.feature.search

import dev.mascwa.pulse.desktop.DeepAnalysis
import dev.mascwa.pulse.desktop.DeepState
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.desktop.search.DesktopSearchIndex
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.EmergencyTriage
import dev.mascwa.pulse.core.telemetry.GuideIndexEntry
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

    // ----- Deep search ------------------------------------------------------------------------

    /** Whether the reader has asked for the whole library to be read, not just its catalogue. */
    val deepOn: Boolean = false,
    val deep: DeepState = DeepState(),
    /** Guides whose BODY contains the query — ids, resolved to titles for display. */
    val deepHits: List<GuideIndexEntry> = emptyList(),
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
            // The resident index, held so a deep scan's ids can be turned into titles without
            // re-opening the shards it just read.
            indexCache = runCatching { library.index() }.getOrDefault(emptyList())
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
        // ⚠️ Typing changes the subject, so anything deep that was on hand is about a different
        // question now and is dropped. It does NOT start a new deep scan — see [setDeep].
        if (_state.value.deep != DeepState() || _state.value.deepHits.isNotEmpty()) {
            _state.value = _state.value.copy(deep = DeepAnalysis.cleared(), deepHits = emptyList())
        }
    }

    /**
     * Read every page in the library, not just its catalogue.
     *
     * ⚠️ **This is the deep switch's whole justification on this screen.** The ordinary search ranks
     * the resident index — title, category, summary, headings — which is instant and offline and
     * cannot see body text at all. That is a documented, real gap: a subject covered at length in a
     * guide is invisible to it unless somebody thought to name it in a heading. Reading the bodies
     * finds those, and costs a scan of every shard, which is exactly the sort of thing that should
     * happen because someone asked rather than because a screen opened.
     */
    fun setDeep(on: Boolean) {
        if (on == _state.value.deepOn) return
        deepJob?.cancel()
        if (!on) {
            _state.value = _state.value.copy(deepOn = false, deep = DeepAnalysis.cleared(), deepHits = emptyList())
            return
        }
        _state.value = _state.value.copy(deepOn = true)
        runDeep()
    }

    /** Ask again after a failure, or after the question changed while the switch was already on. */
    fun runDeep() {
        val s = _state.value
        val key = s.query.trim()
        if (!DeepAnalysis.shouldRun(on = s.deepOn, key = key, state = s.deep)) return
        _state.value = s.copy(deep = DeepAnalysis.started(s.deep))
        deepJob?.cancel()
        deepJob = scope.launch {
            val found = LinkedHashSet<String>()
            val ok = runCatching {
                // Streamed: ids arrive shard by shard, so the list fills in as the scan runs rather
                // than appearing all at once at the end of a long read.
                library.searchBodies(key).collect { ids ->
                    found += ids
                    _state.value = _state.value.copy(deepHits = resolve(found))
                }
            }.isSuccess
            // ⚠️ Only commit if the question has not changed under us. A scan takes a while, and
            // finishing after someone typed something else would answer the wrong question.
            if (_state.value.query.trim() != key) return@launch
            _state.value = _state.value.copy(
                deep = if (ok) DeepAnalysis.succeeded(key) else DeepAnalysis.failed(key),
                deepHits = if (ok) resolve(found) else emptyList(),
            )
        }
    }

    /**
     * Ids to entries, index-ordered.
     *
     * Reads the resident index, which is already in memory — the alternative, opening each guide to
     * read its title, would re-open the very shards the scan just closed.
     */
    private fun resolve(ids: Set<String>): List<GuideIndexEntry> =
        indexCache.filter { it.id in ids }

    @Volatile private var indexCache: List<GuideIndexEntry> = emptyList()
    private var deepJob: Job? = null

    private companion object {
        const val DEBOUNCE_MS = 160L
    }
}
