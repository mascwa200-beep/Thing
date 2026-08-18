package dev.mascwa.pulse.feature.packs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.ContentPack
import dev.mascwa.pulse.data.survival.PackOffer
import dev.mascwa.pulse.data.survival.PackRepository
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Drives PACKS: what is on offer, what is installed, and getting one into the library.
 *
 * Nothing is fetched on its own. A pack is megabytes of content the reader chose to add, so the
 * download is always something a person started — the same posture the updater takes about an
 * installer, and for the same reason.
 */
class PacksViewModel(private val container: AppContainer) : ViewModel() {

    private val repository: PackRepository get() = container.packRepository

    data class UiState(
        val loading: Boolean = false,
        val offers: List<PackOffer> = emptyList(),
        val error: String? = null,
        /** Which pack is being fetched, and how far along. Null when nothing is downloading. */
        val busyId: String? = null,
        val progressPct: Int = 0,
        /** A one-line result of the last install or removal, cleared by the next action. */
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        job?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            repository.offers()
                .onSuccess { list -> _state.update { it.copy(loading = false, offers = list, error = null) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = describe(e)) } }
        }
    }

    fun install(pack: ContentPack.Pack) {
        if (_state.value.busyId != null) return
        _state.update { it.copy(busyId = pack.id, progressPct = 0, notice = null, error = null) }
        viewModelScope.launch {
            val result = repository.install(pack) { pct -> _state.update { it.copy(progressPct = pct) } }
            _state.update {
                it.copy(
                    busyId = null,
                    progressPct = 0,
                    notice = result.fold(
                        onSuccess = { "${pack.title} is in the library — it works offline from now on." },
                        onFailure = { e -> "Could not install ${pack.title}: ${e.message ?: "the download failed"}" },
                    ),
                )
            }
            refresh()
        }
    }

    fun remove(pack: ContentPack.Pack) {
        if (_state.value.busyId != null) return
        viewModelScope.launch {
            val gone = runCatching { repository.remove(pack.id) }.getOrDefault(false)
            _state.update {
                it.copy(notice = if (gone) "${pack.title} removed. Nothing bundled was touched." else null)
            }
            refresh()
        }
    }

    private fun describe(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "401" in m || "404" in m ->
                "Could not read the pack catalog. A private repository needs a GitHub token — " +
                    "add one in Settings."
            "catalog" in m -> m
            else -> m.ifBlank { "The pack catalog could not be read." }
        }
    }
}
