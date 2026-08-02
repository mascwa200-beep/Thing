package dev.mascwa.pulse.core.telemetry

/**
 * The one shared "are we in the owner's quiet-hours window right now" check — used to gate background
 * notification passes ([dev.mascwa.pulse.notifications.RefreshWorker]) and live in-app pulses
 * ([dev.mascwa.pulse.jarvis.matrix.ActiveMatrixService]) identically, instead of two independently-maintained
 * copies of the same overnight-window arithmetic.
 */
object QuietHours {
    /** True when [hour] (0-23) falls inside the [start]..[end] window, which may wrap past midnight
     *  (e.g. 22..7). Always false when [enabled] is false. */
    fun isQuiet(enabled: Boolean, start: Int, end: Int, hour: Int): Boolean {
        if (!enabled) return false
        return if (start <= end) hour in start until end else hour >= start || hour < end
    }
}
