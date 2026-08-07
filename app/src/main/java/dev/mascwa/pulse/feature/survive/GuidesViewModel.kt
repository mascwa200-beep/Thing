package dev.mascwa.pulse.feature.survive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.survival.Guide
import dev.mascwa.pulse.data.survival.GuideIndexEntry
import dev.mascwa.pulse.data.survival.SurvivalContentRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Knowledge Base browse/search/reader off the lightweight catalog [index] — the full corpus is
 * never held resident. Opening a guide lazily parses just its own shard; body-level full-text search
 * streams shard by shard into [bodyMatches] (debounced, cancelled on every keystroke) while the screen
 * does instant index-field matching itself.
 */
class GuidesViewModel(
    private val content: SurvivalContentRepository,
) : ViewModel() {
    private val _index = MutableStateFlow<List<GuideIndexEntry>>(emptyList())
    val index: StateFlow<List<GuideIndexEntry>> = _index.asStateFlow()

    private val _selected = MutableStateFlow<Guide?>(null)
    val selected: StateFlow<Guide?> = _selected.asStateFlow()

    /** Ids whose section BODIES match the current query — beyond what the index fields catch. Filled
     *  progressively as the streamed shard scan advances; reset on every query change. */
    private val _bodyMatches = MutableStateFlow<Set<String>>(emptySet())
    val bodyMatches: StateFlow<Set<String>> = _bodyMatches.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _index.value = runCatching { content.index() }.getOrDefault(emptyList())
        }
    }

    /** Opens a guide in the reader — parses only that guide's shard. */
    fun open(id: String) {
        viewModelScope.launch {
            runCatching { content.guide(id) }.getOrNull()?.let { _selected.value = it }
        }
    }

    fun closeReader() {
        _selected.value = null
    }

    /** Kicks the streamed body-level search for [query]; index-field matching stays in the screen. */
    fun search(query: String) {
        searchJob?.cancel()
        _bodyMatches.value = emptySet()
        val needle = query.trim()
        if (needle.length < BODY_SEARCH_MIN_CHARS) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runCatching {
                content.searchBodies(needle).collect { ids -> _bodyMatches.value = _bodyMatches.value + ids }
            }
        }
    }

    private companion object {
        /** Below this length a body scan is all noise (and every shard raw-matches anyway). */
        const val BODY_SEARCH_MIN_CHARS = 3
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}
