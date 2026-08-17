package dev.mascwa.pulse.feature.survive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.StudyProgress
import dev.mascwa.pulse.data.study.StudyStore
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
    private val study: StudyStore,
) : ViewModel() {
    private val _index = MutableStateFlow<List<GuideIndexEntry>>(emptyList())
    val index: StateFlow<List<GuideIndexEntry>> = _index.asStateFlow()

    private val _selected = MutableStateFlow<Guide?>(null)
    val selected: StateFlow<Guide?> = _selected.asStateFlow()

    /** Ids whose section BODIES match the current query — beyond what the index fields catch. Filled
     *  progressively as the streamed shard scan advances; reset on every query change. */
    private val _bodyMatches = MutableStateFlow<Set<String>>(emptySet())
    val bodyMatches: StateFlow<Set<String>> = _bodyMatches.asStateFlow()

    /**
     * How well the open guide is known, or null when nothing can honestly be said about it.
     *
     * Null covers both "no record" and [StudyProgress.Level.UNSEEN] — a reader that announces "Not
     * started" on every one of 581 guides is noise, and the screen should have nothing to render rather
     * than a line saying nothing.
     */
    private val _mastery = MutableStateFlow<StudyProgress.Mastery?>(null)
    val mastery: StateFlow<StudyProgress.Mastery?> = _mastery.asStateFlow()

    /** How many questions the last "teach me this" produced — a one-shot confirmation, null until used. */
    private val _taught = MutableStateFlow<Int?>(null)
    val taught: StateFlow<Int?> = _taught.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            _index.value = runCatching { content.index() }.getOrDefault(emptyList())
        }
    }

    /** Opens a guide in the reader — parses only that guide's shard. */
    fun open(id: String) {
        // Cleared first: the previous guide's standing must not linger on this one while it loads.
        _mastery.value = null
        _taught.value = null
        viewModelScope.launch {
            runCatching { content.guide(id) }.getOrNull()?.let { _selected.value = it }
            refreshMastery(id)
        }
    }

    /**
     * Turn the open guide into questions — the phone's counterpart of the desktop reader's STUDY THIS.
     *
     * Reading and being taught were entirely disconnected surfaces on Android: you could read the whole
     * bundled library and the study deck would never hear about it.
     */
    fun teach() {
        val id = _selected.value?.id ?: return
        viewModelScope.launch {
            val made = runCatching { study.teach(id) }.getOrDefault(emptyList())
            _taught.value = made.size
            refreshMastery(id)
        }
    }

    private suspend fun refreshMastery(id: String) {
        val m = runCatching { study.mastery(id) }.getOrNull()
        _mastery.value = m?.takeIf { it.level != StudyProgress.Level.UNSEEN }
    }

    fun closeReader() {
        _selected.value = null
        _mastery.value = null
        _taught.value = null
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
