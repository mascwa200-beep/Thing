package dev.mascwa.pulse.desktop.network

import dev.mascwa.pulse.core.network.FeedDate
import java.io.StringReader
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

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
 * Dependency-free RSS 2.0 + Atom parser — a desktop port of the Android app's
 * `core/network/RssParser.kt`. That version is built on Android's `XmlPullParser`
 * (`android.util.Xml.newPullParser()`), which doesn't exist on the JVM; this rewrites the exact same
 * tag-by-tag algorithm on top of the JDK's own `javax.xml.stream` (StAX) `XMLStreamReader`, so it needs no
 * new dependency. Namespace processing is disabled on both ([XMLInputFactory.IS_NAMESPACE_AWARE] false here,
 * `FEATURE_PROCESS_NAMESPACES` false there), so a prefixed tag like `media:content` reads as one flat name
 * in both APIs and the `when` branches below match the original 1:1. Tolerant of malformed dates; never
 * throws on a structurally valid-but-unexpected feed (unknown tags are just ignored by the `when`s).
 */
object RssParser {

    fun parse(xml: String): RssFeed {
        val reader = newReader(xml)
        try {
            var feedTitle = ""
            val items = mutableListOf<RssItem>()
            var event = reader.eventType

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

            while (event != XMLStreamConstants.END_DOCUMENT) {
                when (event) {
                    XMLStreamConstants.START_ELEMENT -> {
                        when (reader.localName.lowercase()) {
                            "item", "entry" -> { inItem = true; resetItem() }
                            "title" -> {
                                val t = readText(reader)
                                if (inItem) title = t
                                else if (!sawChannelTitle) { feedTitle = t; sawChannelTitle = true }
                            }
                            "link" -> {
                                if (inItem && link.isBlank()) {
                                    // Atom uses href attribute; RSS uses element text.
                                    val href = reader.getAttributeValue(null, "href")
                                    link = href ?: readText(reader)
                                }
                            }
                            "guid" -> {
                                if (inItem && link.isBlank()) {
                                    val g = readText(reader)
                                    if (g.startsWith("http")) link = g
                                }
                            }
                            "description", "summary", "content", "content:encoded" -> {
                                if (inItem) {
                                    val html = readText(reader)
                                    if (description.isBlank()) description = html
                                    if (image == null) image = extractImageFromHtml(html)
                                }
                            }
                            "pubdate", "published", "updated", "dc:date" -> {
                                if (inItem && pubDate.isBlank()) pubDate = readText(reader)
                            }
                            "source" -> {
                                if (inItem) {
                                    val txt = readText(reader)
                                    if (txt.isNotBlank()) source = txt
                                }
                            }
                            "media:content", "media:thumbnail", "enclosure" -> {
                                if (inItem && image == null) {
                                    reader.getAttributeValue(null, "url")?.let { url ->
                                        if (looksLikeImage(url)) image = url
                                    }
                                }
                            }
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        when (reader.localName.lowercase()) {
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
                event = reader.next()
            }
            return RssFeed(cleanText(feedTitle), items)
        } finally {
            runCatching { reader.close() }
        }
    }

    private fun newReader(xml: String): XMLStreamReader {
        val factory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
            // Untrusted network XML: no DTD/external-entity resolution (XXE hardening) — this is new
            // relative to the Android original since that ran through Android's own pull parser rather than
            // a general-purpose JVM StAX implementation, which can otherwise fetch external entities.
            runCatching { setProperty(XMLInputFactory.SUPPORT_DTD, false) }
            runCatching { setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false) }
        }
        return factory.createXMLStreamReader(StringReader(xml.trim().removePrefix("﻿")))
    }

    private fun readText(reader: XMLStreamReader): String {
        // Handles plain text and CDATA. Advances to the matching END_ELEMENT.
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0 && reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> sb.append(reader.text)
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
                XMLStreamConstants.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    /**
     * ⚠️ Shared with the Android parser rather than copied beside it. The XML pulling genuinely
     * differs by platform — that one uses `android.util.Xml`, this one `XMLStreamReader` — and that
     * is why these two files exist at all. Reading a date out of a string is not platform work, and
     * the copy this replaced read six fractional digits as whole milliseconds.
     */
    private fun parseDate(raw: String): Long = FeedDate.parse(raw)

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
