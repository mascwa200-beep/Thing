package dev.mascwa.pulse.data.news

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** DTOs for the optional NewsAPI.org integration (only used if a key is set). */
@Serializable
data class NewsApiResponse(
    val status: String = "",
    val totalResults: Int = 0,
    val articles: List<NewsApiArticle> = emptyList(),
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class NewsApiArticle(
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val urlToImage: String? = null,
    val publishedAt: String? = null,
    val source: NewsApiSource? = null,
)

@Serializable
data class NewsApiSource(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String? = null,
)

/** Hacker News (Firebase) item DTO. */
@Serializable
data class HackerNewsItem(
    val id: Long = 0,
    val title: String? = null,
    val url: String? = null,
    val by: String? = null,
    val time: Long = 0,            // unix seconds
    val score: Int = 0,
    val descendants: Int = 0,
    val type: String? = null,
)
