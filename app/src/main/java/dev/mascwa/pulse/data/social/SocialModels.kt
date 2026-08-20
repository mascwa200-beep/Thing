package dev.mascwa.pulse.data.social

import kotlinx.serialization.Serializable

@Serializable
data class SocialItem(
    val title: String,
    val url: String,
    val source: String,       // community / account / "Hacker News"
    val meta: String,         // e.g. "▲ 412 · 88 comments"
    val publishedEpochMs: Long = 0L,
    val thumbnail: String? = null,
    /**
     * The post's own text, where the post IS text rather than a link.
     *
     * ⚠️ Hacker News publishes `text` on every self-post and the app discarded it, so an Ask HN
     * thread — which is nothing but its text — rendered as a title and a vote count and nothing
     * else. Null for link posts, which genuinely have no body.
     */
    val body: String? = null,
)

@Serializable
data class TrendTag(val name: String, val url: String, val uses: Int)

@Serializable
data class SocialFeed(val items: List<SocialItem>)

@Serializable
data class MastodonTrends(val tags: List<TrendTag>, val statuses: List<SocialItem>)
