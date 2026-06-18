package dev.mascwa.pulse.feature.diagnostics

import androidx.lifecycle.ViewModel
import dev.mascwa.pulse.crash.CrashEntry
import dev.mascwa.pulse.crash.CrashReporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Backs the SYS crash console: lists recorded faults and exposes read / clear / (debug) simulate. */
class CrashLogViewModel(private val reporter: CrashReporter) : ViewModel() {

    private val _entries = MutableStateFlow<List<CrashEntry>>(emptyList())
    val entries: StateFlow<List<CrashEntry>> = _entries.asStateFlow()

    init { refresh() }

    fun refresh() { _entries.value = reporter.entries() }

    fun read(entry: CrashEntry): String = reporter.read(entry)

    fun clear() {
        reporter.clear()
        refresh()
    }

    /** Debug-only: throw on a background thread to validate the global handler end-to-end. */
    fun simulateCrash() {
        Thread { throw RuntimeException("Simulated fault — crash console test") }.start()
    }
}
