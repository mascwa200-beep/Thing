// MIRROR OF app/src/main/java/dev/mascwa/pulse/data/live/LiveCatalogRepository.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.live

import dev.mascwa.pulse.desktop.cache.DiskCache
import dev.mascwa.pulse.desktop.network.HttpClient
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.desktop.telemetry.M3uCatalog
import kotlinx.serialization.builtins.serializer

/**
 * The opt-in community channel catalogue.
 *
 * ⚠️ **The raw playlist is what gets cached, not the parsed channels.** `LiveChannel` lives in
 * `core:telemetry`, which carries no serialization dependency by design, so it cannot be written to
 * the cache — and that turns out to be the better shape anyway: the parse is deterministic and
 * cheap, the cached artifact stays readable, and a change to [M3uCatalog]'s rules takes effect
 * without having to invalidate anything.
 *
 * Fetched rarely. The file is ~215 KB and the catalogue changes over weeks, so the miss is a week
 * old at worst and the reader is told none of it is verified regardless.
 */
class LiveCatalogRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {

    private companion object {
        const val KEY = "live_catalogue_news"
    }

    /**
     * The community channels, newest cached copy or a fresh fetch.
     *
     * Falls back to a stale copy when the network fails, and to an empty list when there is nothing
     * at all — an empty list simply means the rail shows the curated channels, which is the same
     * thing the switch being off does. There is no failure here worth interrupting anyone about.
     */
    suspend fun channels(force: Boolean = false): List<LiveChannel> {
        if (!force) {
            cache.read(KEY, M3uCatalog.MAX_AGE_MS, String.serializer())?.let {
                return M3uCatalog.parse(it.value)
            }
        }
        val fetched = runCatching { http.getString(M3uCatalog.NEWS_URL) }.getOrNull()
        if (fetched == null) {
            // Stale beats nothing: a week-old channel list is very nearly the same list.
            val stale = cache.readAny(KEY, String.serializer()) ?: return emptyList()
            return M3uCatalog.parse(stale.value)
        }
        val parsed = M3uCatalog.parse(fetched)
        // Only bank a response that actually yielded channels. A truncated or redirected body parses
        // to nothing, and caching that would hide the real list for a week.
        if (parsed.isNotEmpty()) runCatching { cache.write(KEY, fetched, String.serializer()) }
        return parsed
    }
}
