package dev.mascwa.pulse.core.network

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One normalized feed entry, source-agnostic across RSS 2.0 and Atom. */
data class RssItem(
    val title: String,
    val link: String,
    val description: String,
    val publishedEpochMs: Long,
    val sourceName: String?,
    val imageUrl: String?,
)

data class RssFeed(
    val title: String,
    val items: List<RssItem>,
)

/**
 * Dependency-free RSS 2.0 + Atom parser built on the platform XmlPullParser.
 * Tolerant of namespaces (media:, dc:, content:) and malformed dates.
 */
object RssParser {

    fun parse(xml: String): RssFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml.trim().removePrefix("﻿")))

        var feedTitle = ""
        val items = mutableListOf<RssItem>()
        var event = parser.eventType

        // Per-item accumulators
        var inItem = false
        var title = ""
        var link = ""
        var description = ""
        var pubDate = ""
        var source: String? = null
        var image: String? = null

        fun resetItem() {
            title = ""; link = ""; description = ""; pubDate = ""; source = null; image = null
        }

        var sawChannelTitle = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.lowercase()) {
                        "item", "entry" -> { inItem = true; resetItem() }
                        "title" -> {
                            val t = readText(parser)
                            if (inItem) title = t
                            else if (!sawChannelTitle) { feedTitle = t; sawChannelTitle = true }
                        }
                        "link" -> {
                            if (inItem && link.isBlank()) {
                                // Atom uses href attribute; RSS uses element text.
                                val href = parser.getAttributeValue(null, "href")
                                link = href ?: readText(parser)
                            }
                        }
                        "guid" -> {
                            if (inItem && link.isBlank()) {
                                val g = readText(parser)
                                if (g.startsWith("http")) link = g
                            }
                        }
                        "description", "summary", "content", "content:encoded" -> {
                            if (inItem) {
                                val html = readText(parser)
                                if (description.isBlank()) description = html
                                if (image == null) image = extractImageFromHtml(html)
                            }
                        }
                        "pubdate", "published", "updated", "dc:date" -> {
                            if (inItem && pubDate.isBlank()) pubDate = readText(parser)
                        }
                        "source" -> {
                            if (inItem) {
                                val txt = readText(parser)
                                if (txt.isNotBlank()) source = txt
                            }
                        }
                        "media:content", "media:thumbnail", "enclosure" -> {
                            if (inItem && image == null) {
                                parser.getAttributeValue(null, "url")?.let { url ->
                                    if (looksLikeImage(url)) image = url
                                }
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "item", "entry" -> {
                            if (title.isNotBlank() && link.isNotBlank()) {
                                items += RssItem(
                                    title = cleanText(title),
                                    link = link.trim(),
                                    description = cleanText(stripHtml(description)).take(400),
                                    publishedEpochMs = parseDate(pubDate),
                                    sourceName = source?.let { cleanText(it) },
                                    imageUrl = image,
                                )
                            }
                            inItem = false
                        }
                    }
                }
            }
            event = parser.next()
        }
        return RssFeed(cleanText(feedTitle), items)
    }

    private fun readText(parser: XmlPullParser): String {
        // Handles plain text and CDATA. Advances to the END_TAG.
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(parser.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    private val DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd",
    )

    private fun parseDate(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0L
        for (p in DATE_PATTERNS) {
            try {
                val fmt = SimpleDateFormat(p, Locale.ENGLISH)
                if (p.endsWith("'Z'") || p == "yyyy-MM-dd") fmt.timeZone = TimeZone.getTimeZone("UTC")
                return fmt.parse(s)?.time ?: continue
            } catch (_: Exception) { /* try next */ }
        }
        return 0L
    }

    private fun looksLikeImage(url: String): Boolean {
        val u = url.lowercase()
        return u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png") ||
            u.endsWith(".webp") || u.contains("image") || u.contains(".jpg?") || u.contains(".png?")
    }

    private val IMG_REGEX = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private fun extractImageFromHtml(html: String): String? =
        IMG_REGEX.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") }

    private val TAG_REGEX = Regex("<[^>]*>")
    private fun stripHtml(html: String): String = html.replace(TAG_REGEX, " ")

    private fun cleanText(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#34;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
