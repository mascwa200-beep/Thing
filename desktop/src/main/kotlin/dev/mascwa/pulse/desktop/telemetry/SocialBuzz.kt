// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SocialBuzz.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
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
 * mention-count (Pulse has no such API, and never sends the story to any server to ask), but a
 * same-vocabulary topic-overlap signal: every Lemmy / Hacker News / Mastodon title already visible in the
 * app's SOCIAL tabs is run through the exact same [NewsInsights.topics] extraction already used for the
 * article's own "#tag" chips, so a buzz match visibly correlates with what the reader already sees — no new
 * vocabulary, no black box. Mastodon's trending hashtag names are matched directly against the article's own
 * title/summary text instead (a hashtag like "Ukraine" or "Bitcoin" is a literal word, not a category label
 * like "Politics"/"Markets", so it wouldn't itself survive [NewsInsights.topics] as an input the way a
 * headline does). Pure, deterministic, no network — the caller is expected to have already fetched the
 * social feeds ONCE per screen session (see `NewsViewModel`), not per article.
 */
object SocialBuzz {

    /** [articleTags] is the article's own [NewsInsights.topics] output (already computed for its "#tag"
     *  chips — pass it in rather than re-deriving it here). [socialTitles] are raw Lemmy/HN/Mastodon-status
     *  titles already fetched this session. [trendTagNames] are raw Mastodon trending hashtag names. */
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
