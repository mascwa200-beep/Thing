package dev.mascwa.pulse.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.settings.SearchEngine
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

class SearchViewModel(
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _engine = MutableStateFlow(SearchEngine.DUCKDUCKGO)
    val engine: StateFlow<SearchEngine> = _engine.asStateFlow()

    init { viewModelScope.launch { _engine.value = settings.current().searchEngine } }

    fun setEngine(e: SearchEngine) {
        _engine.value = e
        viewModelScope.launch { settings.update { it.copy(searchEngine = e) } }
    }

    fun urlFor(query: String): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return _engine.value.urlTemplate.format(q)
    }
}
