package dev.mascwa.pulse.core.telemetry

/** The direction a story likely pushes a linked market: up (good), down (bad), or unclear. */
enum class MarketImpact { UP, DOWN, MIXED }

/**
 * A market a news story touches, the direction it likely pushes it, a static market descriptor ([why]),
 * a causal one-liner for THIS story ([rationale] — the "Trading Places" read: what in reality is moving
 * this market), and a 1..3 [strength] (how clearly the story implies the move — drives ordering + UI emphasis).
 */
data class MarketLink(
    val market: String,
    val impact: MarketImpact,
    val why: String,
    val rationale: String = "",
    val strength: Int = 1,
)

/**
 * Maps a news headline to the market(s) it's associated with / would move, and the likely direction — so
 * each article can show, beneath its summary, a "MARKET REACTION" read: who this touches, which way, and WHY.
 * The framing is the (legal) Duke-brothers move from *Trading Places*: you're just reading how real markets
 * react to what's actually in the news — a frozen-crop report lifts orange-juice futures, a chip breakthrough
 * carries the semis, a strike hits the automakers, a rate cut lifts the banks.
 *
 * The taxonomy is DELIBERATELY WIDE (≈40 markets): broad indices, macro (bonds, the dollar, small caps),
 * every major sector, individual soft/hard commodities, and consumer-behaviour signals — so even a minor,
 * local or political story surfaces what it touches, not only the big headlines. Genuinely non-market news
 * (a parking-rules debate, a sports result) still yields nothing. Pure logic (CI-gated): keyword sector
 * matching for the association + a move/event lexicon for the direction (UP/DOWN when the headline states or
 * clearly implies a move, MIXED otherwise — honest rather than over-claiming).
 *
 * This is a heuristic read of a headline, NOT financial advice or a live quote; it says "watch these".
 */
object NewsMarketLink {

    // Generic price-move / good-news words (push a linked market UP) …
    private val UP = listOf(
        "surge", "soar", "jump", "rally", "gains", "gain", "rise", "rises", "climb", "rebound", "spike",
        "record high", "all-time high", "beats", "record profit", "breakthrough", "approval", "approved",
        "merger", "acquire", "wins contract", "upgrade", "boom", "booms", "rallies", "soars", "jumps",
        "climbs", "recovers", "subsidy", "stimulus", "bailout", "tax cut", "rate cut", "demand jumps",
        "sells out", "sold out", "shortage", "strong demand", "expands", "hiring",
    )

    // … and generic price-move / bad-news words (push a linked market DOWN).
    private val DOWN = listOf(
        "plunge", "crash", "tumble", "sink", "fall", "falls", "drop", "slump", "slide", "selloff", "sell-off",
        "misses", "missed", "earnings miss", "loss", "layoff", "layoffs", "recession", "bankruptcy",
        "downgrade", "warning", "cuts jobs", "plummet", "recall", "probe", "lawsuit", "scandal", "glut",
        "sinks", "falls", "drops", "plunges", "tumbles", " sued", "default", "boycott", "strike", "walkout",
        "ban", "banned", "tariff", "sanction", "shutdown", "oversupply", "weak demand", "empty shelves",
        "price cut", "fine", "collapse", "shortfall", "closes stores", "tax hike",
    )

    // Safe-haven / crisis words that lift havens (gold, defense, oil) even when the news is "bad".
    private val CRISIS = listOf(
        "war", "attack", "conflict", "invasion", "invades", "crisis", "tension", "missile", "sanction",
        "airstrike", "terror", "coup", "uncertainty", "escalation", "hostilities",
    )

    private data class Def(
        val market: String,
        val why: String,
        val triggers: List<String>,
        val haven: Boolean = false,            // lifted by CRISIS words
        val extraUp: List<String> = emptyList(),
        val extraDown: List<String> = emptyList(),
        val upWhy: String = "",
        val downWhy: String = "",
    )

    // Ordered most-specific → most-general; specific sectors/commodities win over the broad indices.
    private val DEFS = listOf(
        // ---- Energy ----
        Def("Oil", "energy prices", listOf("oil", "crude", "opec", "barrel", "gasoline", "gas price", "pipeline", "refinery", "wti", "brent"),
            extraUp = listOf("production cut", "supply cut", "opec cut", "embargo", "sanction", "war", "hurricane"),
            extraDown = listOf("glut", "oversupply", "demand slump"),
            upWhy = "Supply tightens — less crude on the market means a higher barrel.",
            downWhy = "Supply glut — more crude than buyers, so the barrel sags."),
        Def("Natural Gas", "heating & power fuel", listOf("natural gas", "lng", "gas storage", "gas heating", "henry hub"),
            upWhy = "Cold snaps / supply cuts spike gas demand.",
            downWhy = "Mild weather / a glut sinks gas."),
        Def("Solar & Clean", "renewables", listOf("solar", "renewable", "clean energy", "wind farm", "photovoltaic", "green energy", "ev charging"),
            upWhy = "Subsidies / a clean-energy push lift renewables.",
            downWhy = "Subsidy cuts / cheap fossil fuels weigh on clean energy."),
        Def("Uranium & Nuclear", "nuclear power", listOf("uranium", "nuclear plant", "reactor", "nuclear power"),
            upWhy = "A nuclear build-out lifts uranium.",
            downWhy = "A plant setback weighs on nuclear."),
        // ---- Precious & industrial metals / materials ----
        Def("Gold", "safe-haven demand", listOf("gold", "bullion", "precious metal", "safe haven", "safe-haven"), haven = true,
            upWhy = "Flight to safety — when the world looks scary, money hides in gold.",
            downWhy = "Risk-on — calm markets don't need a safe haven."),
        Def("Silver", "precious/industrial metal", listOf("silver"),
            upWhy = "Haven bid + industrial demand lift silver.",
            downWhy = "Risk-on / weak industry weighs on silver."),
        Def("Copper", "the economy's metal", listOf("copper"),
            upWhy = "Copper rises when builders and factories are busy.",
            downWhy = "Slowing construction/industry drags copper down."),
        Def("Steel & Iron", "heavy industry", listOf("steel", "iron ore", "blast furnace"),
            upWhy = "Building and tariffs on imports can lift domestic steel.",
            downWhy = "A construction slump / cheap imports hit steel."),
        Def("Lithium & Battery", "battery metals", listOf("lithium", "battery metal", "cobalt", "battery plant"),
            upWhy = "EV/battery demand lifts lithium.",
            downWhy = "An EV slowdown / oversupply sinks lithium."),
        Def("Mining & Materials", "raw materials", listOf("mining", "miner", "ore", "smelter", "quarry", "raw materials"),
            upWhy = "Rising commodity demand lifts the miners.",
            downWhy = "Falling demand weighs on materials."),
        // ---- Tech complex ----
        Def("Chips", "semiconductors", listOf("chip", "semiconductor", "nvidia", "tsmc", "foundry", "gpu", "wafer"),
            upWhy = "More demand for silicon carries the whole semi supply chain.",
            downWhy = "Softer chip demand / a glut weighs on the semis."),
        Def("AI", "artificial intelligence", listOf("artificial intelligence", " ai ", "ai model", "chatbot", "machine learning", "large language model", "openai", "generative ai"),
            upWhy = "An AI advance / spending wave lifts the AI names.",
            downWhy = "AI doubts / a spending pullback cool the trade."),
        Def("Cybersecurity", "digital defence", listOf("cybersecurity", "ransomware", "data breach", "cyberattack", "hacking", "malware", "cyber"),
            upWhy = "A breach wave drives spending on cyber defence.",
            downWhy = "A quiet threat picture eases cyber spend."),
        Def("Cloud & Software", "enterprise software", listOf("cloud computing", "saas", "software", "data center", "data centre"),
            upWhy = "Cloud/software growth lifts the sector.",
            downWhy = "IT-budget cuts weigh on software."),
        Def("Social Media", "social platforms", listOf("social media", "facebook", "instagram", "tiktok", "twitter", "x platform", "snapchat", "reddit"),
            upWhy = "Engagement/ad strength lifts the platforms.",
            downWhy = "Ad weakness / a ban hits social media."),
        Def("Telecom", "carriers & 5G", listOf("telecom", "5g", "broadband", "wireless carrier", "at&t", "verizon", "t-mobile"),
            upWhy = "Subscriber/network strength helps telecom.",
            downWhy = "Price wars / churn weigh on carriers."),
        Def("Tech", "tech sector", listOf("tech", "technology", "apple", "iphone", "google", "microsoft", "meta", "silicon valley", "gadget", "app store"),
            upWhy = "Growth story — the market pays up for tech that's winning.",
            downWhy = "Rich valuations sell off first when tech disappoints."),
        // ---- Financials ----
        Def("Regional Banks", "community lenders", listOf("regional bank", "community bank", "local bank", "credit union", "small bank"),
            upWhy = "Deposit stability / steeper rates help regional banks.",
            downWhy = "Deposit flight / bad loans hit regional banks hard."),
        Def("Banks", "rates & lending", listOf("bank", "banking", "lender", "federal reserve", "the fed", "interest rate", "rate hike", "rate cut", "credit crunch", "wall street"),
            upWhy = "Fatter net-interest margins / easing stress help the lenders.",
            downWhy = "Credit stress / squeezed margins hit the banks."),
        Def("Insurance", "insurers", listOf("insurance", "insurer", "reinsurance", "premium", "claims payout", "catastrophe loss"),
            upWhy = "Higher premiums / few claims help insurers.",
            downWhy = "A catastrophe / big payouts hit insurers."),
        Def("Bonds & Rates", "government debt", listOf("treasury", "treasuries", "bond yield", "10-year", "government debt", "national debt", "deficit", "credit rating", "yield"),
            upWhy = "Falling rates / safe-haven buying lift bond prices.",
            downWhy = "Rising yields / inflation push bond prices down."),
        Def("US Dollar", "the greenback", listOf("dollar", "u.s. dollar", "currency", "forex", "exchange rate", "greenback", "devaluation"),
            upWhy = "Haven demand / higher US rates lift the dollar.",
            downWhy = "Rate cuts / risk-on soften the dollar."),
        // ---- Health ----
        Def("Pharma", "drugmakers", listOf("pharma", "drug", "vaccine", "fda", "medicine", "prescription"),
            upWhy = "An approval or a hit drug is a fresh revenue stream.",
            downWhy = "A failed trial / recall / probe punishes pharma."),
        Def("Biotech", "biotech", listOf("biotech", "clinical trial", "gene therapy", "mrna", "biotech startup"),
            upWhy = "Positive trial data lifts biotech.",
            downWhy = "A trial failure sinks biotech."),
        // ---- Industry, transport, real estate ----
        Def("Defense", "defense spending", listOf("defense", "defence", "military", "weapons", "arms deal", "pentagon", "nato", "warplane", "missile"), haven = true,
            upWhy = "Conflict means orders — defense contractors sell more hardware.",
            downWhy = "Peace dividend — de-escalation trims the order book."),
        Def("Airlines", "air travel", listOf("airline", "flights", "boeing", "airbus", "air travel", "aviation", "jet fuel"),
            upWhy = "Fuller planes + cheaper fuel is pure profit for carriers.",
            downWhy = "Pricier fuel or softer travel clips the airlines."),
        Def("Shipping & Freight", "supply chain", listOf("shipping", "freight", "container", "port", "supply chain", "logistics", "trucking"),
            upWhy = "Trade volume / higher freight rates lift shippers.",
            downWhy = "A trade slump / a port jam hits freight."),
        Def("Industrials", "factories & machinery", listOf("factory", "manufacturing", "industrial", "machinery", "caterpillar", "assembly line"),
            upWhy = "A manufacturing pickup lifts industrials.",
            downWhy = "A factory slowdown weighs on industrials."),
        Def("Autos & EV", "carmakers", listOf("automaker", "electric vehicle", " ev ", "tesla", "ford", "carmaker", "auto sales", "car sales", "vehicle recall"),
            upWhy = "Strong deliveries / demand drive the carmakers.",
            downWhy = "A recall, strike or price war dents the automakers."),
        Def("Housing", "homes & builders", listOf("housing", "mortgage", "real estate", "home sales", "homebuilder", "construction", "house prices"),
            upWhy = "Lower rates / more sales pull homebuilders up.",
            downWhy = "Higher rates freeze buyers — housing cools."),
        Def("Real Estate & REITs", "commercial property", listOf("commercial real estate", "office space", "reit", "landlord", "vacancy", "rent prices", "shopping mall"),
            upWhy = "Occupancy / rent strength lifts property.",
            downWhy = "Empty offices / falling rents hit REITs."),
        Def("Utilities", "power & water", listOf("utility", "power grid", "electricity price", "water utility", "blackout", "power outage"),
            upWhy = "Steady demand / rate increases help utilities.",
            downWhy = "Grid failures / rate freezes weigh on utilities."),
        // ---- Consumer & staples ----
        Def("Retail", "consumer spending", listOf("retail", "consumer spending", "walmart", "shoppers", "holiday sales", "retail sales", "store closures", "back to school"),
            upWhy = "Shoppers spending freely lifts the retailers.",
            downWhy = "A pinched consumer / empty tills weigh on retail."),
        Def("E-commerce", "online shopping", listOf("e-commerce", "online shopping", "amazon", "online sales", "same-day delivery", "marketplace seller"),
            upWhy = "Online demand / delivery growth lifts e-commerce.",
            downWhy = "A spending pullback hits online retail."),
        Def("Consumer Staples", "everyday goods", listOf("grocery", "groceries", "household goods", "detergent", "toothpaste", "packaged food", "supermarket", "cpg", "empty shelves"),
            upWhy = "Staples hold up — people always buy essentials.",
            downWhy = "Trading-down / price caps squeeze staples."),
        Def("Restaurants & Fast Food", "dining out", listOf("restaurant", "fast food", "dining", "mcdonald", "starbucks", "chipotle", "diner", "menu prices"),
            upWhy = "Foot traffic / price power lifts the chains.",
            downWhy = "Diners cutting back hits restaurants."),
        Def("Travel & Hotels", "tourism", listOf("hotel", "cruise", "resort", "tourism", "vacation", "airbnb", "theme park", "bookings"),
            upWhy = "A travel boom fills rooms and ships.",
            downWhy = "A travel pullback empties them."),
        Def("Alcohol & Beverages", "drinks", listOf("beer", "wine", "spirits", "brewery", "distillery", "soda", "beverage maker"),
            upWhy = "Strong sales lift the drinks makers.",
            downWhy = "Weak sales / taxes weigh on beverages."),
        Def("Tobacco & Vaping", "tobacco", listOf("tobacco", "cigarette", "vaping", "vape", "nicotine", "e-cigarette"),
            upWhy = "Pricing power supports tobacco.",
            downWhy = "A ban / tax / health ruling hits tobacco."),
        Def("Cannabis", "cannabis", listOf("cannabis", "marijuana", "dispensary", "legal weed", "cbd"),
            upWhy = "Legalisation / access lifts cannabis.",
            downWhy = "A regulatory setback sinks cannabis."),
        Def("Gaming & Casinos", "gambling & games", listOf("casino", "gambling", "sports betting", "video game", "gaming company", "slot machine", "lottery"),
            upWhy = "Player spending / a hit title lifts the sector.",
            downWhy = "Weak spending / a flop weighs on it."),
        Def("Media & Streaming", "entertainment", listOf("streaming", "netflix", "disney", "box office", "hollywood", "studio", "cable tv", "subscribers"),
            upWhy = "Subscriber growth / a box-office hit lifts media.",
            downWhy = "Cord-cutting / a flop hits media."),
        Def("Luxury", "high-end goods", listOf("luxury", "designer brand", "high-end", "handbag", "haute couture", "luxury goods"),
            upWhy = "Wealthy spending lifts luxury.",
            downWhy = "A luxury slowdown (esp. in China) bites."),
        // ---- Agriculture / softs ----
        Def("Coffee", "coffee futures", listOf("coffee", "arabica", "robusta"),
            extraUp = listOf("frost", "drought", "shortage"),
            upWhy = "A bad harvest / frost spikes coffee.",
            downWhy = "A bumper crop sinks coffee."),
        Def("Cocoa & Chocolate", "cocoa", listOf("cocoa", "chocolate", "cacao"),
            extraUp = listOf("shortage", "disease", "drought"),
            upWhy = "A cocoa shortage spikes chocolate costs.",
            downWhy = "A good crop eases cocoa."),
        Def("OJ Futures", "frozen-concentrate crop", listOf("orange juice", "orange crop", "citrus", "frozen concentrate", " oj ", "florida orange"),
            extraUp = listOf("frost", "freeze", "cold snap", "crop damage", "hurricane", "shortage", "drought"),
            extraDown = listOf("bumper crop", "record harvest"),
            upWhy = "The crop's threatened — a bad freeze report is exactly what spikes OJ (ask the Dukes).",
            downWhy = "A bumper harvest floods the market — concentrate gets cheap."),
        Def("Grains & Food", "food & crops", listOf("wheat", "corn", "soybean", "harvest", "crop", "food price", "grain", "fertilizer", "drought hits"),
            upWhy = "A short harvest bids up grain and food prices.",
            downWhy = "A big harvest / falling prices weigh on ag."),
        // ---- Crypto ----
        Def("Bitcoin", "crypto", listOf("bitcoin", "crypto", "ethereum", "blockchain", "btc", "stablecoin", "coinbase"),
            upWhy = "Risk appetite + inflows lift the whole crypto complex.",
            downWhy = "Risk-off / a crackdown drains crypto fast."),
        // ---- Broad market / macro (most general — last) ----
        Def("Small Caps", "small & local business", listOf("small business", "local business", "main street", "small-cap", "startup", "community business", "mom-and-pop", "small firm"),
            upWhy = "Easier credit / a strong consumer helps small business.",
            downWhy = "Tight credit / a weak consumer squeezes small firms first."),
        Def("Stocks", "the broad market", listOf("stock market", "s&p", "nasdaq", "dow jones", "gdp", "jobs report", "unemployment", "recession", "inflation", "economy", "election", "tariff", "regulation", "antitrust", "trade war", "consumer confidence", "fed", "cpi"),
            upWhy = "Risk-on — the broad tape catches a bid.",
            downWhy = "Risk-off — the whole tape leaks lower."),
    )

    // A news category → the broad market it implicitly touches, so even a plain sector headline gets a read.
    private val CATEGORY_MARKET = mapOf(
        "Business" to "Stocks", "Tech" to "Tech", "Science" to "Pharma", "Health" to "Pharma",
        "Politics" to "Stocks",
    )

    /** The markets a story touches, with likely direction + a causal reason. Capped to the top [max]. */
    fun linksFor(title: String, summary: String = "", category: String = "", max: Int = 4): List<MarketLink> {
        val t = " ${(title + " " + summary).lowercase()} "
        val hits = DEFS.filter { def -> def.triggers.any { it in t } }.toMutableList()

        // Category baseline: if the article's news category implies a broad market not already matched by
        // keyword, add it (low strength) so a sector story still shows something to watch.
        CATEGORY_MARKET[category]?.let { name ->
            if (hits.none { it.market == name }) DEFS.firstOrNull { it.market == name }?.let { hits += it }
        }
        if (hits.isEmpty()) return emptyList()

        return hits
            .map { def -> toLink(t, def, category) }
            .sortedByDescending { it.strength }   // clearest reactions first
            .take(max)
    }

    private fun toLink(t: String, def: Def, category: String): MarketLink {
        val impact = impactFor(t, def)
        val fromCategory = def.triggers.none { it in t } // added only via the category baseline
        val rationale = when (impact) {
            MarketImpact.UP -> def.upWhy.ifBlank { "The news reads bullish for ${def.market.lowercase()}." }
            MarketImpact.DOWN -> def.downWhy.ifBlank { "The news reads bearish for ${def.market.lowercase()}." }
            MarketImpact.MIXED -> "In the crosshairs — a story to watch for ${def.why}."
        }
        val strength = when {
            fromCategory -> 1
            impact == MarketImpact.MIXED -> 2
            else -> 3
        }
        return MarketLink(def.market, impact, def.why, rationale, strength)
    }

    private fun impactFor(t: String, def: Def): MarketImpact {
        val up = UP.any { it in t } || def.extraUp.any { it in t } || (def.haven && CRISIS.any { it in t })
        val down = DOWN.any { it in t } || def.extraDown.any { it in t }
        return when {
            up && !down -> MarketImpact.UP
            down && !up -> MarketImpact.DOWN
            else -> MarketImpact.MIXED
        }
    }

    /** A short "why the markets moved" caption, e.g. "Reality check: lifts Oil, Gold; weighs on Airlines." */
    fun summarize(links: List<MarketLink>): String {
        if (links.isEmpty()) return ""
        val up = links.filter { it.impact == MarketImpact.UP }.map { it.market }
        val down = links.filter { it.impact == MarketImpact.DOWN }.map { it.market }
        val parts = mutableListOf<String>()
        if (up.isNotEmpty()) parts += "lifts ${up.joinToString(", ")}"
        if (down.isNotEmpty()) parts += "weighs on ${down.joinToString(", ")}"
        if (parts.isEmpty()) return "In play: ${links.joinToString(", ") { it.market }}."
        return "Reality check: " + parts.joinToString("; ") + "."
    }

    /** The single sharpest causal line for the strip header — the top-strength link's rationale. */
    fun headline(links: List<MarketLink>): String =
        links.maxByOrNull { it.strength }?.rationale ?: ""
}
