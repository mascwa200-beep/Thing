package dev.mascwa.pulse.data.maps

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The current precipitation-radar frame from **RainViewer** — free, keyless, worldwide.
 *
 * The index is a small JSON document listing the frames RainViewer currently holds; each carries a
 * timestamp and an opaque path that changes every scan. That path is why this cannot be a constant
 * tile template like the other basemaps: a URL cached for an hour would quietly show hour-old rain,
 * which is worse than showing none.
 *
 * Everything is defensive — a failure yields null and the caller simply has no rain layer.
 */
class RainViewerRepository(private val http: HttpClient) {

    @Serializable
    private data class Index(val host: String = "", val radar: Radar = Radar())

    @Serializable
    private data class Radar(val past: List<Frame> = emptyList(), val nowcast: List<Frame> = emptyList())

    @Serializable
    private data class Frame(val time: Long = 0L, val path: String = "")

    /** One radar frame: the tile template to hand MapLibre, and when the scan was taken. */
    data class RadarFrame(val tileUrl: String, val timeEpochMs: Long)

    private val mutex = Mutex()
    private var cached: RadarFrame? = null
    private var cachedAtMs = 0L
    private var lastAttemptMs = 0L

    /**
     * The most recent observed frame, or null.
     *
     * [force] skips the freshness check but not the floor: RainViewer scans every ten minutes, so
     * asking more often than [MIN_INTERVAL_MS] cannot return anything new and is simply rude. The
     * floor is measured from the last *attempt*, so a run of failures backs off instead of
     * hammering.
     */
    suspend fun latest(force: Boolean = false): RadarFrame? = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < MIN_INTERVAL_MS && lastAttemptMs != 0L) return cached
        if (!force && cached != null && now - cachedAtMs < TTL_MS) return cached
        lastAttemptMs = now
        val fresh = runCatching {
            val index = http.getJson(INDEX_URL, Index.serializer())
            // Observed frames only. RainViewer also publishes a forecast; labelling a prediction as
            // radar would be a lie about what the picture is.
            val frame = index.radar.past.lastOrNull { it.path.isNotBlank() } ?: return@runCatching null
            val host = index.host.ifBlank { DEFAULT_HOST }
            RadarFrame(
                // 512-px tiles, colour scheme 2 (the classic radar ramp), smoothed, no snow shading.
                tileUrl = "$host${frame.path}/512/{z}/{x}/{y}/2/1_0.png",
                timeEpochMs = frame.time * 1000L,
            )
        }.getOrNull()
        // A failed refresh keeps the previous frame rather than blanking the layer. The frame
        // carries its own timestamp, so an old picture still says how old it is.
        if (fresh != null) {
            cached = fresh
            cachedAtMs = now
        }
        return cached
    }

    private companion object {
        const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"
        const val DEFAULT_HOST = "https://tilecache.rainviewer.com"
        /** Frames are published every ten minutes; refresh a little inside that. */
        const val TTL_MS = 8 * 60 * 1000L
        /** Never ask more often than this, whatever the caller wants. */
        const val MIN_INTERVAL_MS = 4 * 60 * 1000L
    }
}
