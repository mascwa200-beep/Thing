package dev.mascwa.pulse.data.news

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.network.RssItem
import dev.mascwa.pulse.core.network.RssParser
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.settings.CustomFeed
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NewsRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
    private val settings: SettingsRepository,
) {
    private val ttl = 15 * 60 * 1000L // 15 minutes

    suspend fun fetchCategory(category: NewsCategory, force: Boolean): Fetched<List<Article>> {
        val s = settings.current()
        val key = "news_${category.name}_${s.newsCountry}_${s.newsLanguage}"

        if (!force) {
            cache.read(key, ttl, ListSerializer(Article.serializer()))?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val fresh = loadCategory(category, s)
            cache.write(key, fresh, ListSerializer(Article.serializer()))
            Fetched(fresh, false)
        } catch (e: Exception) {
            cache.readAny(key, ListSerializer(Article.serializer()))?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    suspend fun fetchCustomFeeds(force: Boolean): Fetched<List<Article>> {
        val s = settings.current()
        val key = "news_customfeeds"
        if (!force) {
            cache.read(key, ttl, ListSerializer(Article.serializer()))?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
            val all = coroutineScope {
                s.customFeeds.map { feed ->
                    async {
                        runCatching { loadCustomFeed(feed) }
                            .getOrElse {
                                failures.add("${feed.name}: ${feedFailureReason(it)}")
                                emptyList()
                            }
                    }
                }.flatMap { it.await() }
            }.distinctBy { it.url }.sortedByDescending { it.publishedEpochMs }

            if (all.isEmpty() && failures.isNotEmpty()) {
                // Don't dead-end on a blank "No articles found" — say which feeds failed and why.
                throw java.io.IOException(
                    "Couldn't load your feeds:\n" + failures.joinToString("\n") { "• $it" } +
                        "\n\nThe URL must be the RSS feed itself (often ends in .xml, /feed, or RSSFeed.aspx?…), " +
                        "not the website page.",
                )
            }
            cache.write(key, all, ListSerializer(Article.serializer()))
            Fetched(all, false)
        } catch (e: Exception) {
            cache.readAny(key, ListSerializer(Article.serializer()))?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    /**
     * Load one custom feed, tolerant of a user who pasted the website page URL instead of the feed URL:
     * if the response doesn't parse as RSS/Atom, try RSS auto-discovery (the page's
     * `<link rel="alternate" type="application/rss+xml">`) and fetch that instead. Throws with a readable
     * reason when there's genuinely no feed.
     */
    private suspend fun loadCustomFeed(feed: CustomFeed): List<Article> {
        val xml = http.getString(feed.url)
        runCatching { RssParser.parse(xml) }.getOrNull()?.takeIf { it.items.isNotEmpty() }?.let { parsed ->
            return parsed.items.map { it.toArticle(feed.name, "My Feeds") }
        }
        // Not a parseable feed (likely an HTML page) — try to discover the real feed link in the markup.
        val discovered = discoverFeedUrl(xml, feed.url)
        if (discovered != null && discovered != feed.url) {
            val parsed2 = RssParser.parse(http.getString(discovered))
            if (parsed2.items.isNotEmpty()) return parsed2.items.map { it.toArticle(feed.name, "My Feeds") }
        }
        // A valid-but-empty feed returns nothing; an unparseable page is a configuration error.
        if (xml.trimStart().startsWith("<?xml") || xml.contains("<rss") || xml.contains("<feed")) return emptyList()
        throw IllegalStateException("not an RSS feed — that looks like a web page")
    }

    private fun feedFailureReason(e: Throwable): String = when (e) {
        is dev.mascwa.pulse.core.network.HttpException -> "couldn't fetch (HTTP ${e.code})"
        else -> e.message ?: "couldn't load"
    }

    /** Find a feed URL advertised in an HTML page's `<link rel="alternate" type="application/rss+xml">`. */
    private fun discoverFeedUrl(html: String, baseUrl: String): String? {
        for (tag in Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }) {
            val lower = tag.lowercase()
            if (!lower.contains("alternate")) continue
            if (!lower.contains("rss+xml") && !lower.contains("atom+xml")) continue
            val href = Regex("href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: continue
            return runCatching { java.net.URI(baseUrl).resolve(href).toString() }.getOrDefault(href)
        }
        return null
    }

    suspend fun search(query: String): List<Article> {
        val s = settings.current()
        val url = googleSearchUrl(query, s)
        val xml = http.getString(url)
        return RssParser.parse(xml).items
            .map { it.toArticle(null, "Search") }
            .applyMutes(s)
            .take(s.newsItemsPerCategory)
    }

    private suspend fun loadCategory(category: NewsCategory, s: AppSettings): List<Article> {
        // Prefer NewsAPI when a key is present and the category is supported.
        val viaApi = if (s.apiKeys.hasNewsApi && category.newsApiCategory != null) {
            runCatching { loadFromNewsApi(category, s) }.getOrNull()
        } else null

        val base = viaApi ?: loadFromGoogleRss(category, s)

        // Enrich Tech with Hacker News top stories (keyless).
        val merged = if (category == NewsCategory.TECH) {
            (base + runCatching { loadHackerNews() }.getOrDefault(emptyList()))
        } else base

        return merged
            .applyMutes(s)
            .distinctBy { it.url }
            .sortedByDescending { it.publishedEpochMs }
            .take(s.newsItemsPerCategory)
    }

    private suspend fun loadFromGoogleRss(category: NewsCategory, s: AppSettings): List<Article> {
        val url = when {
            category == NewsCategory.TOP -> googleTopUrl(s)
            category.googleTopic != null -> googleTopicUrl(category.googleTopic, s)
            else -> googleSearchUrl(category.searchQuery ?: category.title, s)
        }
        val xml = http.getString(url)
        return RssParser.parse(xml).items.map { it.toArticle(null, category.title) }
    }

    private suspend fun loadFromNewsApi(category: NewsCategory, s: AppSettings): List<Article> {
        val cat = category.newsApiCategory!!
        val country = s.newsCountry.lowercase()
        val url = "https://newsapi.org/v2/top-headlines?country=$country&category=$cat" +
            "&pageSize=${s.newsItemsPerCategory}&apiKey=${s.apiKeys.newsApi}"
        val resp = http.getJson(url, NewsApiResponse.serializer())
        if (resp.status != "ok") error(resp.message ?: "NewsAPI error")
        return resp.articles.mapNotNull { a ->
            val link = a.url ?: return@mapNotNull null
            Article(
                title = a.title.orEmpty().ifBlank { return@mapNotNull null },
                url = link,
                summary = a.description.orEmpty(),
                source = a.source?.name ?: "",
                publishedEpochMs = parseIso(a.publishedAt),
                imageUrl = a.urlToImage,
                category = category.title,
            )
        }
    }

    private suspend fun loadHackerNews(limit: Int = 12): List<Article> = coroutineScope {
        val ids = http.getJson(
            "https://hacker-news.firebaseio.com/v0/topstories.json",
            ListSerializer(Long.serializer()),
        ).take(limit)
        ids.map { id ->
            async {
                runCatching {
                    val item = http.getJson(
                        "https://hacker-news.firebaseio.com/v0/item/$id.json",
                        HackerNewsItem.serializer(),
                    )
                    val link = item.url ?: "https://news.ycombinator.com/item?id=${item.id}"
                    val title = item.title ?: return@runCatching null
                    Article(
                        title = title,
                        url = link,
                        summary = "${item.score} points · ${item.descendants} comments on Hacker News",
                        source = "Hacker News",
                        publishedEpochMs = item.time * 1000L,
                        imageUrl = null,
                        category = NewsCategory.TECH.title,
                    )
                }.getOrNull()
            }
        }.mapNotNull { it.await() }
    }

    // --- URL builders (Google News RSS is keyless) ---

    private fun ceid(s: AppSettings) = "${s.newsCountry}:${s.newsLanguage}"

    private fun googleTopUrl(s: AppSettings) =
        "https://news.google.com/rss?hl=${s.newsLanguage}&gl=${s.newsCountry}&ceid=${ceid(s)}"

    private fun googleTopicUrl(topic: String, s: AppSettings) =
        "https://news.google.com/rss/headlines/section/topic/$topic" +
            "?hl=${s.newsLanguage}&gl=${s.newsCountry}&ceid=${ceid(s)}"

    private fun googleSearchUrl(query: String, s: AppSettings): String {
        val q = URLEncoder.encode(query, "UTF-8")
        return "https://news.google.com/rss/search?q=$q" +
            "&hl=${s.newsLanguage}&gl=${s.newsCountry}&ceid=${ceid(s)}"
    }

    // --- helpers ---

    private fun List<Article>.applyMutes(s: AppSettings): List<Article> {
        if (s.mutedKeywords.isEmpty()) return this
        val mutes = s.mutedKeywords.map { it.lowercase() }
        return filterNot { art ->
            val hay = (art.title + " " + art.summary).lowercase()
            mutes.any { it.isNotBlank() && hay.contains(it) }
        }
    }

    private fun RssItem.toArticle(sourceOverride: String?, category: String): Article {
        // Google News titles are often "Headline - Source"; split the source out.
        var displayTitle = title
        var src = sourceOverride ?: sourceName ?: ""
        if (src.isBlank()) {
            val idx = title.lastIndexOf(" - ")
            if (idx > 0 && idx > title.length - 60) {
                src = title.substring(idx + 3).trim()
                displayTitle = title.substring(0, idx).trim()
            }
        } else {
            // Trim trailing " - Source" duplication when source is known.
            val suffix = " - $src"
            if (displayTitle.endsWith(suffix)) displayTitle = displayTitle.dropLast(suffix.length).trim()
        }
        return Article(
            title = displayTitle,
            url = link,
            summary = description,
            source = src,
            publishedEpochMs = publishedEpochMs,
            imageUrl = imageUrl,
            category = category,
        )
    }

    private val isoFormats = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")
    private fun parseIso(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        for (p in isoFormats) {
            runCatching {
                val f = SimpleDateFormat(p, Locale.ENGLISH)
                if (p.endsWith("'Z'")) f.timeZone = TimeZone.getTimeZone("UTC")
                return f.parse(s)!!.time
            }
        }
        return 0L
    }
}
