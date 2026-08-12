package dev.mascwa.pulse.data.news

import dev.mascwa.pulse.core.telemetry.MarketWindow
import dev.mascwa.pulse.data.markets.MarketsRepository
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The wires-window tape for the MARKET REACTION + IMPACT desk note: for one story's publish timestamp,
 * measure what the macro complex ACTUALLY did in the ~90 minutes after the story crossed the wires —
 * index futures, the curve, the dollar, volatility, key commodities, a credit proxy — from real
 * 5-minute bars ([MarketsRepository.intradayBars] + the pure, CI-tested
 * [dev.mascwa.pulse.core.telemetry.MarketWindow]). These measured prints are what the desk-note prompt
 * is allowed to cite; without them every magnitude must stay directional. A line whose series can't
 * honestly support a measurement (fetch failed, venue closed with no reopen yet, story predates the
 * bars) is DROPPED, never estimated.
 *
 * Cost/shape: at most [complex]-many chart fetches per analysis, deduplicated by the repository's
 * per-symbol cache and throttled by its shared Yahoo gate; the returned block is ~9 short lines.
 */
class MarketTape(private val markets: MarketsRepository) {

    private data class Instrument(val label: String, val symbol: String, val isYield: Boolean = false)

    /** The instrument classes the desk-note spec names, as canonical Yahoo symbols (the app's own
     *  Stooq→Yahoo override table uses the same `=F`/`^` conventions). An unknown/renamed symbol just
     *  yields a dropped line — the tape degrades, it never lies. */
    private val complex = listOf(
        Instrument("S&P 500 futures", "ES=F"),
        Instrument("Nasdaq 100 futures", "NQ=F"),
        Instrument("10-year Treasury yield", "^TNX", isYield = true),
        Instrument("2-year yield futures", "2YY=F", isYield = true),
        Instrument("US dollar index", "DX-Y.NYB"),
        Instrument("VIX", "^VIX"),
        Instrument("Gold futures", "GC=F"),
        Instrument("WTI crude futures", "CL=F"),
        Instrument("High-yield credit proxy (HYG)", "HYG"),
    )

    /** The measured tape block for a story published at [publishMs], one line per instrument that has an
     *  honest window move — or null when nothing measurable exists (bad timestamp, all fetches failed). */
    suspend fun wiresWindow(publishMs: Long): String? {
        if (publishMs <= 0L) return null
        val lines = coroutineScope {
            complex.map { inst ->
                async {
                    runCatching {
                        val bars = markets.intradayBars(inst.symbol) ?: return@async null
                        val move = MarketWindow.windowMove(bars, publishMs) ?: return@async null
                        format(inst, move)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun format(inst: Instrument, m: MarketWindow.Move): String {
        // Yields quoted in percent units (e.g. 4.281) get a basis-point read; the <30 sanity guard means
        // a differently-scaled feed simply loses the bp annotation instead of shipping a wrong one.
        val change = if (inst.isYield && kotlin.math.abs(m.fromValue) < 30.0) {
            val bp = m.delta * 100.0
            String.format(Locale.US, "%+.1f bp", bp)
        } else {
            String.format(Locale.US, "%+.2f%%", m.pct)
        }
        // Each line states its own MEASURED span relative to the wire — never an assertion about venue
        // hours. A stale starting print says exactly how stale ("last print Xm before the wire"): a
        // weekend gap and a thin instrument's mid-session silence read the same, which is the truth.
        val span = if (m.reopen) {
            "last print ${dur(m.baselineGapMinutes)} BEFORE the wire (market not printing at publish) -> " +
                "first traded window after, ending ${dur(m.endOffsetMinutes)} past the wire"
        } else {
            "wire-${m.baselineGapMinutes}m -> wire+${m.endOffsetMinutes}m"
        }
        return "${inst.label} (${inst.symbol}): ${num(m.fromValue)} -> ${num(m.toValue)} ($change), $span"
    }

    private fun dur(minutes: Int): String =
        if (minutes >= 120) "${minutes / 60}h${(minutes % 60).let { if (it > 0) "${it}m" else "" }}"
        else "${minutes}m"

    private fun num(v: Double): String = when {
        kotlin.math.abs(v) >= 1000 -> String.format(Locale.US, "%.1f", v)
        kotlin.math.abs(v) >= 10 -> String.format(Locale.US, "%.2f", v)
        else -> String.format(Locale.US, "%.3f", v)
    }
}
