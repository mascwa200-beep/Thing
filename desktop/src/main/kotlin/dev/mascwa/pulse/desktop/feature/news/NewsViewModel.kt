package dev.mascwa.pulse.desktop.feature.news

import dev.mascwa.pulse.desktop.news.Article
import dev.mascwa.pulse.desktop.news.NewsCategory
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NewsUiState(
    val category: NewsCategory = NewsCategory.TOP,
    val articles: List<Article> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Drives the News screen. A plain class holding a [StateFlow] — Compose Desktop has no AndroidX ViewModel,
 * and the Android `NewsViewModel` is bound to `Context`, so this is an adaptation rather than a port.
 *
 * Switching category cancels the in-flight fetch: without that, tabbing quickly through the rail leaves
 * several requests racing and the last one to *finish* wins, which is not necessarily the tab you are
 * looking at.
 */
class NewsViewModel(
    private val scope: CoroutineScope,
    private val repository: NewsRepository,
    private val settings: DesktopSettingsStore,
) {
    private val _state = MutableStateFlow(NewsUiState())
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        scope.launch {
            val saved = runCatching { NewsCategory.valueOf(settings.current().newsCategory) }
                .getOrDefault(NewsCategory.TOP)
            _state.value = _state.value.copy(category = saved)
            load(saved)
        }
    }

    fun select(category: NewsCategory) {
        if (category == _state.value.category && _state.value.articles.isNotEmpty()) return
        _state.value = _state.value.copy(category = category, error = null)
        scope.launch { settings.update { it.copy(newsCategory = category.name) } }
        load(category)
    }

    fun refresh() = load(_state.value.category, force = true)

    private fun load(category: NewsCategory, force: Boolean = false) {
        job?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        job = scope.launch {
            repository.headlines(category, force)
                .onSuccess { articles ->
                    // Guard against a stale response landing after the user moved on.
                    if (_state.value.category == category) {
                        _state.value = _state.value.copy(articles = articles, loading = false, error = null)
                    }
                }
                .onFailure { e ->
                    if (_state.value.category == category) {
                        _state.value = _state.value.copy(
                            loading = false,
                            error = "Could not load headlines: ${e.message ?: "no connection"}",
                        )
                    }
                }
        }
    }
}
