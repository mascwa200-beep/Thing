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
    // Keys MUST match the market names NewsMarketLink emits. This is a CURATED, liquid subset (~26) so the
    // one-off background fetch stays light (quotesFor fires one throttled request per symbol — a 40-wide
    // burst would risk Yahoo 429s). NewsMarketLink's taxonomy is far wider; markets not in this basket still
    // show their heuristic direction arrow — only the live ±% needs an instrument here.
    private val BASKET: Map<String, WatchItem> = mapOf(
        "Oil" to WatchItem("cl.f", "Oil", WatchType.COMMODITY),          // WTI crude CL=F
        "Natural Gas" to WatchItem("ng.f", "Nat Gas", WatchType.COMMODITY), // NG=F
        "Gold" to WatchItem("gc.f", "Gold", WatchType.COMMODITY),        // GC=F
        "Copper" to WatchItem("hg.f", "Copper", WatchType.COMMODITY),    // HG=F
        "Chips" to WatchItem("smh", "Chips", WatchType.STOCK),           // VanEck Semiconductor
        "AI" to WatchItem("botz", "AI/Robotics", WatchType.STOCK),       // Global X Robotics & AI
        "Cybersecurity" to WatchItem("cibr", "Cyber", WatchType.STOCK),  // First Trust Cybersecurity
        "Tech" to WatchItem("xlk", "Tech", WatchType.STOCK),             // Tech Select Sector
        "Regional Banks" to WatchItem("kre", "Reg Banks", WatchType.STOCK), // SPDR Regional Banking
        "Banks" to WatchItem("xlf", "Banks", WatchType.STOCK),           // Financial Select Sector
        "Bonds & Rates" to WatchItem("tlt", "Treasuries", WatchType.STOCK), // 20+yr Treasury
        "US Dollar" to WatchItem("uup", "US Dollar", WatchType.STOCK),   // Invesco DB USD Bullish
        "Small Caps" to WatchItem("iwm", "Small Caps", WatchType.STOCK), // Russell 2000
        "Pharma" to WatchItem("xlv", "Pharma", WatchType.STOCK),         // Health Care Select Sector
        "Defense" to WatchItem("ita", "Defense", WatchType.STOCK),       // iShares Aerospace & Defense
        "Airlines" to WatchItem("jets", "Airlines", WatchType.STOCK),    // US Global Jets
        "Autos & EV" to WatchItem("carz", "Autos/EV", WatchType.STOCK),  // First Trust Autos
        "Housing" to WatchItem("xhb", "Housing", WatchType.STOCK),       // Homebuilders
        "Real Estate & REITs" to WatchItem("vnq", "Real Estate", WatchType.STOCK),
        "Retail" to WatchItem("xrt", "Retail", WatchType.STOCK),         // Retail SPDR
        "Consumer Staples" to WatchItem("xlp", "Staples", WatchType.STOCK),
        "Media & Streaming" to WatchItem("xlc", "Media", WatchType.STOCK), // Communication Services
        "Coffee" to WatchItem("kc.f", "Coffee", WatchType.COMMODITY),    // KC=F
        "OJ Futures" to WatchItem("oj.f", "OJ Futures", WatchType.COMMODITY), // OJ=F
        "Grains & Food" to WatchItem("dba", "Agriculture", WatchType.STOCK), // Invesco Agriculture
        "Bitcoin" to WatchItem("btc-usd", "Bitcoin", WatchType.CRYPTO),  // BTC-USD via Yahoo
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
