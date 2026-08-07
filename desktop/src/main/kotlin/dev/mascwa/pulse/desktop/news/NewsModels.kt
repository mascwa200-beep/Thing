package dev.mascwa.pulse.desktop.news

import kotlinx.serialization.Serializable

/**
 * News categories — a port of the Android app's `data/news/NewsModels.kt`. Each maps to either a Google
 * News RSS *topic* or a search query (keyless). The Android version also carries an optional NewsAPI
 * category per entry and a TRENDING velocity-query category; desktop v1 is Google-News-only (no NewsAPI key
 * path, no custom feeds, no social tabs yet — see the Desktop Phase B plan for what's deferred), so only
 * the fields/categories that path actually needs are ported.
 */
enum class NewsCategory(
    val title: String,
    val googleTopic: String?, // null => use [searchQuery]
    val searchQuery: String?,
) {
    TOP("Top Stories", null, null),
    WORLD("World", "WORLD", null),
    POLITICS("Politics", null, "politics OR election OR government OR parliament"),
    BUSINESS("Business", "BUSINESS", null),
    TECH("Tech", "TECHNOLOGY", null),
    POPCULTURE("Pop Culture", "ENTERTAINMENT", null),
    TRENDING(
        "Trending", null,
        "breaking OR trending OR viral OR \"goes viral\" OR \"this just in\" OR \"developing\" OR \"happening now\"",
    ),
    SCIENCE("Science", "SCIENCE", null),
    HEALTH("Health", "HEALTH", null),
    SPORTS("Sports", "SPORTS", null),
}

@Serializable
data class Article(
    val title: String,
    val url: String,
    val summary: String,
    val source: String,
    val publishedEpochMs: Long,
    val imageUrl: String? = null,
    val category: String,
)

@Serializable
data class ArticleList(val articles: List<Article>)
