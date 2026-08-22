package dev.mascwa.pulse.desktop.diagnostics

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records, once, that a negative dimension was caught on its way into intrinsic measurement.
 *
 * ⚠️ **This is the half of the clamp that keeps it honest.** `Modifier.clampIntrinsics` prevents a
 * fault that has never been traced to its producer; absorbing it silently would remove the only
 * evidence anyone will ever get. So the clamp reports here, and the report goes to the same place
 * the fault dialog's does — MENU → CRASH CONSOLE and `diagnostics\fault-*.txt` — with the live
 * stack, so [FaultTrace] can name the frames above the clamp.
 *
 * ⚠️ **One report per process. This is not tidiness, it is a hard requirement.** The clamp runs
 * inside layout, and a layout that clamps once will clamp again on every subsequent frame; writing
 * a file per frame would be a far worse defect than the one being contained. The latch is set
 * before the write, so even a concurrent second caller cannot double-report.
 *
 * Nothing is recorded until [install] has been called, which means tests, headless renders and any
 * composition before startup cost nothing at all.
 */
object IntrinsicClampWatch {

    private val fired = AtomicBoolean(false)

    @Volatile
    private var reporter: CrashReporter? = null

    @Volatile
    private var buildLabel: String = "unknown build"

    /** Last thing caught, for a test or a surface to read. Null until something is. */
    @Volatile
    var last: String? = null
        private set

    fun install(reporter: CrashReporter, buildLabel: String) {
        this.reporter = reporter
        this.buildLabel = buildLabel
    }

    /**
     * Called from the clamp on the layout thread, with the offending value.
     *
     * [label] names the composable the clamp was placed on, because the stack alone will be full of
     * Compose measure frames and the app frame nearest the fault is not always this one.
     */
    fun clamped(label: String, query: String, axis: String, value: Int) {
        last = "$label · $query received $axis $value"
        // Latch FIRST: two layout passes racing must not both write.
        if (!fired.compareAndSet(false, true)) return
        val r = reporter ?: return
        val note = IllegalStateException(
            "A negative $axis ($value) was clamped to 0 before $query. The panel was drawn, but " +
                "something above produced an impossible dimension — the frames below name it.",
        )
        runCatching { r.record(Thread.currentThread(), note, buildLabel, where = "clamp · $label") }
    }

    /** Tests only: forget that anything fired, so each case starts from a clean latch. */
    internal fun resetForTest() {
        fired.set(false)
        last = null
        reporter = null
        buildLabel = "unknown build"
    }
}
