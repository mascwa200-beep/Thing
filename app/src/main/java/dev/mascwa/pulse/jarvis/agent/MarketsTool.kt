package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.MarketSession
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.markets.Quote
import kotlin.math.abs

/**
 * The user's own watchlist, as the app already has it.
 *
 * Without this the console answers "how are my stocks doing?" with a web search, while the app holds
 * live quotes for the instruments this particular user chose, their day ranges, their position in the
 * year, and the venue's own session state. Reads the warm cache rather than forcing a fetch, so
 * asking costs nothing the app was not already paying.
 *
 * Read-only.
 */
class MarketsTool(private val markets: MarketsRepository) : JarvisTool {
    override val name = "markets"
    override val usage =
        "markets [name] — the user's watchlist right now: prices, moves, and whether the market is " +
            "even open (blank = the whole list and its overall mood)"

    override suspend fun run(arg: String): String {
        val quotes = runCatching { markets.fetchAll(force = false).data }.getOrNull()
            ?: return "I couldn't read the markets — the quote feed didn't answer."
        if (quotes.isEmpty()) return "The watchlist is empty."

        val filter = arg.trim()
        if (filter.isNotBlank()) {
            val match = quotes.filter {
                it.label.contains(filter, true) || it.id.contains(filter, true) ||
                    it.name?.contains(filter, true) == true
            }
            if (match.isEmpty()) {
                return "\"$filter\" isn't on the watchlist. It holds: " +
                    quotes.joinToString(", ") { it.label }
            }
            return match.joinToString("\n\n") { detail(it) }
        }

        val moves = quotes.mapNotNull { it.changePercent }
        val mood = MarketMood.summarize(moves)
        return buildString {
            if (mood != null) {
                append(mood.plain).append("\n")
                append(mood.up).append(" up · ").append(mood.down).append(" down")
                if (mood.flat > 0) append(" · ").append(mood.flat).append(" flat")
                append(" · net ").append(signed(mood.netChangePct)).append("%\n")
            }
            quotes.sortedByDescending { abs(it.changePercent ?: 0.0) }.forEach { append("\n").append(line(it)) }
            append("\n\nAsk for one by name for its day range, year range and session state.")
        }
    }

    /** One instrument, one line, sorted so the biggest movers are read first. */
    private fun line(q: Quote): String = buildString {
        append("• ").append(q.label).append("  ")
        append(price(q))
        q.changePercent?.let { append("  ").append(signed(it)).append("%") }
        if (closed(q)) append("  (market closed)")
    }

    private fun detail(q: Quote): String = buildString {
        append(q.name ?: q.label)
        q.exchange?.let { append("  [").append(it).append("]") }
        append("\n").append(price(q))
        q.changePercent?.let { pct ->
            append("  ").append(signed(pct)).append("%")
            q.change?.let { append(" (").append(signed(it, q)).append(")") }
        }

        // What the venue is doing. A closed market's price looks exactly like a live one, and saying
        // so is the difference between a quote and a stale number presented as a quote.
        val phase = MarketSession.phaseAt(q.hours?.toWindows(), System.currentTimeMillis())
        when (phase) {
            MarketSession.Phase.OPEN -> append("\nMarket open.")
            MarketSession.Phase.PRE -> append("\nPre-market — the regular session hasn't started.")
            MarketSession.Phase.AFTER -> append("\nAfter hours — the regular session has closed.")
            MarketSession.Phase.CLOSED -> append("\nMarket closed; this is the last print, not a live price.")
            MarketSession.Phase.UNKNOWN -> Unit // Saying nothing is honest; claiming CLOSED would not be.
        }

        // ⚠️ Hoisted, exactly as the year's range below already was. `Quote` lives in `:core:feeds`
        // now, and Kotlin will not smart-cast a public property declared in a DIFFERENT module — the
        // guard above reads as though it narrows and does not.
        val dayLow = q.low
        val dayHigh = q.high
        if (dayLow != null && dayHigh != null) {
            append("\nToday: ").append(num(dayLow, q)).append(" – ").append(num(dayHigh, q))
        }
        val lo = q.fiftyTwoWeekLow
        val hi = q.fiftyTwoWeekHigh
        if (lo != null && hi != null) {
            append("\nYear: ").append(num(lo, q)).append(" – ").append(num(hi, q))
            MarketSession.describeRange(MarketSession.rangePosition(q.price, lo, hi))
                ?.let { append(" — ").append(it) }
        }
    }

    private fun closed(q: Quote): Boolean =
        MarketSession.phaseAt(q.hours?.toWindows(), System.currentTimeMillis()) == MarketSession.Phase.CLOSED

    private fun price(q: Quote): String =
        q.price?.let { num(it, q) + " " + q.currency } ?: "no price"

    /**
     * The venue's own precision, so an FX pair is not rounded to the two decimals that hide its move.
     *
     * ⚠️ Derived from the instrument's PRICE, never from the number being printed. Keying it off the
     * value would give a −3.42 change on an ordinary stock four decimal places, because the change is
     * small even when the price is not.
     */
    private fun dp(q: Quote): Int =
        q.priceHint?.coerceIn(0, 6) ?: if (abs(q.price ?: 100.0) < 10.0) 4 else 2

    private fun num(v: Double, q: Quote): String =
        String.format(java.util.Locale.US, "%.${dp(q)}f", v)

    private fun signed(v: Double): String = String.format(java.util.Locale.US, "%+.2f", v)
    private fun signed(v: Double, q: Quote): String = (if (v >= 0) "+" else "") + num(v, q)
}
