package dev.mascwa.pulse.desktop.feature.library

import dev.mascwa.pulse.core.telemetry.Guide
import dev.mascwa.pulse.core.telemetry.GuideIndexEntry
import dev.mascwa.pulse.desktop.library.LibraryRepository
import dev.mascwa.pulse.core.telemetry.SUPERGROUPS
import dev.mascwa.pulse.core.telemetry.supergroupOf
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    /** Supergroup → its categories, in the taxonomy's display order. */
    val groups: List<Pair<String, List<String>>> = emptyList(),
    val category: String = "",
    val guides: List<GuideIndexEntry> = emptyList(),
    /** The guide being read, or null when browsing. */
    val open: Guide? = null,
    val openBusy: Boolean = false,
    /** Provenance for the bundled diagrams — several are CC-BY, so this is not optional. */
    val imageNotice: String = "",
    val total: Int = 0,
)

/**
 * Drives the LIBRARY screen: browse by supergroup ▸ category, then read.
 *
 * Everything here is off the classpath — no network, no phone. Opening a guide is the only operation
 * that touches a shard, and it is the only one that shows a busy state for that reason.
 */
class LibraryViewModel(
    private val scope: CoroutineScope,
    private val repository: LibraryRepository,
    private val settings: DesktopSettingsStore,
) {
    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var openJob: Job? = null

    init {
        scope.launch {
            val index = runCatching { repository.index() }.getOrDefault(emptyList())
            val byCategory = index.map { it.category }.distinct()
            // Group through the shared taxonomy so the desktop rail reads exactly like the app's, and
            // an unmapped category still lands somewhere instead of vanishing.
            val groups = (SUPERGROUPS + listOf(OTHER_GROUP))
                .map { group -> group to byCategory.filter { supergroupOf(it) == group }.sorted() }
                .filter { it.second.isNotEmpty() }
            val saved = runCatching { settings.current().libraryCategory }.getOrDefault("")
            val category = saved.takeIf { it in byCategory } ?: groups.firstOrNull()?.second?.firstOrNull().orEmpty()
            _state.value = _state.value.copy(
                loading = false,
                groups = groups,
                total = index.size,
                category = category,
                guides = index.filter { it.category == category }.sortedBy { it.title },
                imageNotice = runCatching { repository.imageNotice() }.getOrDefault(""),
            )
        }
    }

    fun select(category: String) {
        if (category == _state.value.category && _state.value.guides.isNotEmpty()) return
        scope.launch {
            val index = runCatching { repository.index() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(
                category = category,
                guides = index.filter { it.category == category }.sortedBy { it.title },
                open = null,
            )
            runCatching { settings.update { it.copy(libraryCategory = category) } }
        }
    }

    /**
     * Open a guide by id, from anywhere.
     *
     * Cancels an in-flight open: without that, clicking through a list faster than shards parse leaves
     * several loads racing and the last to *finish* wins, which need not be the one you asked for.
     */
    fun open(guideId: String) {
        openJob?.cancel()
        _state.value = _state.value.copy(openBusy = true)
        openJob = scope.launch {
            // A null here leaves the screen on the guide list rather than a blank reader. It should not
            // be reachable — every caller passes an id that came out of the index, and `LibraryBundleTest`
            // holds that every index entry resolves to a real shard entry — so the list is the honest
            // fallback rather than an error state for a situation that would mean the bundle is broken.
            val guide = runCatching { repository.guide(guideId) }.getOrNull()
            // Selecting the guide's own category too, so closing the reader lands somewhere sensible
            // rather than back in whatever was showing before.
            val index = runCatching { repository.index() }.getOrDefault(emptyList())
            val category = guide?.category ?: _state.value.category
            _state.value = _state.value.copy(
                open = guide,
                openBusy = false,
                category = category,
                guides = index.filter { it.category == category }.sortedBy { it.title },
            )
        }
    }

    fun close() {
        openJob?.cancel()
        _state.value = _state.value.copy(open = null, openBusy = false)
    }

    private companion object {
        /** Matches the taxonomy's own fallback bucket, so nothing is dropped from the rail. */
        const val OTHER_GROUP = "Other"
    }
}
