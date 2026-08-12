package dev.mascwa.pulse.core.telemetry

/** One intraday observation: epoch-ms timestamp + value (a price, an index level, or a yield level). */
data class MarketBar(val tsMs: Long, val value: Double)

/**
 * Pure "what actually moved after the story crossed the wires" math for the MARKET REACTION + IMPACT
 * desk note. Given an instrument's intraday bars (5-minute closes in practice) and a story's publish
 * timestamp, [windowMove] measures the instrument's real move over the ~[DEFAULT_WINDOW_MS] that
 * followed — the spec's "first 30-90 minutes after the story crossed the wires" — instead of letting a
 * language model guess at it.
 *
 * Two honest regimes, decided by how fresh the last print before publish is:
 *  - **Live window** (venue open at publish): last bar at/before publish → last bar inside the window.
 *  - **Reopen** (venue closed at publish — the baseline print is stale): the story landed on a closed
 *    market, so the measurable reaction is the next session's first ~window against the prior close.
 *    Flagged [Move.reopen] so the prompt/reader knows it is a reopen gap, not a live tape.
 *
 * Returns null whenever the series cannot honestly support a measurement (no print before publish, no
 * print after it, or nothing new inside a live window) — a dropped tape line, never a made-up one.
 */
object MarketWindow {

    /** The top of the spec's 30-90-minute wires window. */
    const val DEFAULT_WINDOW_MS: Long = 90 * 60_000L

    /** A pre-publish print older than this means the venue wasn't trading when the story hit: with
     *  5-minute bars a live session's baseline is minutes old, so 45 min of silence is a closed market
     *  (or a halted/delisted series — either way, not a live window). */
    const val DEFAULT_STALE_MS: Long = 45 * 60_000L

    data class Move(
        val fromValue: Double,
        val toValue: Double,
        /** Minutes actually spanned — baseline→end for a live window, reopen-bar→end for a reopen. */
        val minutes: Int,
        /** True when the venue was closed at publish and this is the next session's gap vs prior close. */
        val reopen: Boolean,
    ) {
        val delta: Double get() = toValue - fromValue
        val pct: Double get() = if (fromValue != 0.0) (toValue - fromValue) / fromValue * 100.0 else 0.0
    }

    fun windowMove(
        bars: List<MarketBar>,
        publishMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        staleMs: Long = DEFAULT_STALE_MS,
    ): Move? {
        if (publishMs <= 0L) return null
        val sorted = bars.asSequence()
            .filter { it.value.isFinite() && it.tsMs > 0L }
            .sortedBy { it.tsMs }
            .toList()
        if (sorted.isEmpty()) return null

        val baselineIdx = sorted.indexOfLast { it.tsMs <= publishMs }
        if (baselineIdx < 0) return null // the story predates the whole series — nothing to measure
        val baseline = sorted[baselineIdx]

        if (publishMs - baseline.tsMs <= staleMs) {
            // Live window: the venue was printing when the story hit.
            val endIdx = sorted.indexOfLast { it.tsMs <= publishMs + windowMs }
            if (endIdx <= baselineIdx) return null // nothing has printed since the story crossed
            val end = sorted[endIdx]
            return Move(
                fromValue = baseline.value,
                toValue = end.value,
                minutes = ((end.tsMs - baseline.tsMs) / 60_000L).toInt(),
                reopen = false,
            )
        }

        // Reopen: the venue was closed at publish. Measure the next session's first ~window against the
        // prior close (the baseline). A single post-publish print still counts — that IS the gap.
        val reopenIdx = sorted.indexOfFirst { it.tsMs > publishMs }
        if (reopenIdx < 0) return null // the market hasn't reopened since the story
        val endIdx = sorted.indexOfLast { it.tsMs <= sorted[reopenIdx].tsMs + windowMs }
        val end = sorted[endIdx] // endIdx >= reopenIdx by construction
        return Move(
            fromValue = baseline.value,
            toValue = end.value,
            minutes = ((end.tsMs - sorted[reopenIdx].tsMs) / 60_000L).toInt(),
            reopen = true,
        )
    }
}
