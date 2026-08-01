package dev.mascwa.pulse.core.telemetry

/** The direction a story likely pushes a linked market: up (good), down (bad), or unclear. */
enum class MarketImpact { UP, DOWN, MIXED }

/** A market a news story touches, the direction it likely pushes it, and a one-line reason. */
data class MarketLink(val market: String, val impact: MarketImpact, val why: String)

/**
 * Maps a news headline to the market(s) it's associated with / would move, and the likely direction —
 * so each article can show, beneath its summary, "who this touches and which way." Pure logic (CI-gated):
 * keyword sector-matching for the association, plus a move/event lexicon for the direction (UP/DOWN when the
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
    )

    // Ordered most-specific → most-general; the first few matches are shown, so specific sectors win.
    private val DEFS = listOf(
        Def("Oil", "energy prices", listOf("oil", "crude", "opec", "barrel", "gasoline", "gas price", "pipeline", "refinery"),
            extraUp = listOf("production cut", "supply cut", "opec cut", "embargo", "sanction", "war", "hurricane"),
            extraDown = listOf("glut", "oversupply", "demand slump")),
        Def("Gold", "safe-haven demand", listOf("gold", "bullion", "precious metal", "safe haven", "safe-haven"), haven = true),
        Def("Defense", "defense spending", listOf("defense", "defence", "military", "weapons", "arms deal", "pentagon", "nato", "warplane"), haven = true),
        Def("Bitcoin", "crypto", listOf("bitcoin", "crypto", "ethereum", "blockchain", "btc", "stablecoin")),
        Def("Chips", "chip demand", listOf("chip", "semiconductor", "nvidia", "tsmc", "foundry", "gpu")),
        Def("Tech", "tech sector", listOf("tech", "technology", "artificial intelligence", " ai ", "software", "cloud", "apple", "iphone", "google", "microsoft", "meta", "silicon valley")),
        Def("Banks", "rates & lending", listOf("bank", "banking", "lender", "federal reserve", "the fed", "interest rate", "rate hike", "rate cut", "credit crunch")),
        Def("Airlines", "travel demand", listOf("airline", "flights", "boeing", "airbus", "air travel", "aviation")),
        Def("Housing", "housing market", listOf("housing", "mortgage", "real estate", "home sales", "homebuilder", "construction")),
        Def("Pharma", "healthcare sector", listOf("pharma", "drug", "vaccine", "fda", "biotech", "clinical trial")),
        Def("Autos/EV", "auto sector", listOf("automaker", "electric vehicle", " ev ", "tesla", "ford", "carmaker", "auto sales")),
        Def("Retail", "consumer spending", listOf("retail", "consumer spending", "walmart", "shoppers", "holiday sales", "retail sales")),
        Def("Food", "food & crops", listOf("wheat", "corn", "soybean", "harvest", "crop", "food price", "drought hits")),
        Def("Stocks", "broad market", listOf("stock market", "s&p", "nasdaq", "dow jones", "wall street", "gdp", "jobs report", "unemployment", "recession", "inflation")),
    )

    /** The markets a story touches, with likely direction + reason. Capped to the top [max] for a compact strip. */
    fun linksFor(title: String, summary: String = "", category: String = "", max: Int = 3): List<MarketLink> {
        val t = " ${(title + " " + summary).lowercase()} "
        val hits = DEFS.filter { def -> def.triggers.any { it in t } }
        if (hits.isEmpty()) return emptyList()
        return hits.take(max).map { def -> MarketLink(def.market, impactFor(t, def), def.why) }
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

    /** A short "why the markets moved" caption for a set of links, e.g. "Could lift Oil, Gold; weigh on Airlines." */
    fun summarize(links: List<MarketLink>): String {
        if (links.isEmpty()) return ""
        val up = links.filter { it.impact == MarketImpact.UP }.map { it.market }
        val down = links.filter { it.impact == MarketImpact.DOWN }.map { it.market }
        val parts = mutableListOf<String>()
        if (up.isNotEmpty()) parts += "Could lift ${up.joinToString(", ")}"
        if (down.isNotEmpty()) parts += "${if (up.isEmpty()) "Could weigh on" else "weigh on"} ${down.joinToString(", ")}"
        if (parts.isEmpty()) return "Touches ${links.joinToString(", ") { it.market }}"
        return parts.joinToString("; ") + "."
    }
}
