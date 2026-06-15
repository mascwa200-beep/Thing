package dev.mascwa.pulse.data.images

import dev.mascwa.pulse.core.network.HttpClient
import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Content-neutral web image search. Term search uses keyless open sources
 * (Openverse + Wikimedia Commons). Custom sources are any URL the user adds:
 * the page's HTML is fetched and its <img>/og:image links are extracted. A
 * "%s" in a custom URL is replaced with the query (so a site's own search URL
 * can be used). The app does not bundle or curate any sources.
 */
class ImageRepository(private val http: HttpClient) {

    suspend fun searchWeb(query: String): List<ImageResult> {
        if (query.isBlank()) return emptyList()
        val openverse = runCatching { openverse(query) }.getOrDefault(emptyList())
        val wikimedia = runCatching { wikimedia(query) }.getOrDefault(emptyList())
        return (openverse + wikimedia).distinctBy { it.fullUrl }
    }

    suspend fun fromSite(siteTemplate: String, query: String): List<ImageResult> {
        val url = if (siteTemplate.contains("%s")) {
            siteTemplate.replace("%s", URLEncoder.encode(query.trim(), "UTF-8"))
        } else siteTemplate
        return runCatching { extractImages(url) }.getOrDefault(emptyList())
    }

    private suspend fun openverse(query: String): List<ImageResult> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://api.openverse.org/v1/images/?q=$q&page_size=40"
        val results = http.json.parseToJsonElement(http.getString(url))
            .jsonObject["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el.jsonObject
            val full = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ImageResult(
                thumbUrl = o["thumbnail"]?.jsonPrimitive?.contentOrNull ?: full,
                fullUrl = full,
                sourceUrl = o["foreign_landing_url"]?.jsonPrimitive?.contentOrNull ?: full,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: "Image",
            )
        }
    }

    private suspend fun wikimedia(query: String): List<ImageResult> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search" +
            "&gsrnamespace=6&gsrsearch=$q&gsrlimit=30&prop=imageinfo&iiprop=url&iiurlwidth=320&format=json"
        val pages = http.json.parseToJsonElement(http.getString(url))
            .jsonObject["query"]?.jsonObject?.get("pages")?.jsonObject ?: return emptyList()
        return pages.values.mapNotNull { p ->
            val info = p.jsonObject["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val full = info["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ImageResult(
                thumbUrl = info["thumburl"]?.jsonPrimitive?.contentOrNull ?: full,
                fullUrl = full,
                sourceUrl = info["descriptionurl"]?.jsonPrimitive?.contentOrNull ?: full,
                title = p.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.removePrefix("File:") ?: "Image",
            )
        }
    }

    private val imgRegex = Regex("<img[^>]+(?:data-src|src)=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val ogRegex = Regex("property=[\"']og:image[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    private suspend fun extractImages(pageUrl: String): List<ImageResult> {
        val html = http.getString(pageUrl)
        val base = URI(pageUrl)
        val found = LinkedHashSet<String>()
        ogRegex.findAll(html).forEach { found.add(it.groupValues[1]) }
        imgRegex.findAll(html).forEach { found.add(it.groupValues[1]) }
        return found.mapNotNull { raw -> resolve(base, raw) }
            .filter { it.startsWith("http") && !it.startsWith("data:") }
            .distinct()
            .take(60)
            .map { ImageResult(thumbUrl = it, fullUrl = it, sourceUrl = pageUrl, title = "Image") }
    }

    private fun resolve(base: URI, ref: String): String? = try {
        when {
            ref.startsWith("http", true) -> ref
            ref.startsWith("//") -> "${base.scheme}:$ref"
            else -> base.resolve(ref).toString()
        }
    } catch (_: Exception) {
        null
    }
}
