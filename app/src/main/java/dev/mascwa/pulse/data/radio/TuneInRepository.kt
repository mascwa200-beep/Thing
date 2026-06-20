package dev.mascwa.pulse.data.radio

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

/**
 * Station search via **TuneIn**'s free, keyless OPML API (`opml.radiotime.com`, a.k.a. radiotime — the
 * long-standing directory Sonos/etc. use). It carries the huge catalogue of US commercial broadcast
 * stations (iHeart/Townsquare/Cumulus/Audacy affiliates) that the community Radio Browser DB is missing —
 * e.g. WTRV "100.5 The River" Grand Rapids — so a name search actually finds them.
 *
 * Two steps: Search.ashx returns station entries (name + a Tune.ashx link); Tune.ashx resolves the link to
 * the live stream URL. Fully defensive — any failure yields fewer/zero results, never throws. Additive to
 * the Radio Browser results, so it can only ADD findable stations.
 */
class TuneInRepository(private val http: HttpClient) {

    private data class Entry(val name: String, val tuneUrl: String, val subtext: String)

    /** Up to [limit] TuneIn stations matching [query], each resolved to a playable stream URL. */
    suspend fun search(query: String, limit: Int = 8): List<RadioStation> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val url = "https://opml.radiotime.com/Search.ashx?types=station&formats=mp3,aac&query=" +
            URLEncoder.encode(q, "UTF-8")
        val opml = runCatching { http.getString(url) }.getOrNull() ?: return emptyList()
        val entries = parseStations(opml).take(limit)
        if (entries.isEmpty()) return emptyList()
        return coroutineScope {
            entries.map { e -> async { resolve(e) } }.awaitAll().filterNotNull()
        }
    }

    /** Pull the station outlines (`type="audio"` with a Tune.ashx link) out of the OPML response. */
    private fun parseStations(opml: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (m in OUTLINE.findAll(opml)) {
            val tag = m.value
            if (!tag.contains("type=\"audio\"")) continue
            val url = attr(tag, "URL") ?: continue
            if (!url.contains("Tune.ashx", ignoreCase = true)) continue
            val name = attr(tag, "text")?.let(::unescape) ?: continue
            out.add(Entry(name, unescape(url), attr(tag, "subtext")?.let(::unescape).orEmpty()))
        }
        return out
    }

    /** Resolve a Tune.ashx link to the first live stream URL in its (newline-separated) body. */
    private suspend fun resolve(e: Entry): RadioStation? {
        val body = runCatching { http.getString(e.tuneUrl) }.getOrNull() ?: return null
        val stream = body.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("http", ignoreCase = true) && !it.endsWith(".pls", true) && !it.endsWith(".m3u", true) }
            ?: return null
        val band = e.subtext.ifBlank { "TUNEIN" }.uppercase().take(40)
        return RadioStation(name = e.name, band = band, streamUrl = stream)
    }

    // \b so `text=` never matches inside `subtext=`.
    private fun attr(tag: String, name: String): String? =
        Regex("\\b$name=\"([^\"]*)\"").find(tag)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun unescape(s: String): String =
        s.replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")

    private companion object {
        val OUTLINE = Regex("<outline\\b[^>]*>")
    }
}
