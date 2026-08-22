package dev.mascwa.pulse.data.markets

import kotlinx.serialization.Serializable

@Serializable
data class Quote(
    val id: String,
    val label: String,
    val type: String,               // WatchType name
    val price: Double? = null,
    val change: Double? = null,
    val changePercent: Double? = null,
    val previousClose: Double? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val volume: Double? = null,
    val marketCap: Double? = null,
    val currency: String = "USD",
    val imageUrl: String? = null,
    /** When the app fetched this. NOT when the market last traded — see [marketTimeMs]. */
    val updatedEpochMs: Long = 0L,
    val sparkline: List<Double> = emptyList(),
    /** The year's extremes, for placing today's price in a range rather than in a vacuum. */
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    /**
     * The venue's own timestamp on the last trade.
     *
     * The distinction from [updatedEpochMs] is the point. Outside market hours the price is a print
     * from hours or days ago, and showing a fetch time beside it reads as freshness the number does
     * not have.
     */
    val marketTimeMs: Long? = null,
    /** The venue's sessions for the day, so the screen can say whether the market is even open. */
    val hours: SessionHours? = null,
    /** The venue's own name for the instrument, which is often better than the app's own label. */
    val name: String? = null,
    /** Where it trades ("NasdaqGS", "CME"). */
    val exchange: String? = null,
    /** Decimal places the venue quotes in — 2 for most equities, 4 for FX. Null when unstated. */
    val priceHint: Int? = null,
)

/**
 * A venue's trading windows for the day, in epoch milliseconds.
 *
 * A flat serializable mirror of `MarketSession.Windows` rather than the core type itself, because
 * `core:telemetry` deliberately carries no serialization dependency. Convert at the boundary.
 */
@Serializable
data class SessionHours(
    val preStartMs: Long? = null,
    val preEndMs: Long? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val postStartMs: Long? = null,
    val postEndMs: Long? = null,
) {
    /** Null bounds drop the whole window — a half-known session is not a session. */
    fun toWindows(): dev.mascwa.pulse.core.telemetry.MarketSession.Windows =
        dev.mascwa.pulse.core.telemetry.MarketSession.Windows(
            pre = window(preStartMs, preEndMs),
            regular = window(startMs, endMs),
            post = window(postStartMs, postEndMs),
        )

    private fun window(a: Long?, b: Long?) =
        if (a != null && b != null) dev.mascwa.pulse.core.telemetry.MarketSession.Window(a, b) else null
}

@Serializable
data class QuoteList(val quotes: List<Quote>)

// --- CoinGecko DTO (field names match the JSON; unknown keys ignored) ---

@Serializable
data class CoinMarket(
    val id: String = "",
    val symbol: String = "",
    val name: String = "",
    val image: String? = null,
    val current_price: Double? = null,
    val market_cap: Double? = null,
    val total_volume: Double? = null,
    val high_24h: Double? = null,
    val low_24h: Double? = null,
    val price_change_24h: Double? = null,
    val price_change_percentage_24h: Double? = null,
    val sparkline_in_7d: CoinSparkline? = null,
)

@Serializable
data class CoinSparkline(val price: List<Double> = emptyList())
