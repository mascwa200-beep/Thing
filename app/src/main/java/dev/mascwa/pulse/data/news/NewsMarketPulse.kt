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
    // Keys MUST match the market names NewsMarketLink emits. A CURATED, liquid subset — widened this pass
    // toward NewsMarketLink's full ~49-market taxonomy (the "insider knowledge" ask: more chips carry a
    // real live % instead of falling back to the static heuristic arrow) while staying honest — a handful
    // of markets (Restaurants & Fast Food, Travel & Hotels, Alcohol & Beverages, Tobacco & Vaping, Luxury)
    // have no sufficiently liquid single pure-play instrument and are deliberately left off; they still
    // show their heuristic direction arrow. `quotesFor` fires one throttled request per symbol.
    private val BASKET: Map<String, WatchItem> = mapOf(
        "Oil" to WatchItem("cl.f", "Oil", WatchType.COMMODITY),          // WTI crude CL=F
        "Natural Gas" to WatchItem("ng.f", "Nat Gas", WatchType.COMMODITY), // NG=F
        "Solar & Clean" to WatchItem("tan", "Solar", WatchType.STOCK),   // Invesco Solar ETF
        "Uranium & Nuclear" to WatchItem("ura", "Uranium", WatchType.STOCK), // Global X Uranium
        "Gold" to WatchItem("gc.f", "Gold", WatchType.COMMODITY),        // GC=F
        "Silver" to WatchItem("si.f", "Silver", WatchType.COMMODITY),    // SI=F
        "Copper" to WatchItem("hg.f", "Copper", WatchType.COMMODITY),    // HG=F
        "Steel & Iron" to WatchItem("slx", "Steel", WatchType.STOCK),    // VanEck Steel
        "Lithium & Battery" to WatchItem("lit", "Lithium", WatchType.STOCK), // Global X Lithium & Battery
        "Mining & Materials" to WatchItem("xlb", "Materials", WatchType.STOCK), // Materials Select Sector
        "Chips" to WatchItem("smh", "Chips", WatchType.STOCK),           // VanEck Semiconductor
        "AI" to WatchItem("botz", "AI/Robotics", WatchType.STOCK),       // Global X Robotics & AI
        "Cybersecurity" to WatchItem("cibr", "Cyber", WatchType.STOCK),  // First Trust Cybersecurity
        "Cloud & Software" to WatchItem("igv", "Cloud/SW", WatchType.STOCK), // iShares Expanded Tech-Software
        "Social Media" to WatchItem("socl", "Social Media", WatchType.STOCK), // Global X Social Media
        "Telecom" to WatchItem("iyz", "Telecom", WatchType.STOCK),       // iShares US Telecom
        "Tech" to WatchItem("xlk", "Tech", WatchType.STOCK),             // Tech Select Sector
        "Regional Banks" to WatchItem("kre", "Reg Banks", WatchType.STOCK), // SPDR Regional Banking
        "Banks" to WatchItem("xlf", "Banks", WatchType.STOCK),           // Financial Select Sector
        "Insurance" to WatchItem("kie", "Insurance", WatchType.STOCK),   // SPDR Insurance
        "Bonds & Rates" to WatchItem("tlt", "Treasuries", WatchType.STOCK), // 20+yr Treasury
        "US Dollar" to WatchItem("uup", "US Dollar", WatchType.STOCK),   // Invesco DB USD Bullish
        "Small Caps" to WatchItem("iwm", "Small Caps", WatchType.STOCK), // Russell 2000
        "Pharma" to WatchItem("xlv", "Pharma", WatchType.STOCK),         // Health Care Select Sector
        "Biotech" to WatchItem("xbi", "Biotech", WatchType.STOCK),       // SPDR Biotech
        "Defense" to WatchItem("ita", "Defense", WatchType.STOCK),       // iShares Aerospace & Defense
        "Airlines" to WatchItem("jets", "Airlines", WatchType.STOCK),    // US Global Jets
        "Shipping & Freight" to WatchItem("iyt", "Freight", WatchType.STOCK), // iShares Transportation Average
        "Industrials" to WatchItem("xli", "Industrials", WatchType.STOCK), // Industrial Select Sector
        "Autos & EV" to WatchItem("carz", "Autos/EV", WatchType.STOCK),  // First Trust Autos
        "Housing" to WatchItem("xhb", "Housing", WatchType.STOCK),       // Homebuilders
        "Real Estate & REITs" to WatchItem("vnq", "Real Estate", WatchType.STOCK),
        "Utilities" to WatchItem("xlu", "Utilities", WatchType.STOCK),   // Utilities Select Sector
        "Retail" to WatchItem("xrt", "Retail", WatchType.STOCK),         // Retail SPDR
        "E-commerce" to WatchItem("ibuy", "E-commerce", WatchType.STOCK), // Amplify Online Retail
        "Consumer Staples" to WatchItem("xlp", "Staples", WatchType.STOCK),
        "Cannabis" to WatchItem("msos", "Cannabis", WatchType.STOCK),    // AdvisorShares Pure US Cannabis
        "Gaming & Casinos" to WatchItem("bjk", "Gaming", WatchType.STOCK), // VanEck Gaming
        "Media & Streaming" to WatchItem("xlc", "Media", WatchType.STOCK), // Communication Services
        "Coffee" to WatchItem("kc.f", "Coffee", WatchType.COMMODITY),    // KC=F
        "Cocoa & Chocolate" to WatchItem("cc.f", "Cocoa", WatchType.COMMODITY), // CC=F
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
