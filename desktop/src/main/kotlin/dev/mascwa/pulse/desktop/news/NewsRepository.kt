package dev.mascwa.pulse.desktop.news

import dev.mascwa.pulse.desktop.cache.DiskCache
import dev.mascwa.pulse.desktop.network.HttpClient
import dev.mascwa.pulse.desktop.network.RssParser
import java.net.URLEncoder

/**
 * Fetches headlines from Google News' keyless RSS endpoints — the same source and the same URL shapes the
 * Android app uses, so both clients see the same feed without either needing an API key.
 *
 * This is the piece that was missing: [HttpClient], [RssParser] and [DiskCache] were all ported in an
 * earlier slice but nothing ever called them, so the desktop app made no network requests at all. It is
 * cache-first — a fetch failure serves whatever was last stored rather than emptying the screen, which is
 * the behaviour that matters on a laptop that sleeps and wakes on a different network.
 */
class NewsRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {

    /**
     * Headlines for [category]. Serves a fresh-enough cache entry without touching the network; on a
     * network failure falls back to a stale entry, and only surfaces an error when there is nothing at all.
     *
     * ⚠️ Returns [Fetched], not a bare list. The cache has always known when each entry was written and
     * this threw it away at the boundary, so the fallback above — serving whatever was last stored after
     * a failed request — was completely silent: the screen showed old headlines that looked live. Saying
     * so needs the timestamp to survive the return.
     */
    suspend fun headlines(category: NewsCategory, force: Boolean = false): Result<Fetched<List<Article>>> {
        val key = "news_${category.name}"
        if (!force) {
            cache.read(key, FRESH_MS, ArticleList.serializer())?.let {
                return Result.success(Fetched(it.value.articles, fromCache = true, timestampEpochMs = it.savedAtMs))
            }
        }
        return runCatching {
            val xml = http.getString(urlFor(category))
            val articles = RssParser.parse(xml).items.mapNotNull { item ->
                if (item.title.isBlank() || item.link.isBlank()) return@mapNotNull null
                Article(
                    title = item.title,
                    url = item.link,
                    summary = item.description,
                    // Google News titles carry a trailing " - Source"; the feed's own source field wins.
                    source = item.sourceName ?: item.title.substringAfterLast(" - ", "").ifBlank { "Unknown" },
                    publishedEpochMs = item.publishedEpochMs,
                    imageUrl = item.imageUrl,
                    category = category.name,
                )
            }
            if (articles.isEmpty()) error("That feed returned nothing.")
            cache.write(key, ArticleList(articles), ArticleList.serializer())
            Fetched(articles, fromCache = false, timestampEpochMs = System.currentTimeMillis())
        }.recoverCatching { failure ->
            val stored = cache.readAny(key, ArticleList.serializer()) ?: throw failure
            // refreshFailed, not merely fromCache: the request was made and it did not work, which is a
            // different thing to say than "this came from disk".
            Fetched(stored.value.articles, fromCache = true, refreshFailed = true, timestampEpochMs = stored.savedAtMs)
        }
    }

    private fun urlFor(category: NewsCategory): String {
        val locale = "hl=en-US&gl=US&ceid=US:en"
        return when {
            category.searchQuery != null ->
                "$BASE/search?q=${URLEncoder.encode(category.searchQuery, "UTF-8")}&$locale"
            category.googleTopic != null ->
                "$BASE/headlines/section/topic/${category.googleTopic}?$locale"
            else -> "$BASE?$locale"
        }
    }

    private companion object {
        const val BASE = "https://news.google.com/rss"

        /** Headlines older than this are refetched. Long enough that flicking between tabs is instant. */
        const val FRESH_MS = 10 * 60 * 1000L
    }
}
