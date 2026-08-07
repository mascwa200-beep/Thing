package dev.mascwa.pulse.desktop.telemetry

/** How much social-platform chatter a story's own vocabulary overlaps with. */
enum class BuzzLevel(val label: String) {
    NONE("Quiet online"),
    LOW("A little chatter"),
    MODERATE("Some chatter"),
    HIGH("Trending"),
    VIRAL("Viral"),
}

/**
 * A local, offline read of how much social-platform chatter overlaps with a news story — NOT a live
 * mention-count, but a same-vocabulary topic-overlap signal: every social-platform title already visible
 * this session is run through the exact same [NewsInsights.topics] extraction already used for the
 * article's own "#tag" chips, so a buzz match visibly correlates with what the reader already sees — no new
 * vocabulary, no black box. Pure, deterministic, no network — the caller is expected to have already
 * fetched the social feeds ONCE per screen session, not per article.
 *
 * Ported byte-for-byte from the Android app's `core:telemetry/SocialBuzz.kt` (zero android.* imports there
 * — a straight copy with the package renamed). Desktop v1's News vertical doesn't have social tabs wired
 * yet (see Desktop Phase B plan notes), so [socialTitles]/[trendTagNames] will simply be empty for now —
 * ported now anyway since it's zero-cost and the whole insider-knowledge stack travels together.
 */
object SocialBuzz {

    /** [articleTags] is the article's own [NewsInsights.topics] output (already computed for its "#tag"
     *  chips — pass it in rather than re-deriving it here). [socialTitles] are raw social-post titles
     *  already fetched this session. [trendTagNames] are raw trending hashtag names. */
    fun score(
        articleTags: List<String>,
        socialTitles: List<String>,
        articleTitle: String = "",
        articleSummary: String = "",
        trendTagNames: List<String> = emptyList(),
    ): BuzzLevel {
        val tagSet = articleTags.map { it.lowercase() }.toSet()
        val itemMatches = if (tagSet.isEmpty()) {
            0
        } else {
            socialTitles.count { title -> NewsInsights.topics(title).any { it.lowercase() in tagSet } }
        }
        // Whole-word match (padded on both sides, mirroring NewsInsights/NewsMarketLink's own convention) —
        // a bare substring check would let a short tag like "ai" false-positive inside "said"/"main"/etc.
        val t = " ${(articleTitle + " " + articleSummary).lowercase()} "
        val trendMatches = trendTagNames.count { name -> name.isNotBlank() && t.contains(" ${name.lowercase()} ") }
        return level(itemMatches + trendMatches)
    }

    private fun level(total: Int): BuzzLevel = when {
        total >= 5 -> BuzzLevel.VIRAL
        total >= 3 -> BuzzLevel.HIGH
        total >= 2 -> BuzzLevel.MODERATE
        total >= 1 -> BuzzLevel.LOW
        else -> BuzzLevel.NONE
    }
}
