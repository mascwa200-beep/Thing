package dev.mascwa.pulse.desktop.feature.packs

import dev.mascwa.pulse.desktop.library.PackOffer
import dev.mascwa.pulse.desktop.library.PackRepository
import dev.mascwa.pulse.core.telemetry.ContentPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PacksUiState(
    val loading: Boolean = false,
    val offers: List<PackOffer> = emptyList(),
    val error: String? = null,
    /** Which pack is being fetched, and how far along. Null when nothing is downloading. */
    val busyId: String? = null,
    val progressPct: Int = 0,
    /** A one-line result of the last install or removal, cleared by the next action. */
    val notice: String? = null,
)

/**
 * Drives PACKS: what is on offer, what is installed, and getting one into the library.
 *
 * Nothing is fetched on its own. A pack is megabytes of content the reader chose to add, so the
 * download is always something a person started — the same posture the updater takes about an
 * installer, and for the same reason.
 */
class PacksViewModel(
    private val scope: CoroutineScope,
    private val repository: PackRepository,
) {
    private val _state = MutableStateFlow(PacksUiState())
    val state: StateFlow<PacksUiState> = _state.asStateFlow()

    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        job?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        job = scope.launch {
            repository.offers()
                .onSuccess { _state.value = _state.value.copy(loading = false, offers = it, error = null) }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        // The token is the usual reason, and saying so beats a bare failure.
                        error = describe(it),
                    )
                }
        }
    }

    fun install(pack: ContentPack.Pack) {
        if (_state.value.busyId != null) return
        _state.value = _state.value.copy(busyId = pack.id, progressPct = 0, notice = null, error = null)
        scope.launch {
            val result = repository.install(pack) { pct ->
                _state.value = _state.value.copy(progressPct = pct)
            }
            _state.value = _state.value.copy(
                busyId = null,
                progressPct = 0,
                notice = result.fold(
                    onSuccess = { "${pack.title} is in the library — it works offline from now on." },
                    onFailure = { "Could not install ${pack.title}: ${it.message ?: "the download failed"}" },
                ),
            )
            refresh()
        }
    }

    fun remove(pack: ContentPack.Pack) {
        if (_state.value.busyId != null) return
        scope.launch {
            val gone = repository.remove(pack.id)
            _state.value = _state.value.copy(
                notice = if (gone) "${pack.title} removed. Nothing bundled was touched." else null,
            )
            refresh()
        }
    }

    private fun describe(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "401" in m || "404" in m ->
                "Could not read the pack catalog. A private repository needs a GitHub token — add one in ABOUT."
            "catalog" in m -> m
            else -> m.ifBlank { "The pack catalog could not be read." }
        }
    }
}
