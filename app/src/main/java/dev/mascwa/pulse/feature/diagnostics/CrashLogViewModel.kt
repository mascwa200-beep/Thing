package dev.mascwa.pulse.feature.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.crash.CrashEntry
import dev.mascwa.pulse.crash.CrashReporter
import dev.mascwa.pulse.data.diagnostics.DebugUploader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Backs the SYS crash console: lists recorded faults and exposes read / clear / send-to-repo / simulate. */
class CrashLogViewModel(
    private val reporter: CrashReporter,
    private val uploader: DebugUploader,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<CrashEntry>>(emptyList())
    val entries: StateFlow<List<CrashEntry>> = _entries.asStateFlow()

    private val _uploadStatus = MutableStateFlow<String?>(null)
    val uploadStatus: StateFlow<String?> = _uploadStatus.asStateFlow()

    init { refresh() }

    fun refresh() { _entries.value = reporter.entries() }

    fun read(entry: CrashEntry): String = reporter.read(entry)

    /** Upload a scrubbed debug report (latest crash + diagnostics) to the repo for remote reading. */
    fun sendReport() {
        _uploadStatus.value = "Sending…"
        viewModelScope.launch {
            _uploadStatus.value = when (val r = uploader.sendNow()) {
                is DebugUploader.Result.Ok -> "Sent → debug-reports/${r.path}"
                is DebugUploader.Result.Skipped -> "Skipped: ${r.reason}"
                is DebugUploader.Result.Failed -> "Failed: ${r.reason}"
            }
        }
    }

    fun clear() {
        reporter.clear()
        refresh()
    }

    /** Debug-only: throw on a background thread to validate the global handler end-to-end. */
    fun simulateCrash() {
        Thread { throw RuntimeException("Simulated fault — crash console test") }.start()
    }
}
