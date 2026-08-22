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
    /**
     * Read straight through for its last report — no state of its own here.
     *
     * ⚠️ The extraction report belongs on THIS screen rather than a new one. It answers the same
     * question the console already exists to answer ("what went wrong?"), and splitting that
     * across two places is how a diagnosis ends up half-read. The console is also already labelled
     * shareable, which is the right handling for a report someone will send on.
     */
    private val extractor: dev.mascwa.pulse.data.media.MediaExtractor,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<CrashEntry>>(emptyList())
    val entries: StateFlow<List<CrashEntry>> = _entries.asStateFlow()

    private val _uploadStatus = MutableStateFlow<String?>(null)
    val uploadStatus: StateFlow<String?> = _uploadStatus.asStateFlow()

    init { refresh() }

    fun refresh() { _entries.value = reporter.entries() }

    /** The last extraction's own account of itself, untruncated, or null if nothing has resolved. */
    fun extractionReport(): String? = extractor.lastReport

    /**
     * What happened the last time the widget drew itself, or null if it has not tried in this
     * process.
     *
     * Read straight off [dev.mascwa.pulse.widget.WidgetDiagnostics] rather than injected: it is a
     * process-wide record with no dependencies, exactly like the alert condition the widget reads
     * for its accent, and threading it through the container would buy nothing.
     */
    fun widgetReport(): String? =
        dev.mascwa.pulse.widget.WidgetDiagnostics.report(dev.mascwa.pulse.widget.WidgetDiagnostics.last)

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
