package dev.mascwa.pulse.desktop.telemetry

/**
 * Lightweight "at-a-glance" infographics for a news headline — pure, deterministic, offline visual aids
 * that sit beside the MARKET REACTION strip: the story's TONE (a mood read), auto-extracted TOPIC/REGION
 * tags, and a MARKET-IMPACT level. All deterministic keyword logic over the headline + summary; no network,
 * no model. A heuristic read for glanceability, not a claim of fact.
 *
 * Ported byte-for-byte from the Android app's `core:telemetry/NewsInsights.kt` (zero android.* imports
 * there, so this is a straight copy with the package renamed) — the desktop News vertical's first slice
 * (`Desktop Phase B slice 1`) reuses the whole "insider-knowledge" signal stack this way.
 */

/** The overall mood a headline reads as. */
enum class Tone(val label: String) { UPBEAT("Upbeat"), MIXED("Mixed"), GRIM("Grim"), TENSE("Tense") }

/** How market-relevant a story is, from its market links. */
enum class ImpactLevel(val label: String) { NONE("—"), LOW("Low"), MEDIUM("Medium"), HIGH("High") }

/** Everything the at-a-glance strip needs for one article. */
data class NewsInsight(
    val tone: Tone,
    /** -1 (grim) … +1 (upbeat), for a mood bar. */
    val toneScore: Float,
    /** Topic + region tags, most-relevant first, capped. */
    val topics: List<String>,
    val marketImpact: ImpactLevel,
)

/** The literal keyword-hit counts behind a [tone] read, so a UI can show its work instead of an opaque
 *  single number — "4 upbeat terms, 1 tense" is more informative than a bare score. */
data class ToneBreakdown(
    val tone: Tone,
    val score: Float,
    val positive: Int,
    val negative: Int,
    val tense: Int,
)

object NewsInsights {

    private val POSITIVE = listOf(
        "surge", "soar", "jump", "rally", "gain", "rise", "record", "beats", "win", "wins", "boost",
        "breakthrough", "approval", "approved", "recover", "boom", "hope", "deal", "growth", "success",
        "profit", "rescue", "agreement", "upgrade", "milestone", "celebrat", "improve", "strong",
    )
    private val NEGATIVE = listOf(
        "plunge", "crash", "tumble", "fall", "drop", "slump", "loss", "layoff", "recession", "bankrupt",
        "warning", "cut", "recall", "probe", "lawsuit", "scandal", "fail", "fear", "worry", "crisis",
        "threat", "ban", "strike", "shortage", "default", "collapse", "outbreak", "victim", "dead",
        "death", "injur", "damage", "downgrade", "protest", "fine", "delay",
    )
    private val TENSE = listOf(
        "war", "attack", "conflict", "invasion", "missile", "terror", "shooting", "violence", "clash",
        "hostage", "coup", "airstrike", "nuclear", "escalat", "standoff", "riot",
    )

    // topic tag -> keywords. Order = priority when capping.
    private val TOPICS: List<Pair<String, List<String>>> = listOf(
        "Conflict" to listOf("war", "attack", "military", "missile", "invasion", "troops", "airstrike", "ceasefire"),
        "Politics" to listOf("election", "president", "senate", "congress", "parliament", "government", "policy", "vote", "campaign", "minister", "law", "tariff"),
        "Economy" to listOf("economy", "inflation", "gdp", "jobs", "unemployment", "recession", "interest rate", "fed", "growth", "trade"),
        "Markets" to listOf("stock", "market", "shares", "nasdaq", "dow", "s&p", "bond", "crypto", "bitcoin", "earnings"),
        "Tech" to listOf("tech", "ai", "artificial intelligence", "app", "software", "chip", "robot", "gadget", "startup", "iphone"),
        "Science" to listOf("science", "research", "study", "discovery", "physics", "genome"),
        "Space" to listOf("space", "nasa", "rocket", "satellite", "mars", "moon", "asteroid", "spacex"),
        "Climate" to listOf("climate", "carbon", "emissions", "warming", "wildfire", "flood", "hurricane", "drought", "heatwave"),
        "Health" to listOf("health", "virus", "vaccine", "disease", "hospital", "outbreak", "cancer", "medical"),
        "Crime" to listOf("police", "arrest", "murder", "theft", "fraud", "court", "trial", "prison"),
        "Disaster" to listOf("earthquake", "tsunami", "explosion", "collapse", "crash", "disaster", "evacuat"),
        "Business" to listOf("company", "ceo", "merger", "acquisition", "layoff", "factory", "profit", "revenue", "startup"),
        "Energy" to listOf("oil", "gas", "energy", "power", "electric", "nuclear", "solar", "renewable"),
        "Sports" to listOf("game", "match", "championship", "league", "tournament", "olympic", "world cup", "player"),
        "Entertainment" to listOf("film", "movie", "music", "album", "celebrity", "hollywood", "box office", "series"),
    )

    // region tag -> keywords.
    private val REGIONS: List<Pair<String, List<String>>> = listOf(
        "Ukraine" to listOf("ukraine", "kyiv", "zelensky"),
        "Russia" to listOf("russia", "moscow", "putin", "kremlin"),
        "China" to listOf("china", "beijing", "chinese", "xi jinping"),
        "Middle East" to listOf("israel", "gaza", "iran", "saudi", "syria", "lebanon", "yemen", "middle east"),
        "Europe" to listOf("europe", "eu ", "brussels", "germany", "france", "italy", "spain"),
        "UK" to listOf("britain", "uk ", "london", "england", "scotland"),
        "India" to listOf("india", "delhi", "modi"),
        "US" to listOf("u.s.", "united states", "america", "washington", "biden", "trump", "white house"),
        "Africa" to listOf("africa", "nigeria", "kenya", "ethiopia", "sudan"),
    )

    private fun List<String>.hits(t: String): Int = count { it in t }

    /** The full signal breakdown behind the mood read — the actual positive/negative/tense keyword hit
     *  counts plus the derived score and label, so a UI can render WHY the tone landed where it did. */
    fun toneBreakdown(title: String, summary: String = ""): ToneBreakdown {
        val t = " ${(title + " " + summary).lowercase()} "
        val pos = POSITIVE.hits(t)
        val neg = NEGATIVE.hits(t)
        val tense = TENSE.hits(t)
        val total = pos + neg + tense
        val score = if (total == 0) 0f else (pos - neg - tense).toFloat() / total
        val label = when {
            tense >= 1 && tense >= pos -> Tone.TENSE
            score > 0.25f -> Tone.UPBEAT
            score < -0.25f -> Tone.GRIM
            else -> Tone.MIXED
        }
        return ToneBreakdown(label, score.coerceIn(-1f, 1f), pos, neg, tense)
    }

    /** The mood read: a score in -1..1 plus a [Tone] label. */
    fun tone(title: String, summary: String = ""): Pair<Tone, Float> {
        val b = toneBreakdown(title, summary)
        return b.tone to b.score
    }

    /** Auto topic + region tags for the story (topics first), capped at [max]. */
    fun topics(title: String, summary: String = "", max: Int = 4): List<String> {
        val t = " ${(title + " " + summary).lowercase()} "
        val topicTags = TOPICS.filter { (_, kws) -> kws.any { it in t } }.map { it.first }
        val regionTags = REGIONS.filter { (_, kws) -> kws.any { it in t } }.map { it.first }
        return (topicTags + regionTags).distinct().take(max)
    }

    /** How many OTHER stories (by their own pre-computed topic/region tags, via [topics]) share at least
     *  one tag with [tags] — the "how much of the crowd is on this right now" signal. Pure: the caller
     *  supplies every candidate's tags so this module stays free of any article/UI type dependency. */
    fun clusterSize(tags: List<String>, othersTags: List<List<String>>): Int {
        if (tags.isEmpty()) return 0
        val tagSet = tags.toSet()
        return othersTags.count { other -> other.any { it in tagSet } }
    }

    /** How market-relevant the story is, from its [MarketLink]s. */
    fun marketImpact(links: List<MarketLink>): ImpactLevel {
        if (links.isEmpty()) return ImpactLevel.NONE
        val strong = links.count { it.strength >= 3 }
        return when {
            strong >= 2 || links.size >= 3 -> ImpactLevel.HIGH
            strong >= 1 -> ImpactLevel.MEDIUM
            else -> ImpactLevel.LOW
        }
    }

    /** The full at-a-glance read for one article. */
    fun analyze(title: String, summary: String = "", links: List<MarketLink> = emptyList()): NewsInsight {
        val (toneLabel, score) = tone(title, summary)
        return NewsInsight(toneLabel, score, topics(title, summary), marketImpact(links))
    }
}
