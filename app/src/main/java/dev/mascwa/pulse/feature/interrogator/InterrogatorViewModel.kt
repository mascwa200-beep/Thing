package dev.mascwa.pulse.feature.interrogator

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Rebuttal
import dev.mascwa.pulse.data.interrogator.AcousticInterrogatorService
import dev.mascwa.pulse.data.interrogator.TranscriptStore
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The interrogator screen's state.
 *
 * The cascade's flows are owned by the singleton in [AppContainer], so this is a window rather than
 * a driver: findings accumulate whether or not the screen is open, and the screen sees the most
 * recent one immediately because [dev.mascwa.pulse.data.interrogator.InterrogatorCascade.findings]
 * replays.
 */
class InterrogatorViewModel(private val c: AppContainer) : ViewModel() {

    val findings = c.interrogatorCascade.findings

    /**
     * Whether it is listening.
     *
     * ⚠️ Derived from the SETTING, not from a flag this class keeps. The notification's Stop action
     * writes that setting from outside the app entirely, so a screen holding its own copy would sit
     * showing LISTENING at a microphone that had already stopped.
     */
    val listening: StateFlow<Boolean> = c.settingsRepository.settings
        .map { it.sensing.interrogator }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val lastTrace = c.interrogatorCascade.lastTrace

    private val _lines = MutableStateFlow<List<TranscriptStore.Line>>(emptyList())

    /**
     * The transcript, newest first.
     *
     * ⚠️ Loaded on demand rather than exposed as a flow. Every line has to be decrypted to be read,
     * and a flow that re-decrypted the whole window on each new utterance would do that work
     * continuously in the background — for a screen nobody is looking at most of the time.
     */
    val lines: StateFlow<List<TranscriptStore.Line>> = _lines.asStateFlow()

    private val _kept = MutableStateFlow(0)
    val kept: StateFlow<Int> = _kept.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val modelReady: Boolean get() = c.whisperEngine.loaded
    val adjudicatorReady: Boolean get() = c.llamaEngine.loaded
    val adjudicatorPresent: Boolean get() = c.llamaEngine.modelPresent()

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true
            runCatching { c.transcriptStore.prune() }
            _lines.value = runCatching { c.transcriptStore.recent() }.getOrDefault(emptyList())
            _kept.value = runCatching { c.transcriptStore.count() }.getOrDefault(0)
            _busy.value = false
        }
    }

    /** Start listening. Only from a visible screen — see the service. */
    fun start(context: Context) {
        viewModelScope.launch {
            runCatching {
                c.settingsRepository.update { it.copy(sensing = it.sensing.copy(interrogator = true)) }
            }
            runCatching { AcousticInterrogatorService.start(context) }
        }
    }

    fun stop(context: Context) {
        viewModelScope.launch {
            runCatching {
                c.settingsRepository.update { it.copy(sensing = it.sensing.copy(interrogator = false)) }
            }
            runCatching { AcousticInterrogatorService.stop(context) }
        }
    }

    /**
     * Fetch the adjudicator's weights — about a gigabyte, so only ever from this explicit tap.
     */
    fun fetchAdjudicator() {
        viewModelScope.launch {
            _busy.value = true
            runCatching { c.llamaEngine.prepare(allowDownload = true) }
            _busy.value = false
        }
    }

    /**
     * Erase everything.
     *
     * ⚠️ Deletes the database FILE, not its rows: deleting rows leaves the text in freed SQLite
     * pages, which is not what anyone tapping this expects. The cascade's cooldowns and repeat
     * counts go with it, because those are derived from what was said.
     */
    fun purge() {
        viewModelScope.launch {
            _busy.value = true
            runCatching { c.transcriptStore.purge() }
            runCatching { c.interrogatorCascade.reset() }
            _lines.value = emptyList()
            _kept.value = 0
            _busy.value = false
        }
    }

    companion object {
        /** What the screen shows when the model has run and found nothing worth raising. */
        fun quietLine(trace: dev.mascwa.pulse.data.interrogator.InterrogatorCascade.Trace?): String =
            when (trace?.verdict) {
                null -> "Nothing heard yet."
                dev.mascwa.pulse.core.telemetry.Discourse.Verdict.NO_CLAIM ->
                    "Heard, no claim in it."
                dev.mascwa.pulse.core.telemetry.Discourse.Verdict.NO_CANDIDATE ->
                    "A claim, but nothing suspect in how it was argued."
                dev.mascwa.pulse.core.telemetry.Discourse.Verdict.HEDGED ->
                    "Already stated as a guess — left alone."
                dev.mascwa.pulse.core.telemetry.Discourse.Verdict.COOLING_DOWN ->
                    "Same pattern as a moment ago — counted, not raised."
                dev.mascwa.pulse.core.telemetry.Discourse.Verdict.RATE_LIMITED ->
                    "Enough for this hour."
                dev.mascwa.pulse.core.telemetry.Discourse.Verdict.ESCALATE ->
                    "Checked with the adjudicator."
            }

        /** The provenance, in the register of the screen rather than the enum. */
        fun provenanceLine(p: Rebuttal.Provenance): String = when (p) {
            Rebuttal.Provenance.PATTERN_ONLY -> "WORDING ONLY"
            Rebuttal.Provenance.GROUNDED -> "WORDING + LIBRARY"
            Rebuttal.Provenance.REASONED -> "READ AND JUDGED"
        }
    }
}
