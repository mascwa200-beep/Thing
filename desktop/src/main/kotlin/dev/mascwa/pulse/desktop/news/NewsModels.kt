package dev.mascwa.pulse.desktop.news

import kotlinx.serialization.Serializable

/**
 * News categories — a port of the Android app's `data/news/NewsModels.kt`. Each maps to either a Google
 * News RSS *topic* or a search query (keyless). The Android version also carries an optional NewsAPI
 * category per entry; the desktop is Google-News-only (no NewsAPI key path, no custom feeds), so only the
 * fields that path actually needs are ported.
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

/** A discussion site carried as its own News tab, exactly as the phone carries it. */
enum class SocialSource(val title: String) {
    LEMMY("Lemmy"),
    HN("Hacker News"),
    MASTODON("Mastodon"),
}

/**
 * One tab in the News rail: either a Google News category or a discussion site.
 *
 * The phone folded Lemmy, Hacker News and Mastodon into News as per-source tabs rather than keeping a
 * separate Social screen, because a post with a title, a source and a time is an article as far as the
 * list is concerned. The desktop follows it — same arrangement, same reader, no second screen.
 */
data class NewsTab(
    /** Stable across builds: this is what gets written to settings. */
    val key: String,
    val title: String,
    val category: NewsCategory? = null,
    val social: SocialSource? = null,
)

object NewsTabs {
    /**
     * Categories first, then the discussion sites.
     *
     * ⚠️ A category tab's key is its enum name, which is deliberately the same string the desktop used
     * to persist when the selection *was* a bare [NewsCategory] — so an existing settings file still
     * selects the tab it always did rather than silently falling back to Top Stories.
     */
    val ALL: List<NewsTab> =
        NewsCategory.entries.map { NewsTab(it.name, it.title, category = it) } +
            SocialSource.entries.map { NewsTab("SOCIAL_${it.name}", it.title, social = it) }

    val DEFAULT: NewsTab = ALL.first()

    fun byKey(key: String): NewsTab? = ALL.firstOrNull { it.key == key }
}
