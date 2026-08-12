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
 * Two regimes, decided by how fresh the last print before publish is — and described by MEASURABLE
 * facts only. A stale baseline does NOT prove the venue was closed (a thin instrument can simply go
 * 45 minutes without a trade mid-session), so the result never claims closure; it reports the gap:
 *  - **Live** ([Move.reopen] false): the last print at/before publish is ≤ [DEFAULT_STALE_MS] old —
 *    baseline → last print inside publish+window.
 *  - **Gapped/reopen** ([Move.reopen] true): the last pre-publish print is older than that — the move
 *    is measured from that prior print into the first traded window after publish, and
 *    [Move.baselineGapMinutes] says exactly how stale the starting print was. Callers word it as
 *    "the last print predates the wire by N minutes", never as an assertion about venue hours.
 *
 * Returns null whenever the series cannot honestly support a measurement (no print before publish, no
 * print after it, nothing new inside a live window, or a zero/non-finite print that would poison the
 * percentage) — a dropped tape line, never a made-up one.
 */
object MarketWindow {

    /** The top of the spec's 30-90-minute wires window. */
    const val DEFAULT_WINDOW_MS: Long = 90 * 60_000L

    /** A pre-publish print older than this switches to the gapped/reopen regime: with 5-minute bars a
     *  live series' baseline is minutes old, so 45 min of silence means the venue was closed OR the
     *  instrument simply wasn't printing — the result reports the gap and asserts nothing more. */
    const val DEFAULT_STALE_MS: Long = 45 * 60_000L

    data class Move(
        val fromValue: Double,
        val toValue: Double,
        /** Minutes the starting print predates publish — ~0-5 for a live series, large when gapped. */
        val baselineGapMinutes: Int,
        /** Minutes after publish at which the measured window ends (always > 0). */
        val endOffsetMinutes: Int,
        /** True when the starting print was stale ([DEFAULT_STALE_MS]) — a gapped/reopen measurement
         *  from the prior print into the first traded window after publish. */
        val reopen: Boolean,
    ) {
        val delta: Double get() = toValue - fromValue
        val pct: Double get() = (toValue - fromValue) / fromValue * 100.0 // fromValue != 0 by construction
    }

    fun windowMove(
        bars: List<MarketBar>,
        publishMs: Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        staleMs: Long = DEFAULT_STALE_MS,
    ): Move? {
        if (publishMs <= 0L) return null
        // A 0.0 print on these instruments is a feed artifact, and it would also poison pct — drop it.
        val sorted = bars.asSequence()
            .filter { it.value.isFinite() && it.value != 0.0 && it.tsMs > 0L }
            .sortedBy { it.tsMs }
            .toList()
        if (sorted.isEmpty()) return null

        val baselineIdx = sorted.indexOfLast { it.tsMs <= publishMs }
        if (baselineIdx < 0) return null // the story predates the whole series — nothing to measure
        val baseline = sorted[baselineIdx]
        val gapMin = ((publishMs - baseline.tsMs) / 60_000L).toInt()

        if (publishMs - baseline.tsMs <= staleMs) {
            // Live: the instrument was printing around publish.
            val endIdx = sorted.indexOfLast { it.tsMs <= publishMs + windowMs }
            if (endIdx <= baselineIdx) return null // nothing has printed since the story crossed
            val end = sorted[endIdx]
            return Move(
                fromValue = baseline.value,
                toValue = end.value,
                baselineGapMinutes = gapMin,
                endOffsetMinutes = ((end.tsMs - publishMs) / 60_000L).toInt(),
                reopen = false,
            )
        }

        // Gapped: no prints near publish. Measure from the prior print into the first traded window
        // after publish. A single post-publish print still counts — that IS the gap move.
        val reopenIdx = sorted.indexOfFirst { it.tsMs > publishMs }
        if (reopenIdx < 0) return null // nothing has printed since the story at all
        val endIdx = sorted.indexOfLast { it.tsMs <= sorted[reopenIdx].tsMs + windowMs }
        val end = sorted[endIdx] // endIdx >= reopenIdx by construction
        return Move(
            fromValue = baseline.value,
            toValue = end.value,
            baselineGapMinutes = gapMin,
            endOffsetMinutes = ((end.tsMs - publishMs) / 60_000L).toInt(),
            reopen = true,
        )
    }
}
