package dev.mascwa.pulse.data.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the current **ICY** (Icecast/SHOUTcast) now-playing title from a stream. Android's `MediaPlayer`
 * plays the audio but doesn't surface ICY metadata, so this opens a separate, short-lived connection
 * with `Icy-MetaData: 1`, skips to the interleaved metadata block, and parses `StreamTitle`. It reads
 * only a few KB then disconnects — not a second playback stream. Fully defensive: any failure (no
 * metadata, timeout, offline) yields null.
 */
object IcyMetadata {

    /** Current "Artist - Song" for [streamUrl], or null if the stream carries no ICY metadata. */
    suspend fun nowPlaying(streamUrl: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Icy-MetaData", "1")
                setRequestProperty("User-Agent", "PulseRadio/1.0")
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
            }
            conn.connect()
            val metaInt = conn.getHeaderField("icy-metaint")?.toIntOrNull()
                ?: return@withContext null
            if (metaInt <= 0 || metaInt > 1_000_000) return@withContext null

            conn.inputStream.use { input ->
                // The title usually arrives in the first block; try a few in case the first is empty.
                repeat(3) {
                    if (!skipFully(input, metaInt)) return@withContext null
                    val lenByte = input.read()
                    if (lenByte < 0) return@withContext null
                    if (lenByte > 0) {
                        val meta = readFully(input, lenByte * 16) ?: return@withContext null
                        parseStreamTitle(String(meta, Charsets.UTF_8))?.let { return@withContext it }
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun skipFully(input: InputStream, count: Int): Boolean {
        var skipped = 0
        val buf = ByteArray(8192)
        while (skipped < count) {
            val n = input.read(buf, 0, minOf(buf.size, count - skipped))
            if (n < 0) return false
            skipped += n
        }
        return true
    }

    private fun readFully(input: InputStream, count: Int): ByteArray? {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(out, read, count - read)
            if (n < 0) return if (read > 0) out.copyOf(read) else null
            read += n
        }
        return out
    }

    /** Pull the title out of `StreamTitle='Artist - Song';StreamUrl='...';`. */
    private fun parseStreamTitle(meta: String): String? {
        val marker = "StreamTitle='"
        val start = meta.indexOf(marker)
        if (start < 0) return null
        val from = start + marker.length
        val end = meta.indexOf("';", from)
        val title = if (end >= 0) meta.substring(from, end) else meta.substring(from)
        return title.trim().takeIf { it.isNotBlank() }
    }
}
