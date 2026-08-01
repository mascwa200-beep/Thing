package dev.mascwa.pulse.data.news

import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.settings.WatchItem
import dev.mascwa.pulse.data.settings.WatchType

/**
 * A fixed "market pulse" basket keyed by the market names [dev.mascwa.pulse.core.telemetry.NewsMarketLink]
 * produces, so an article's market strip can show each linked market's live ±% today. Fetched once via
 * Yahoo ([MarketsRepository.quotesFor]) and cached in the news ViewModel; fully defensive — any symbol that
 * fails to quote (throttled / bad id) simply omits its %, and the strip falls back to the heuristic arrow.
 */
object NewsMarketPulse {

    // NewsMarketLink market name -> the instrument to quote (Yahoo id conventions from the watchlist:
    // "^spx"/etc. resolve via YAHOO_OVERRIDES, ".f" -> "=F" commodities, ETF tickers pass through).
    private val BASKET: Map<String, WatchItem> = mapOf(
        "Oil" to WatchItem("cl.f", "Oil", WatchType.COMMODITY),          // WTI crude CL=F
        "Gold" to WatchItem("gc.f", "Gold", WatchType.COMMODITY),        // GC=F
        "Defense" to WatchItem("ita", "Defense", WatchType.STOCK),       // iShares Aerospace & Defense
        "Bitcoin" to WatchItem("btc-usd", "Bitcoin", WatchType.CRYPTO),  // BTC-USD via Yahoo
        "Chips" to WatchItem("smh", "Chips", WatchType.STOCK),           // VanEck Semiconductor
        "Tech" to WatchItem("xlk", "Tech", WatchType.STOCK),             // Tech Select Sector
        "Banks" to WatchItem("xlf", "Banks", WatchType.STOCK),           // Financial Select Sector
        "Airlines" to WatchItem("jets", "Airlines", WatchType.STOCK),    // US Global Jets
        "Housing" to WatchItem("xhb", "Housing", WatchType.STOCK),       // Homebuilders
        "Pharma" to WatchItem("xlv", "Pharma", WatchType.STOCK),         // Health Care Select Sector
        "Autos/EV" to WatchItem("carz", "Autos/EV", WatchType.STOCK),    // First Trust Autos
        "Retail" to WatchItem("xrt", "Retail", WatchType.STOCK),         // Retail SPDR
        "Food" to WatchItem("dba", "Food", WatchType.STOCK),             // Invesco Agriculture
        "Stocks" to WatchItem("^spx", "S&P 500", WatchType.INDEX),       // -> ^GSPC via override
    )

    /** market name -> today's % change, for whatever quoted successfully. Empty on total failure. */
    suspend fun fetch(markets: MarketsRepository): Map<String, Double> {
        val quotes = runCatching { markets.quotesFor(BASKET.values.toList()) }.getOrNull() ?: return emptyMap()
        val byId = quotes.associateBy { it.id }
        return BASKET.mapNotNull { (market, item) ->
            byId[item.id]?.changePercent?.let { market to it }
        }.toMap()
    }
}
