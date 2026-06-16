package dev.mascwa.pulse.data.news

import kotlinx.serialization.Serializable

/**
 * News categories. Each maps to either a Google News RSS *topic* or a search
 * query (keyless), and — when the user supplies one — a NewsAPI category.
 */
enum class NewsCategory(
    val title: String,
    val googleTopic: String?,        // null => use [searchQuery]
    val searchQuery: String?,
    val newsApiCategory: String?,    // null => not directly supported by NewsAPI top-headlines
) {
    TOP("Top Stories", null, null, "general"),
    WORLD("World", "WORLD", null, null),
    POLITICS("Politics", null, "politics OR election OR government OR parliament", null),
    BUSINESS("Business", "BUSINESS", null, "business"),
    TECH("Tech", "TECHNOLOGY", null, "technology"),
    POPCULTURE("Pop Culture", "ENTERTAINMENT", null, "entertainment"),
    SCIENCE("Science", "SCIENCE", null, "science"),
    HEALTH("Health", "HEALTH", null, "health"),
    SPORTS("Sports", "SPORTS", null, "sports"),
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
