package dev.mascwa.pulse.feature.oracle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.DayAhead
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.data.oracle.DayAheadEngine
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
        /**
         * What the Oracle has learned about which of its own rules you act on.
         *
         * Surfaced rather than left to work invisibly: a ranking that quietly reshapes itself is
         * indistinguishable from a ranking that is drifting, and the user is owed the difference.
         */
        val learned: String = "",
        /**
         * The rest of the day, projected.
         *
         * Separate from [insights] because it answers a different question: those are about now,
         * this is about what is coming. Empty when the calendar is empty or unreadable, which is the
         * common case and reads as an absent section rather than an error.
         */
        val dayAhead: List<DayAhead.Beat> = emptyList(),
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
            val learned = runCatching { container.oracleLearningStore.summary() }.getOrDefault("")
            // Best-effort and last, because it may route: a failure here costs the timeline, never
            // the ranked read above it.
            val day = if (settings != null) {
                runCatching { DayAheadEngine.plan(container, settings) }.getOrDefault(emptyList())
            } else emptyList()
            _state.update {
                it.copy(
                    loading = false, insights = insights,
                    briefing = Oracle.briefing(insights), updatedMs = System.currentTimeMillis(),
                    learned = learned, dayAhead = day,
                )
            }
        }
    }
}
