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
 * Maps a news headline to the market(s) it's associated with / would move, and the likely direction —
 * so each article can show, beneath its summary, a "MARKET REACTION" read: who this touches, which way,
 * and WHY. The framing is the (legal) Duke-brothers move from *Trading Places*: you're just reading how
 * real markets react to what's actually in the news — a frozen-crop report lifts orange-juice futures, a
 * war scare bids up gold and defense, a chip breakthrough carries the semis. Pure logic (CI-gated):
 * keyword sector-matching for the association + a move/event lexicon for the direction (UP/DOWN when the
 * headline states or clearly implies a move, MIXED otherwise — honest rather than over-claiming).
 *
 * This is a heuristic read of a headline, NOT financial advice or a live quote; it says "watch these",
 * not "this will happen".
 */
object NewsMarketLink {

    // Generic price-move / good-news words (push a linked market UP) …
    private val UP = listOf(
        "surge", "soar", "jump", "rally", "gains", "rise", "climb", "rebound", "spike", "record high",
        "all-time high", "beats", "record profit", "breakthrough", "approval", "approved", "merger",
        "wins contract", "upgrade", "boom", "rallies", "soars", "jumps", "climbs", "recovers",
    )

    // … and generic price-move / bad-news words (push a linked market DOWN).
    private val DOWN = listOf(
        "plunge", "crash", "tumble", "sink", "fall", "drop", "slump", "slide", "selloff", "sell-off",
        "misses", "missed", "earnings miss", "loss", "layoff", "recession", "bankruptcy", "downgrade",
        "warning", "cuts jobs", "plummet", "recall", "probe", "lawsuit", "scandal", "glut", "sinks",
        "falls", "drops", "plunges", "tumbles", " sued", "default",
    )

    // Safe-haven / crisis words that lift havens (gold, defense) even when the news is "bad".
    private val CRISIS = listOf(
        "war", "attack", "conflict", "invasion", "invades", "crisis", "tension", "missile", "sanction",
        "airstrike", "terror", "coup", "uncertainty",
    )

    private data class Def(
        val market: String,
        val why: String,
        val triggers: List<String>,
        val haven: Boolean = false,            // lifted by CRISIS words
        val extraUp: List<String> = emptyList(),
        val extraDown: List<String> = emptyList(),
        // The causal read for this market when the story pushes it up / down / is unclear — the
        // "what reality is doing to this market" line. Falls back to a generic phrasing if blank.
        val upWhy: String = "",
        val downWhy: String = "",
    )

    // Ordered most-specific → most-general; the first few matches are shown, so specific sectors win.
    private val DEFS = listOf(
        Def("Oil", "energy prices", listOf("oil", "crude", "opec", "barrel", "gasoline", "gas price", "pipeline", "refinery"),
            extraUp = listOf("production cut", "supply cut", "opec cut", "embargo", "sanction", "war", "hurricane"),
            extraDown = listOf("glut", "oversupply", "demand slump"),
            upWhy = "Supply tightens — less crude on the market means a higher barrel.",
            downWhy = "Supply glut — more crude than buyers, so the barrel sags."),
        Def("OJ Futures", "frozen-concentrate crop", listOf("orange juice", "orange crop", "citrus", "frozen concentrate", " oj ", "florida orange"),
            extraUp = listOf("frost", "freeze", "cold snap", "crop damage", "hurricane", "shortage", "drought"),
            extraDown = listOf("bumper crop", "record harvest"),
            upWhy = "The crop's threatened — a bad freeze report is exactly what spikes OJ (ask the Dukes).",
            downWhy = "A bumper harvest floods the market — concentrate gets cheap."),
        Def("Gold", "safe-haven demand", listOf("gold", "bullion", "precious metal", "safe haven", "safe-haven"), haven = true,
            upWhy = "Flight to safety — when the world looks scary, money hides in gold.",
            downWhy = "Risk-on — calm markets don't need a safe haven."),
        Def("Defense", "defense spending", listOf("defense", "defence", "military", "weapons", "arms deal", "pentagon", "nato", "warplane"), haven = true,
            upWhy = "Conflict means orders — defense contractors sell more hardware.",
            downWhy = "Peace dividend — de-escalation trims the order book."),
        Def("Bitcoin", "crypto", listOf("bitcoin", "crypto", "ethereum", "blockchain", "btc", "stablecoin"),
            upWhy = "Risk appetite + inflows lift the whole crypto complex.",
            downWhy = "Risk-off / a crackdown drains crypto fast."),
        Def("Chips", "chip demand", listOf("chip", "semiconductor", "nvidia", "tsmc", "foundry", "gpu"),
            upWhy = "More demand for silicon carries the whole semi supply chain.",
            downWhy = "Softer chip demand / a glut weighs on the semis."),
        Def("Tech", "tech sector", listOf("tech", "technology", "artificial intelligence", " ai ", "software", "cloud", "apple", "iphone", "google", "microsoft", "meta", "silicon valley"),
            upWhy = "Growth story — the market pays up for tech that's winning.",
            downWhy = "Rich valuations sell off first when tech disappoints."),
        Def("Banks", "rates & lending", listOf("bank", "banking", "lender", "federal reserve", "the fed", "interest rate", "rate hike", "rate cut", "credit crunch"),
            upWhy = "Fatter net-interest margins / easing stress help the lenders.",
            downWhy = "Credit stress / squeezed margins hit the banks."),
        Def("Airlines", "travel demand", listOf("airline", "flights", "boeing", "airbus", "air travel", "aviation"),
            upWhy = "Fuller planes + cheaper fuel is pure profit for carriers.",
            downWhy = "Pricier fuel or softer travel clips the airlines."),
        Def("Housing", "housing market", listOf("housing", "mortgage", "real estate", "home sales", "homebuilder", "construction"),
            upWhy = "Lower rates / more sales pull homebuilders up.",
            downWhy = "Higher rates freeze buyers — housing cools."),
        Def("Pharma", "healthcare sector", listOf("pharma", "drug", "vaccine", "fda", "biotech", "clinical trial"),
            upWhy = "An approval or a hit trial is a fresh revenue stream.",
            downWhy = "A failed trial / recall / probe punishes pharma."),
        Def("Autos/EV", "auto sector", listOf("automaker", "electric vehicle", " ev ", "tesla", "ford", "carmaker", "auto sales"),
            upWhy = "Strong deliveries / demand drive the carmakers.",
            downWhy = "A recall or a price war dents the automakers."),
        Def("Retail", "consumer spending", listOf("retail", "consumer spending", "walmart", "shoppers", "holiday sales", "retail sales"),
            upWhy = "Shoppers spending freely lifts the retailers.",
            downWhy = "A pinched consumer means thinner retail tills."),
        Def("Food", "food & crops", listOf("wheat", "corn", "soybean", "harvest", "crop", "food price", "drought hits"),
            upWhy = "A short harvest bids up grain and food prices.",
            downWhy = "A big harvest / falling prices weigh on ag."),
        Def("Stocks", "broad market", listOf("stock market", "s&p", "nasdaq", "dow jones", "wall street", "gdp", "jobs report", "unemployment", "recession", "inflation"),
            upWhy = "Risk-on — the broad tape catches a bid.",
            downWhy = "Risk-off — the whole tape leaks lower."),
    )

    // A news category → the broad market it implicitly touches, so even a plain sector headline gets a
    // read. Only applied when the story didn't already match that market by keyword, and still capped.
    private val CATEGORY_MARKET = mapOf(
        "Business" to "Stocks", "Tech" to "Tech", "Science" to "Pharma", "Health" to "Pharma",
    )

    /** The markets a story touches, with likely direction + a causal reason. Capped to the top [max]. */
    fun linksFor(title: String, summary: String = "", category: String = "", max: Int = 3): List<MarketLink> {
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
        // Strength: a clearly-stated direction on a keyword-matched market is the strongest read; a
        // category-only association or an unclear direction is weaker.
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
        if (down.isNotEmpty()) parts += "${if (up.isEmpty()) "weighs on" else "weighs on"} ${down.joinToString(", ")}"
        if (parts.isEmpty()) return "In play: ${links.joinToString(", ") { it.market }}."
        return "Reality check: " + parts.joinToString("; ") + "."
    }

    /** The single sharpest causal line for the strip header — the top-strength link's rationale. */
    fun headline(links: List<MarketLink>): String =
        links.maxByOrNull { it.strength }?.rationale ?: ""
}
