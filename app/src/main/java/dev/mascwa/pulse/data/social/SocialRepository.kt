package dev.mascwa.pulse.data.social

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

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
            val posts = http.json.parseToJsonElement(http.getString(url))
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
                    thumbnail = post["thumbnail_url"]?.jsonPrimitive?.contentOrNull,
                )
            })
        }
    }

    suspend fun mastodon(force: Boolean): Fetched<MastodonTrends> {
        val instance = settings.current().mastodonInstance.ifBlank { "mastodon.social" }
        val key = "social_mastodon_$instance"
        return cachedJson(key, force, MastodonTrends.serializer()) {
            val tags = runCatching {
                val arr = http.json.parseToJsonElement(
                    http.getString("https://$instance/api/v1/trends/tags?limit=20"),
                ).jsonArray
                arr.mapNotNull { el ->
                    val o = el.jsonObject
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val uses = o["history"]?.jsonArray?.firstOrNull()?.jsonObject
                        ?.get("uses")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    TrendTag(name, o["url"]?.jsonPrimitive?.contentOrNull ?: "", uses)
                }
            }.getOrDefault(emptyList())

            val statuses = runCatching {
                val arr = http.json.parseToJsonElement(
                    http.getString("https://$instance/api/v1/trends/statuses?limit=20"),
                ).jsonArray
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
                    )
                }
            }.getOrDefault(emptyList())

            MastodonTrends(tags, statuses)
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
            val items = coroutineScope {
                ids.map { id ->
                    async {
                        runCatching {
                            val o = http.json.parseToJsonElement(
                                http.getString("https://hacker-news.firebaseio.com/v0/item/$id.json"),
                            ).jsonObject
                            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                            val link = o["url"]?.jsonPrimitive?.contentOrNull ?: "https://news.ycombinator.com/item?id=$id"
                            val score = o["score"]?.jsonPrimitive?.intOrNull ?: 0
                            val comments = o["descendants"]?.jsonPrimitive?.intOrNull ?: 0
                            val time = (o["time"]?.jsonPrimitive?.longOrNull ?: 0L) * 1000L
                            SocialItem(title, link, "Hacker News", "▲ $score · $comments comments", time)
                        }.getOrNull()
                    }
                }.mapNotNull { it.await() }
            }
            SocialFeed(items)
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
    private fun stripHtml(s: String) = s.replace(tagRegex, " ").replace("&amp;", "&")
        .replace("&#39;", "'").replace("&quot;", "\"").replace(Regex("\\s+"), " ").trim()
}
