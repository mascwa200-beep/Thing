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

    /**
     * Resolve [url] to a playable stream URL (unchanged for direct streams / HLS).
     *
     * ⚠️ [sniff] is a RECOVERY mode, not a better default. Detection is by file extension, which
     * misses a playlist served from an extensionless path — `/listen`, `Tune.ashx`, `?type=pls` —
     * with `Content-Type: audio/x-scpls`. ExoPlayer is then handed a text file, reaches the end of it
     * immediately, and reports a dropped stream, which looks exactly like a station that will not
     * stay tuned.
     *
     * The tempting fix is to probe every address before playing. That is wrong here: a probe opens a
     * connection to the mount, and a duplicate listener from one address is precisely what makes
     * connection-limited stations drop the audio — the defect this whole change exists to remove. So
     * the sniff runs only AFTER playback has already failed, where it costs nothing on the happy
     * path and there is no live connection left to disturb.
     */
    suspend fun resolve(url: String, sniff: Boolean = false): String = withContext(Dispatchers.IO) {
        val kind = playlistKind(url)
        if (kind != null) {
            return@withContext runCatching { fetchAndParse(url, kind) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: url
        }
        if (!sniff) return@withContext url
        // No usable extension and playback already failed: fetch once and try each parser in turn.
        // Whichever yields an http address wins; none of them doing so means it really was audio.
        runCatching { fetchAndSniff(url) }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
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

    /**
     * Fetch [url] once and see whether it is a playlist after all, whatever it is called.
     *
     * ⚠️ Reads a bounded prefix and refuses anything that does not look like text, so a real audio
     * stream — which this is called on by definition, since playback just failed — is dropped after
     * a few kilobytes rather than being read as a string. The `Content-Type` is consulted first
     * because it is the authoritative answer when the server bothers to give one.
     */
    /** A playlist is a few hundred bytes; nothing legitimate here is close to this. */
    private const val MAX_PLAYLIST_CHARS = 64_000

    /**
     * Read at most [MAX_PLAYLIST_CHARS], and STOP.
     *
     * ⚠️ **`readText().take(64_000)` reads the whole body first and then throws away all but the
     * first 64 kB**, which is the opposite of a cap. The comment it replaces claimed it stopped a
     * mislabelled binary stream being "slurped whole" — it is exactly what it did not do. A station
     * that serves continuous audio under a playlist content type has no end, so the read ran until
     * the 12-second timeout, buffering everything that arrived in the meantime. On the phone this
     * app is meant to run well on, that is megabytes allocated to be discarded a moment later.
     */
    private fun readCapped(conn: HttpURLConnection): String {
        val out = StringBuilder()
        val buf = CharArray(4096)
        conn.inputStream.bufferedReader().use { r ->
            while (out.length < MAX_PLAYLIST_CHARS) {
                // ⚠️ Bounded by what is LEFT in the budget, not by the buffer, or the last read
                // could overshoot the cap by up to a buffer's worth.
                val n = r.read(buf, 0, minOf(buf.size, MAX_PLAYLIST_CHARS - out.length))
                if (n <= 0) break
                out.appendRange(buf, 0, n) // endIndex is exclusive
            }
        }
        return out.toString()
    }

    private fun fetchAndSniff(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", UA)
                connectTimeout = 12_000
                readTimeout = 12_000
                instanceFollowRedirects = true
            }
            conn.connect()
            val type = conn.contentType.orEmpty().lowercase()
            // audio/mpeg, audio/aac and friends are the stream itself: nothing to parse, leave now
            // rather than reading any of it.
            val looksLikeAudio = type.startsWith("audio/") &&
                PLAYLIST_TYPES.none { type.contains(it) }
            if (looksLikeAudio) return null
            val body = readCapped(conn)
            // Order matters only in that each parser is cheap and returns null on a non-match.
            parsePls(body) ?: parseM3u(body) ?: parseAsx(body)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Content types that ARE playlists despite living under `audio/`. */
    private val PLAYLIST_TYPES = listOf("scpls", "mpegurl", "x-mpegurl", "xspf", "ms-asf")

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
            val body = readCapped(conn)
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
