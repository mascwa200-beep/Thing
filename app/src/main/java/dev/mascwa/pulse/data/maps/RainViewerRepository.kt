package dev.mascwa.pulse.data.maps

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The precipitation-radar frames RainViewer currently holds — free, keyless, worldwide.
 *
 * The index is a small JSON document listing those frames; each carries a timestamp and an opaque
 * path that changes every scan. That path is why this cannot be a constant tile template like the
 * other basemaps: a URL cached for an hour would quietly show hour-old rain, which is worse than
 * showing none.
 *
 * ⚠️ **The whole sequence is kept, not just the newest.** It used to read `past.lastOrNull()` and
 * throw the rest away, which meant the map could show where rain *is* and never whether it is
 * coming towards you or going away — the only question a rain radar is actually consulted for.
 * Measured against the live index today: **13 frames spanning two hours at ten-minute steps**, and
 * an independent probe of the tiles put the overlap between the oldest and newest at 0.37 against
 * 0.68-0.83 for neighbouring frames, so the movement across the sequence is large and real. One
 * frame of thirteen was being drawn and two hours of it discarded.
 *
 * Everything is defensive — a failure yields an empty list and the caller simply has no rain layer.
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
    private var cached: List<RadarFrame> = emptyList()
    private var cachedAtMs = 0L
    private var lastAttemptMs = 0L

    /**
     * Every observed frame RainViewer is holding, **oldest first**, or an empty list.
     *
     * [force] skips the freshness check but not the floor: RainViewer scans every ten minutes, so
     * asking more often than [MIN_INTERVAL_MS] cannot return anything new and is simply rude. The
     * floor is measured from the last *attempt*, so a run of failures backs off instead of
     * hammering.
     */
    suspend fun frames(force: Boolean = false): List<RadarFrame> = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < MIN_INTERVAL_MS && lastAttemptMs != 0L) return cached
        if (!force && cached.isNotEmpty() && now - cachedAtMs < TTL_MS) return cached
        lastAttemptMs = now
        val fresh = runCatching {
            val index = http.getJson(INDEX_URL, Index.serializer())
            val host = index.host.ifBlank { DEFAULT_HOST }
            // Observed frames only. RainViewer also publishes a forecast; labelling a prediction as
            // radar would be a lie about what the picture is.
            index.radar.past
                .filter { it.path.isNotBlank() && it.time > 0L }
                .sortedBy { it.time }
                .map { frame ->
                    RadarFrame(
                        // 512-px tiles, colour scheme 2 (the classic radar ramp), smoothed, no snow.
                        tileUrl = "$host${frame.path}/512/{z}/{x}/{y}/2/1_0.png",
                        timeEpochMs = frame.time * 1000L,
                    )
                }
        }.getOrNull().orEmpty()
        // A failed refresh keeps the previous sequence rather than blanking the layer. Each frame
        // carries its own timestamp, so an old picture still says how old it is.
        if (fresh.isNotEmpty()) {
            cached = fresh
            cachedAtMs = now
        }
        return cached
    }
    // No `latest()` convenience here. The one consumer wants the sequence, and a public method with
    // no caller is the defect this repository has corrected too many times to add another on purpose.

    private companion object {
        const val INDEX_URL = "https://api.rainviewer.com/public/weather-maps.json"
        const val DEFAULT_HOST = "https://tilecache.rainviewer.com"
        /** Frames are published every ten minutes; refresh a little inside that. */
        const val TTL_MS = 8 * 60 * 1000L
        /** Never ask more often than this, whatever the caller wants. */
        const val MIN_INTERVAL_MS = 4 * 60 * 1000L
    }
}
