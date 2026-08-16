// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/NewsMarketLink.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

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
            upWhy = "Here's the real story: the taps just got tighter, so every barrel left on the market is worth more.",
            downWhy = "Truth is there's too much crude sloshing around right now — sellers are chasing buyers, and the price knows it."),
        Def("Natural Gas", "heating & power fuel", listOf("natural gas", "lng", "gas storage", "gas heating", "henry hub"),
            upWhy = "Cold snap or a supply pinch — either way, everyone needs the same fuel at once and gas gets pricey fast.",
            downWhy = "Mild weather and full storage tanks — nobody's fighting over gas right now, so it's cheap."),
        Def("Solar & Clean", "renewables", listOf("solar", "renewable", "clean energy", "wind farm", "photovoltaic", "green energy", "ev charging"),
            upWhy = "Money's flowing toward the clean-energy story again — subsidies and momentum do the heavy lifting.",
            downWhy = "The subsidy well's drying up and cheap fossil fuel is stealing the spotlight — clean energy's having a rough week."),
        Def("Uranium & Nuclear", "nuclear power", listOf("uranium", "nuclear plant", "reactor", "nuclear power"),
            upWhy = "Nuclear's back in fashion — a build-out means real, long-term demand for uranium.",
            downWhy = "A plant setback spooks the whole nuclear trade, uranium included."),
        // ---- Precious & industrial metals / materials ----
        Def("Gold", "safe-haven demand", listOf("gold", "bullion", "precious metal", "safe haven", "safe-haven"), haven = true,
            upWhy = "When the world looks this scary, everyone runs to the same place — and it's always gold.",
            downWhy = "Nothing to hide from right now — calm markets don't bother stashing cash in gold."),
        Def("Silver", "precious/industrial metal", listOf("silver"),
            upWhy = "Silver's doing double duty here — a bit of haven fear, a bit of real factory demand, both pulling it up.",
            downWhy = "No fear, no factory demand — silver's just drifting without a reason to move."),
        Def("Copper", "the economy's metal", listOf("copper"),
            upWhy = "Copper's the tell — when builders and factories are actually busy, this is the metal that shows it first.",
            downWhy = "Copper's the tell here too — construction and industry going quiet shows up in this price before anywhere else."),
        Def("Steel & Iron", "heavy industry", listOf("steel", "iron ore", "blast furnace"),
            upWhy = "Building booms and import tariffs both funnel business straight to domestic steel.",
            downWhy = "A construction slowdown plus cheap imports flooding in — steel's getting squeezed from both sides."),
        Def("Lithium & Battery", "battery metals", listOf("lithium", "battery metal", "cobalt", "battery plant"),
            upWhy = "Every EV rolling off the line needs a battery, and every battery needs this — demand's the whole story.",
            downWhy = "EV sales cooling off while supply keeps piling up — that's a rough combination for lithium."),
        Def("Mining & Materials", "raw materials", listOf("mining", "miner", "ore", "smelter", "quarry", "raw materials"),
            upWhy = "The world's building things again, and that means digging things up — the miners feel it first.",
            downWhy = "Demand's drying up across the board, and the miners are always first to feel a cold shoulder."),
        // ---- Tech complex ----
        Def("Chips", "semiconductors", listOf("chip", "semiconductor", "nvidia", "tsmc", "foundry", "gpu", "wafer"),
            upWhy = "Everything runs on silicon these days — more demand here ripples through the entire supply chain.",
            downWhy = "Chip demand's gone soft and there's a glut sitting on shelves — not a great look for the semis."),
        Def("AI", "artificial intelligence", listOf("artificial intelligence", " ai ", "ai model", "chatbot", "machine learning", "large language model", "openai", "generative ai"),
            upWhy = "Another leap forward, another wave of spending — the AI trade feeds on exactly this kind of headline.",
            downWhy = "Doubts are creeping in and the spending's slowing — the AI trade doesn't handle skepticism well."),
        Def("Cybersecurity", "digital defence", listOf("cybersecurity", "ransomware", "data breach", "cyberattack", "hacking", "malware", "cyber"),
            upWhy = "Nothing sells cyber defence like a fresh breach making headlines — fear opens the checkbook.",
            downWhy = "A quiet stretch with no scares means budgets for cyber defence quietly get trimmed."),
        Def("Cloud & Software", "enterprise software", listOf("cloud computing", "saas", "software", "data center", "data centre"),
            upWhy = "Companies are still throwing budget at cloud and software — that's the whole sector's engine.",
            downWhy = "IT budgets getting the axe hits software subscriptions before almost anything else."),
        Def("Social Media", "social platforms", listOf("social media", "facebook", "instagram", "tiktok", "twitter", "x platform", "snapchat", "reddit"),
            upWhy = "Eyeballs and ad dollars are both flowing — that's the whole business model working as intended.",
            downWhy = "Advertisers pulling back, or a ban threat looming — either one starves these platforms of oxygen."),
        Def("Telecom", "carriers & 5G", listOf("telecom", "5g", "broadband", "wireless carrier", "at&t", "verizon", "t-mobile"),
            upWhy = "Subscribers sticking around and networks holding up — steady, unglamorous, and exactly what carriers want.",
            downWhy = "Price wars and customers jumping ship — the carriers are bleeding each other dry."),
        Def("Tech", "tech sector", listOf("tech", "technology", "apple", "iphone", "google", "microsoft", "meta", "silicon valley", "gadget", "app store"),
            upWhy = "This is a growth story, and the market pays a premium for anything that's actually winning.",
            downWhy = "Tech trades on hype more than most — and when it disappoints, the pricey names get sold first."),
        // ---- Financials ----
        Def("Regional Banks", "community lenders", listOf("regional bank", "community bank", "local bank", "credit union", "small bank"),
            upWhy = "Deposits staying put and rates working in their favor — the small guys are catching a break.",
            downWhy = "Deposits fleeing and bad loans piling up hit the small regional banks hardest — they don't have the cushion the giants do."),
        Def("Banks", "rates & lending", listOf("bank", "banking", "lender", "federal reserve", "the fed", "interest rate", "rate hike", "rate cut", "credit crunch", "wall street"),
            upWhy = "Fatter margins on every loan they write, less stress on the balance sheet — bankers are smiling.",
            downWhy = "Squeezed margins and shaky credit — the lenders are the ones who eat it when the system gets stressed."),
        Def("Insurance", "insurers", listOf("insurance", "insurer", "reinsurance", "premium", "claims payout", "catastrophe loss"),
            upWhy = "Premiums up, claims quiet — that's the whole insurance business working exactly the way they like it.",
            downWhy = "A big disaster means a big payout, and insurers are the ones writing the check."),
        Def("Bonds & Rates", "government debt", listOf("treasury", "treasuries", "bond yield", "10-year", "government debt", "national debt", "deficit", "credit rating", "yield"),
            upWhy = "Rates dropping and nervous money hunting for safety both push bond prices higher.",
            downWhy = "Yields climbing and inflation fears biting both drag bond prices lower — the math is unforgiving."),
        Def("US Dollar", "the greenback", listOf("dollar", "u.s. dollar", "currency", "forex", "exchange rate", "greenback", "devaluation"),
            upWhy = "When the world gets nervous, or US rates run hot, the dollar's the default place to park cash.",
            downWhy = "Rate cuts and a risk-on mood both take the wind out of the dollar's sails."),
        // ---- Health ----
        Def("Pharma", "drugmakers", listOf("pharma", "drug", "vaccine", "fda", "medicine", "prescription"),
            upWhy = "A green light from regulators — or a genuine hit drug — is basically a licence to print money for years.",
            downWhy = "A failed trial, a recall, a probe — pharma gets punished hard and fast when the science doesn't pan out."),
        Def("Biotech", "biotech", listOf("biotech", "clinical trial", "gene therapy", "mrna", "biotech startup"),
            upWhy = "Good trial data is basically the entire biotech thesis proving itself out in real time.",
            downWhy = "A trial that fails can wipe out years of promise overnight — that's the brutal reality of biotech."),
        // ---- Industry, transport, real estate ----
        Def("Defense", "defense spending", listOf("defense", "defence", "military", "weapons", "arms deal", "pentagon", "nato", "warplane", "missile"), haven = true,
            upWhy = "Grim as it is, conflict means orders — and defense contractors are the ones filling them.",
            downWhy = "Peace is great for the world and lousy for the order book — de-escalation means fewer contracts."),
        Def("Airlines", "air travel", listOf("airline", "flights", "boeing", "airbus", "air travel", "aviation", "jet fuel"),
            upWhy = "Full planes and cheap fuel — that combination is straight, undiluted profit for the carriers.",
            downWhy = "Fuel costs climbing while travelers stay home — that's the airlines' worst-case squeeze."),
        Def("Shipping & Freight", "supply chain", listOf("shipping", "freight", "container", "port", "supply chain", "logistics", "trucking"),
            upWhy = "More stuff moving around the world at higher rates — the shippers are cashing in on the traffic.",
            downWhy = "A trade slump or a jammed-up port leaves freight sitting idle — no cargo, no cash."),
        Def("Industrials", "factories & machinery", listOf("factory", "manufacturing", "industrial", "machinery", "caterpillar", "assembly line"),
            upWhy = "Factories humming again means orders flowing to the companies that build the machines.",
            downWhy = "A manufacturing slowdown means idle equipment, and idle equipment doesn't sell more machinery."),
        Def("Autos & EV", "carmakers", listOf("automaker", "electric vehicle", " ev ", "tesla", "ford", "carmaker", "auto sales", "car sales", "vehicle recall"),
            upWhy = "Strong deliveries mean the demand story's real, not just marketing — carmakers love proving that.",
            downWhy = "A recall, a strike, a price war — any one of those alone dents an automaker; together they hurt."),
        Def("Housing", "homes & builders", listOf("housing", "mortgage", "real estate", "home sales", "homebuilder", "construction", "house prices"),
            upWhy = "Cheaper mortgages get people off the fence and into contracts — that's a homebuilder's dream scenario.",
            downWhy = "Higher rates freeze buyers in place — nobody's signing a mortgage they can't stomach, and housing cools fast."),
        Def("Real Estate & REITs", "commercial property", listOf("commercial real estate", "office space", "reit", "landlord", "vacancy", "rent prices", "shopping mall"),
            upWhy = "Full buildings and rising rents — that's the whole commercial-property playbook working.",
            downWhy = "Empty offices and falling rents are the landlord's nightmare, and it shows up straight in the REITs."),
        Def("Utilities", "power & water", listOf("utility", "power grid", "electricity price", "water utility", "blackout", "power outage"),
            upWhy = "Steady demand and a rate hike approved by regulators — about as good as it gets for a utility.",
            downWhy = "A grid failure or a rate freeze from regulators both squeeze the utilities right where it hurts."),
        // ---- Consumer & staples ----
        Def("Retail", "consumer spending", listOf("retail", "consumer spending", "walmart", "shoppers", "holiday sales", "retail sales", "store closures", "back to school"),
            upWhy = "People are actually opening their wallets — that's the one thing retailers need most.",
            downWhy = "A pinched consumer means quiet tills, and quiet tills are every retailer's worst headline."),
        Def("E-commerce", "online shopping", listOf("e-commerce", "online shopping", "amazon", "online sales", "same-day delivery", "marketplace seller"),
            upWhy = "Carts are filling up online and deliveries are humming — that's e-commerce firing on all cylinders.",
            downWhy = "When budgets tighten, online carts get abandoned first — e-commerce feels a pullback fast."),
        Def("Consumer Staples", "everyday goods", listOf("grocery", "groceries", "household goods", "detergent", "toothpaste", "packaged food", "supermarket", "cpg", "empty shelves"),
            upWhy = "The one bright spot in a shaky economy — people stop buying almost everything before they stop buying essentials.",
            downWhy = "Even essentials aren't safe when shoppers start trading down to the cheapest option on the shelf."),
        Def("Restaurants & Fast Food", "dining out", listOf("restaurant", "fast food", "dining", "mcdonald", "starbucks", "chipotle", "diner", "menu prices"),
            upWhy = "Tables are full and prices are sticking — the chains are having a genuinely good run.",
            downWhy = "Diners cutting back on eating out is one of the first, quietest signs of a squeezed budget."),
        Def("Travel & Hotels", "tourism", listOf("hotel", "cruise", "resort", "tourism", "vacation", "airbnb", "theme park", "bookings"),
            upWhy = "Bookings are pouring in — rooms and cabins are filling up faster than they can be listed.",
            downWhy = "A travel pullback empties rooms and cabins just as fast as a boom fills them."),
        Def("Alcohol & Beverages", "drinks", listOf("beer", "wine", "spirits", "brewery", "distillery", "soda", "beverage maker"),
            upWhy = "Strong sales across the board — people are still reaching for a drink, recession or not.",
            downWhy = "Weak sales and rising taxes are squeezing margins for the drinks makers."),
        Def("Tobacco & Vaping", "tobacco", listOf("tobacco", "cigarette", "vaping", "vape", "nicotine", "e-cigarette"),
            upWhy = "Tobacco's pricing power is legendary — they can raise prices and demand barely blinks.",
            downWhy = "A ban, a new tax, or a health ruling — any of those hits tobacco right where it's most vulnerable."),
        Def("Cannabis", "cannabis", listOf("cannabis", "marijuana", "dispensary", "legal weed", "cbd"),
            upWhy = "Every new market that opens up is pure upside for an industry still fighting for legitimacy.",
            downWhy = "A regulatory setback stings extra hard for an industry this new and this fragile."),
        Def("Gaming & Casinos", "gambling & games", listOf("casino", "gambling", "sports betting", "video game", "gaming company", "slot machine", "lottery"),
            upWhy = "A hit title or a busy casino floor — either way, players are spending and the house is winning.",
            downWhy = "Quiet floors and a flop release both leave the same mark: nobody's spending."),
        Def("Media & Streaming", "entertainment", listOf("streaming", "netflix", "disney", "box office", "hollywood", "studio", "cable tv", "subscribers"),
            upWhy = "A box-office smash or a subscriber surge — either one is the whole media business model working.",
            downWhy = "Cord-cutters and a flop at the box office both leave the same hole in the revenue sheet."),
        Def("Luxury", "high-end goods", listOf("luxury", "designer brand", "high-end", "handbag", "haute couture", "luxury goods"),
            upWhy = "The wealthy are spending freely again, and luxury lives and dies on exactly that.",
            downWhy = "A pullback among wealthy shoppers — especially in China — is luxury's biggest, most reliable fear."),
        // ---- Agriculture / softs ----
        Def("Coffee", "coffee futures", listOf("coffee", "arabica", "robusta"),
            extraUp = listOf("frost", "drought", "shortage"),
            upWhy = "A bad harvest or a frost hitting the crop right now is exactly what sends your morning cup up in price.",
            downWhy = "A bumper crop means more beans than the world can drink — coffee gets cheap fast."),
        Def("Cocoa & Chocolate", "cocoa", listOf("cocoa", "chocolate", "cacao"),
            extraUp = listOf("shortage", "disease", "drought"),
            upWhy = "A cocoa shortage right now means your candy bar gets pricier down the line — it always trickles down.",
            downWhy = "A good harvest eases the squeeze — cocoa gets breathing room and chocolate makers exhale."),
        Def("OJ Futures", "frozen-concentrate crop", listOf("orange juice", "orange crop", "citrus", "frozen concentrate", " oj ", "florida orange"),
            extraUp = listOf("frost", "freeze", "cold snap", "crop damage", "hurricane", "shortage", "drought"),
            extraDown = listOf("bumper crop", "record harvest"),
            upWhy = "The literal Trading Places setup: a frozen crop report like this is exactly what spikes OJ futures — the Dukes would recognize it instantly.",
            downWhy = "A bumper harvest floods the market and the concentrate gets cheap — no scarcity story here."),
        Def("Grains & Food", "food & crops", listOf("wheat", "corn", "soybean", "harvest", "crop", "food price", "grain", "fertilizer", "drought hits"),
            upWhy = "A short harvest means less grain to go around, and less-to-go-around always shows up as a higher price tag.",
            downWhy = "A big harvest floods the market — more grain than buyers means the price has nowhere to go but down."),
        // ---- Crypto ----
        Def("Bitcoin", "crypto", listOf("bitcoin", "crypto", "ethereum", "blockchain", "btc", "stablecoin", "coinbase"),
            upWhy = "Risk appetite's back and money's flowing in — crypto moves fast and furious on exactly that kind of mood.",
            downWhy = "Risk-off moods or a crackdown headline drain crypto faster than almost anything else out there."),
        // ---- Broad market / macro (most general — last) ----
        Def("Small Caps", "small & local business", listOf("small business", "local business", "main street", "small-cap", "startup", "community business", "mom-and-pop", "small firm"),
            upWhy = "Easier credit and a spending consumer are oxygen for the little guys — Main Street feels it first.",
            downWhy = "When credit tightens, the small firms without a cash cushion feel the squeeze before anyone else does."),
        Def("Stocks", "the broad market", listOf("stock market", "s&p", "nasdaq", "dow jones", "gdp", "jobs report", "unemployment", "recession", "inflation", "economy", "election", "tariff", "regulation", "antitrust", "trade war", "consumer confidence", "fed", "cpi"),
            upWhy = "This is the kind of headline that puts the whole market in a good mood — risk-on, and everything catches a bid.",
            downWhy = "This is the kind of headline that sours the whole room — risk-off, and the entire tape leaks lower together."),
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
            MarketImpact.UP -> def.upWhy.ifBlank { "This is genuinely good news for ${def.market.lowercase()} — worth watching." }
            MarketImpact.DOWN -> def.downWhy.ifBlank { "This one's rough for ${def.market.lowercase()} — keep an eye on it." }
            MarketImpact.MIXED -> "This is the one to watch — ${def.why}, and this story's right in the middle of it."
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

    /** A short, confiding "here's who wins and who loses" caption — the insider read, not a hedge. E.g.
     *  "Here's who's actually moving: Oil, Gold catching a bid — Airlines paying for it." */
    fun summarize(links: List<MarketLink>): String {
        if (links.isEmpty()) return ""
        val up = links.filter { it.impact == MarketImpact.UP }.map { it.market }
        val down = links.filter { it.impact == MarketImpact.DOWN }.map { it.market }
        val parts = mutableListOf<String>()
        if (up.isNotEmpty()) parts += "${up.joinToString(", ")} catching a bid"
        if (down.isNotEmpty()) parts += "${down.joinToString(", ")} paying for it"
        if (parts.isEmpty()) return "Keep both eyes on: ${links.joinToString(", ") { it.market }}."
        return "Here's who's actually moving: " + parts.joinToString(" — ") + "."
    }

    /** The single sharpest causal line for the strip header — the top-strength link's rationale. */
    fun headline(links: List<MarketLink>): String =
        links.maxByOrNull { it.strength }?.rationale ?: ""
}
