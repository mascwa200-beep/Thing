package dev.mascwa.pulse.data.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves a station URL to a directly-playable audio stream. Many directory entries (Radio Browser
 * `url`, some TuneIn `Tune.ashx` results) point at a **playlist** file — `.pls` / `.m3u` / `.asx` —
 * which is a tiny TEXT file listing the real stream(s). Handed straight to ExoPlayer that text file is
 * "played" as audio: it opens, reaches end immediately, and the player reports the stream dropped
 * ("plays then can't load"), or fails the container parse ("just doesn't load"). This fetches the
 * playlist and returns the first real stream URL inside it. Direct streams and HLS (`.m3u8`) pass
 * through untouched — ExoPlayer handles those natively.
 *
 * Fully defensive: any failure returns the original URL so the player can still attempt it directly.
 */
object StreamResolver {

    // Picky CDNs 403 the default UA; resolve with a browser UA so playlist fetches actually succeed.
    private const val UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private enum class Kind { PLS, M3U, ASX }

    /** Resolve [url] to a playable stream URL (unchanged for direct streams / HLS). */
    suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        val kind = playlistKind(url) ?: return@withContext url
        runCatching { fetchAndParse(url, kind) }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
    }

    /** Detect a resolvable playlist by extension (query/fragment stripped). HLS `.m3u8` is deliberately
     *  NOT treated as a playlist — ExoPlayer plays it natively. */
    private fun playlistKind(url: String): Kind? {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> null
            path.endsWith(".pls") -> Kind.PLS
            path.endsWith(".m3u") -> Kind.M3U
            path.endsWith(".asx") || path.endsWith(".xspf") -> Kind.ASX
            else -> null
        }
    }

    private fun fetchAndParse(url: String, kind: Kind): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
            }
            conn.connect()
            // Playlists are tiny; cap the read so a mislabelled binary stream can't be slurped whole.
            val body = conn.inputStream.bufferedReader().use { it.readText().take(64_000) }
            when (kind) {
                Kind.PLS -> parsePls(body)
                Kind.M3U -> parseM3u(body)
                Kind.ASX -> parseAsx(body)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** `[playlist]` … `File1=http://…` — return the first File entry's URL. */
    private fun parsePls(body: String): String? =
        body.lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("File", ignoreCase = true) && it.contains('=') &&
                    it.substringAfter('=').trim().startsWith("http", ignoreCase = true)
            }
            ?.substringAfter('=')?.trim()

    /** A plain `.m3u` is just stream URLs (with optional `#EXTINF` comments) — return the first URL. */
    private fun parseM3u(body: String): String? =
        body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http", ignoreCase = true) }

    /** `<ref href="http://…"/>` (ASX) or `<location>http://…</location>` (XSPF) — return the first one. */
    private fun parseAsx(body: String): String? =
        Regex("href\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.trim()
            ?: Regex("<location>([^<]+)</location>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.getOrNull(1)?.trim()
}
