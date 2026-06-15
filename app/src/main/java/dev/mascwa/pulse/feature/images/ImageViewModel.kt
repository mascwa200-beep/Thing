package dev.mascwa.pulse.feature.images

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.toUserMessage
import dev.mascwa.pulse.data.images.ImageRepository
import dev.mascwa.pulse.data.images.ImageResult
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImageUiState(
    val query: String = "",
    val siteUrls: List<String> = emptyList(),
    val selected: Int = 0, // 0 = Web search; 1.. = custom sites
    val results: Async<List<ImageResult>> = Async(),
) {
    val chips: List<String> get() = listOf("Web") + siteUrls.map { hostLabel(it) }
}

private fun hostLabel(url: String): String =
    url.substringAfter("://").substringBefore("/").removePrefix("www.").ifBlank { url }.take(18)

class ImageViewModel(
    private val repo: ImageRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ImageUiState())
    val state: StateFlow<ImageUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.settings.collect { s ->
                _state.update { it.copy(siteUrls = s.customImageSites) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun selectSource(index: Int) {
        _state.update { it.copy(selected = index) }
        if (_state.value.query.isNotBlank() || index > 0) search()
    }

    fun search() {
        val st = _state.value
        if (st.selected == 0 && st.query.isBlank()) return
        viewModelScope.launch {
            _state.update { it.copy(results = it.results.copy(loading = true, error = null)) }
            try {
                val imgs = if (st.selected == 0) repo.searchWeb(st.query)
                else repo.fromSite(st.siteUrls.getOrElse(st.selected - 1) { return@launch }, st.query)
                _state.update { it.copy(results = Async(data = imgs, loading = false)) }
            } catch (e: Throwable) {
                _state.update { it.copy(results = Async(loading = false, error = e.toUserMessage())) }
            }
        }
    }

    fun addSite(url: String) {
        val u = url.trim()
        if (u.isBlank()) return
        viewModelScope.launch {
            settings.update { it.copy(customImageSites = (it.customImageSites + u).distinct()) }
        }
    }

    fun removeSite(url: String) {
        viewModelScope.launch {
            settings.update { it.copy(customImageSites = it.customImageSites - url) }
        }
    }
}
