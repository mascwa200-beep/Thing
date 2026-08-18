package dev.mascwa.pulse.core.telemetry

/**
 * How long a rocket actually has to get off the ground, as opposed to the single instant the app
 * prints.
 *
 * Launch Library publishes `window_start` and `window_end` beside the T-0 on every launch measured,
 * and the app declared neither. That looked minor until the interaction with `net_precision` was
 * checked, and the interaction is the whole finding:
 *
 * > `Falcon 9 | Starlink Group 17-50` — T-0 `2026-08-19T03:45:08Z`, precision **Second**,
 * > window `02:00 → 06:00`.
 *
 * The screen's existing honesty guard (`UpcomingLaunch.timeIsFirm`) suppresses a precise clock time
 * when the precision is coarse — and here the precision is the finest the feed has, so the guard
 * passes and the app prints a **second-accurate** time for a launch that may go at any point across
 * **four hours**. The precision field describes how well the T-0 itself is known; it says nothing
 * about how much room the flight has. Only the window does, and it was being thrown away.
 *
 * This is deliberately clock-free: it answers *how wide* and *is that worth saying*, and leaves the
 * caller to format the two instants with the device's own zone. Rendering a UTC time beside local
 * ones is a mistake this app has already made twice, and the way not to make it a third time is to
 * keep formatting out of the pure core entirely.
 */
object LaunchWindow {

    /**
     * Below this the window tells the reader nothing they do not already have from the T-0.
     *
     * Ten minutes is chosen against the feed's own precision ladder: the finest precision it
     * publishes is to the second and the next is to the minute, so a window of a few minutes is
     * inside the noise the printed time already carries. Anything wider is real information — the
     * narrowest genuine window in the sampled feed was 37 minutes.
     */
    const val MIN_MEANINGFUL_MS = 10 * 60_000L

    /** The window's width, or null when either end is missing or the pair is nonsensical. */
    fun widthMs(startMs: Long?, endMs: Long?): Long? {
        val s = startMs ?: return null
        val e = endMs ?: return null
        val width = e - s
        return if (width <= 0L) null else width
    }

    /** Whether the window is worth a line of its own. */
    fun isMeaningful(startMs: Long?, endMs: Long?): Boolean =
        (widthMs(startMs, endMs) ?: 0L) >= MIN_MEANINGFUL_MS

    /**
     * The width in words — "4 hours", "37 minutes", "2 days".
     *
     * Rounded rather than truncated, so a window of 3 h 50 m is not called three hours. Whole units
     * only: a launch window is a planning figure, and "3 hours 47 minutes" implies a precision the
     * providers themselves do not intend.
     */
    fun describeWidth(widthMs: Long): String {
        if (widthMs <= 0L) return "instantaneous"
        val minutes = Math.round(widthMs / 60_000.0)
        if (minutes < 90) return plural(minutes, "minute")
        val hours = Math.round(widthMs / 3_600_000.0)
        if (hours < 36) return plural(hours, "hour")
        return plural(Math.round(widthMs / 86_400_000.0), "day")
    }

    /**
     * True when the T-0 is the earliest the flight can go, which is the ordinary case and reads
     * differently from a T-0 sitting inside a window that has already opened.
     */
    fun netIsWindowOpen(netMs: Long?, startMs: Long?): Boolean {
        val n = netMs ?: return false
        val s = startMs ?: return false
        return n <= s
    }

    private fun plural(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"
}
