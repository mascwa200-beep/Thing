package dev.mascwa.pulse.feature.survive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.survival.Guide
import dev.mascwa.pulse.data.survival.SurvivalContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GuidesViewModel(
    private val content: SurvivalContentRepository,
) : ViewModel() {
    private val _guides = MutableStateFlow<List<Guide>>(emptyList())
    val guides: StateFlow<List<Guide>> = _guides.asStateFlow()

    init {
        viewModelScope.launch {
            _guides.value = runCatching { content.guides() }.getOrDefault(emptyList())
        }
    }
}
