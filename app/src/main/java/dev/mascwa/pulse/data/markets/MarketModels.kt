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
    val updatedEpochMs: Long = 0L,
    val sparkline: List<Double> = emptyList(),
)

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
