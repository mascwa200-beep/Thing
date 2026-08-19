package dev.mascwa.pulse.core.telemetry

/**
 * The pure half of the Theater — the in-app media discovery surface.
 *
 * What lives here is everything about browsing that is a decision rather than a network call: which
 * shelves exist and what they search for, how a resume ledger evolves, how a news headline becomes a
 * search query, and which URLs are videos at all. The Android side supplies the fetches; this
 * decides what to do with them, so CI holds the rules.
 *
 * ⚠️ DELIBERATELY NOT MIRRORED to the desktop. Media playback is phone-only under the tandem rule
 * (sensors, GPS, notifications and media stay on the phone; saying so beats a half-port), so a
 * desktop copy of this file would be a callerless mirror — the exact defect class the mirror
 * machinery exists to prevent.
 *
 * ⚠️ There is NO trending shelf and that is measured, not stylistic: YouTube retired /feed/trending
 * (the URL redirects to the home page), so "what to show before anyone types" is composed here from
 * searches over what the app already knows — its own news headlines and curated subject queries.
 */
object TheaterModel {

    /** A browse shelf: a label on screen and the search that fills it. */
    data class Shelf(val id: String, val label: String, val query: String)

    /**
     * The curated subject shelves, shown when nothing has been typed.
     *
     * Queries, not YouTube category ids — category ids are an API surface this app has no access
     * to, and plain `ytsearch` is the one listing path proven to work. Kept short: every shelf is
     * one network round trip through the embedded interpreter, and the ViewModel loads them
     * sequentially, so each entry here has a real latency cost.
     */
    val CATEGORY_SHELVES: List<Shelf> = listOf(
        Shelf("science", "SCIENCE", "science explained documentary"),
        Shelf("space", "SPACE", "space astronomy documentary"),
        Shelf("engineering", "ENGINEERING", "engineering how it works"),
        Shelf("history", "HISTORY", "history documentary"),
        Shelf("music", "MUSIC", "live music performance"),
    )

    /**
     * The 11-character YouTube id in [url], or "" when the address is not one we can key.
     *
     * ⚠️ Deliberately a TWIN of `lcars_extract.video_id`'s regex, not a JNI call to it: the
     * consumers here filter whole feeds (every social item, every enclosure), and paying an
     * interpreter round trip per feed item is exactly the cost a pure twin avoids. A test asserts
     * both regexes agree on a shared fixture table, so they cannot drift silently.
     */
    fun videoId(url: String): String =
        ID_PATTERN.find(url)?.groupValues?.get(1).orEmpty()

    private val ID_PATTERN = Regex("(?:v=|/shorts/|youtu\\.be/|/embed/|/v/)([A-Za-z0-9_-]{11})")

    /** One remembered viewing — enough to draw a card and resume playback. */
    data class Viewing(
        val id: String,
        val title: String,
        val pageUrl: String,
        val thumbnailUrl: String,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAtMs: Long,
    )

    /** Watched past this fraction counts as finished — it leaves the shelf rather than nagging. */
    const val FINISHED_FRACTION = 0.95

    /** A viewing older than this is stale — nobody resumes a video from last month. */
    const val LEDGER_MAX_AGE_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** How many viewings the shelf keeps. */
    const val LEDGER_MAX = 10

    /**
     * Fold viewing [v] into [ledger]: dedupe by id, newest first, drop finished and stale entries.
     *
     * Finished means past [FINISHED_FRACTION] of a KNOWN duration — an unknown duration (0) can
     * never count as finished, because 0/0 finishing everything would silently empty the shelf for
     * live streams and unresolved durations.
     */
    fun remember(ledger: List<Viewing>, v: Viewing, max: Int = LEDGER_MAX): List<Viewing> {
        val merged = listOf(v) + ledger.filter { it.id != v.id }
        return merged
            .filter { keep ->
                val finished = keep.durationMs > 0 &&
                    keep.positionMs >= keep.durationMs * FINISHED_FRACTION
                !finished && v.updatedAtMs - keep.updatedAtMs <= LEDGER_MAX_AGE_MS
            }
            .sortedByDescending { it.updatedAtMs }
            .take(max)
    }

    /**
     * Turn the day's top news [headlines] into video search queries — the "ON TODAY'S STORIES"
     * shelf, the app's replacement for the trending feed YouTube no longer has.
     *
     * A headline is close to a good query already; what it carries that a query must not is the
     * attribution tail (" - Reuters", " | BBC News") and quote punctuation. Words are capped
     * because a 20-word headline over-constrains a search into returning nothing.
     */
    fun storyQueries(headlines: List<String>, max: Int = 3): List<String> {
        val seen = LinkedHashSet<String>()
        for (h in headlines) {
            val cleaned = h
                .substringBefore(" - ")
                .substringBefore(" | ")
                .replace(QUOTE_NOISE, " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .take(QUERY_MAX_WORDS)
                .joinToString(" ")
                .trim()
            if (cleaned.split(" ").size >= QUERY_MIN_WORDS) seen.add(cleaned)
            if (seen.size >= max) break
        }
        return seen.toList()
    }

    /** Fewer words than this is a fragment, not a story — searching it returns noise. */
    const val QUERY_MIN_WORDS = 3

    /** More words than this over-constrains the search toward zero results. */
    const val QUERY_MAX_WORDS = 8

    private val QUOTE_NOISE = Regex("[\"“”‘’']")
}
