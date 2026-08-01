package dev.mascwa.pulse.feature.oracle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.data.oracle.OracleEngine
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The interactive ORACLE surface: reads the full ranked foresight on demand + a one-line J.A.R.V.I.S. briefing. */
class OracleViewModel(private val container: AppContainer) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val insights: List<Insight> = emptyList(),
        val briefing: String = "",
        val updatedMs: Long = 0L,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.insights.isEmpty()) }
            val settings = runCatching { container.settingsRepository.current() }.getOrNull()
            val insights = if (settings != null)
                runCatching { OracleEngine.read(container, settings) }.getOrDefault(emptyList())
            else emptyList()
            _state.update {
                it.copy(
                    loading = false, insights = insights,
                    briefing = Oracle.briefing(insights), updatedMs = System.currentTimeMillis(),
                )
            }
        }
    }
}
