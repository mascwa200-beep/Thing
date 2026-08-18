package dev.mascwa.pulse.data.social

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * Keyless social-discovery feeds: Lemmy popular (Reddit alternative), Mastodon
 * trends, and Hacker News top stories. Reddit is intentionally omitted — it
 * shut off keyless API access in 2026.
 */
class SocialRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
    private val settings: SettingsRepository,
) {
    private val ttl = 10 * 60 * 1000L

    suspend fun lemmy(force: Boolean): Fetched<SocialFeed> {
        val instance = settings.current().lemmyInstance.ifBlank { "lemmy.world" }
        val key = "social_lemmy_$instance"
        return cachedJson(key, force, SocialFeed.serializer()) {
            val url = "https://$instance/api/v3/post/list?sort=Active&type_=All&limit=30"
            val text = http.getString(url)
            val posts = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }
                .jsonObject["posts"]?.jsonArray ?: return@cachedJson SocialFeed(emptyList())
            SocialFeed(posts.mapNotNull { el ->
                val o = el.jsonObject
                val post = o["post"]?.jsonObject ?: return@mapNotNull null
                val title = post["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val link = post["url"]?.jsonPrimitive?.contentOrNull
                    ?: post["ap_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val counts = o["counts"]?.jsonObject
                val score = counts?.get("score")?.jsonPrimitive?.intOrNull ?: 0
                val comments = counts?.get("comments")?.jsonPrimitive?.intOrNull ?: 0
                val community = o["community"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: instance
                SocialItem(
                    title = title,
                    url = link,
                    source = "c/$community",
                    meta = "▲ $score · $comments comments",
                    publishedEpochMs = isoMillis(post["published"]?.jsonPrimitive?.contentOrNull),
                    thumbnail = post["thumbnail_url"]?.jsonPrimitive?.contentOrNull,
                )
            })
        }
    }

    suspend fun mastodon(force: Boolean): Fetched<MastodonTrends> {
        val instance = settings.current().mastodonInstance.ifBlank { "mastodon.social" }
        val key = "social_mastodon_$instance"
        return cachedJson(key, force, MastodonTrends.serializer()) {
            val tagsResult = runCatching {
                val text = http.getString("https://$instance/api/v1/trends/tags?limit=20")
                val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val uses = o["history"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("uses")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    TrendTag(name, o["url"]?.jsonPrimitive?.contentOrNull ?: "", uses)
                }
            }

            val statusesResult = runCatching {
                val text = http.getString("https://$instance/api/v1/trends/statuses?limit=20")
                val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val link = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val content = stripHtml(o["content"]?.jsonPrimitive?.contentOrNull ?: "")
                    val acct = o["account"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull ?: "user"
                    val favs = o["favourites_count"]?.jsonPrimitive?.intOrNull ?: 0
                    val reblogs = o["reblogs_count"]?.jsonPrimitive?.intOrNull ?: 0
                    SocialItem(
                        title = content.ifBlank { "(media post)" }.take(200),
                        url = link, source = "@$acct",
                        meta = "♥ $favs · ↻ $reblogs",
                        publishedEpochMs = isoMillis(o["created_at"]?.jsonPrimitive?.contentOrNull),
                    )
                }
            }

            // ⚠️ Both halves used to swallow, so a total outage produced an empty-but-successful
            // result — which cachedJson then wrote over the last good one and the screen rendered
            // as "Nothing trending right now." An outage is not a quiet internet. One half failing
            // is still real data and is kept.
            if (tagsResult.isFailure && statusesResult.isFailure) {
                throw tagsResult.exceptionOrNull() ?: IllegalStateException("mastodon unreachable")
            }
            MastodonTrends(tagsResult.getOrDefault(emptyList()), statusesResult.getOrDefault(emptyList()))
        }
    }

    suspend fun hackerNews(force: Boolean): Fetched<SocialFeed> {
        val key = "social_hn"
        return cachedJson(key, force, SocialFeed.serializer()) {
            val ids = http.getJson(
                "https://hacker-news.firebaseio.com/v0/topstories.json",
                ListSerializer(Long.serializer()),
            ).take(25)
            // Fetch all item details in parallel (was sequential — a big stall).
            val results = coroutineScope {
                ids.map { id ->
                    async {
                        runCatching {
                            val text = http.getString("https://hacker-news.firebaseio.com/v0/item/$id.json")
                            val o = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject
                            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                            val link = o["url"]?.jsonPrimitive?.contentOrNull ?: "https://news.ycombinator.com/item?id=$id"
                            val score = o["score"]?.jsonPrimitive?.intOrNull ?: 0
                            val comments = o["descendants"]?.jsonPrimitive?.intOrNull ?: 0
                            val time = (o["time"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000L
                            SocialItem(title, link, "Hacker News", "▲ $score · $comments comments", time)
                        }
                    }
                }.map { it.await() }
            }
            // ⚠️ Every one of these used to swallow its own failure, so twenty-five dead requests
            // produced an empty feed with no error — written to the cache as a fact and shown as
            // "Nothing trending right now." for the rest of the session.
            //
            // Only a total failure throws. A story that came back without a title is a *successful*
            // fetch of something unrenderable (a poll, a deleted item), so "all failed" is asked of
            // the results rather than of the list, which would also fire on a page of those.
            if (ids.isNotEmpty() && results.all { it.isFailure }) {
                throw results.first().exceptionOrNull()
                    ?: IllegalStateException("hacker news unreachable")
            }
            SocialFeed(results.mapNotNull { it.getOrNull() })
        }
    }

    private suspend fun <T> cachedJson(
        key: String,
        force: Boolean,
        serializer: kotlinx.serialization.KSerializer<T>,
        fetch: suspend () -> T,
    ): Fetched<T> {
        if (!force) {
            cache.read(key, ttl, serializer)?.let { return Fetched(it.value, true, it.savedAtMs) }
        }
        return try {
            val fresh = fetch()
            cache.write(key, fresh, serializer)
            Fetched(fresh, false)
        } catch (e: Exception) {
            cache.readAny(key, serializer)?.let { return Fetched(it.value, true, it.savedAtMs) }
            throw e
        }
    }

    private val tagRegex = Regex("<[^>]*>")
    private val wsRegex = Regex("\\s+")
    /**
     * An ISO-8601 instant in milliseconds, or 0 when there is not one.
     *
     * ⚠️ Not [java.text.SimpleDateFormat]. Lemmy publishes six fractional digits
     * (`2026-08-16T16:21:45.104208Z`) where Mastodon publishes three, and a `.SSS` pattern reads
     * the six-digit form as 104,208 milliseconds — a story stamped nearly two minutes into its own
     * future. [Instant.parse] accepts any number of fractional digits.
     *
     * Some Lemmy instances omit the zone designator entirely, which ISO_INSTANT rejects; those
     * timestamps are UTC by the API's own definition, so a bare one is retried with the Z it
     * should have carried.
     */
    private fun isoMillis(raw: String?): Long {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        runCatching { return Instant.parse(text).toEpochMilli() }
        if (!text.endsWith("Z") && !text.contains('+')) {
            runCatching { return Instant.parse(text + "Z").toEpochMilli() }
        }
        return 0L
    }

    private fun stripHtml(s: String) = s.replace(tagRegex, " ").replace("&amp;", "&")
        .replace("&#39;", "'").replace("&quot;", "\"").replace(wsRegex, " ").trim()
}
