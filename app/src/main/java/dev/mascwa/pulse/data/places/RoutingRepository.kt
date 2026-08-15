package dev.mascwa.pulse.data.places

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Road-snapped routing via the free, keyless **OSRM** demo server (driving profile). Returns the route
 * geometry as a list of (lat, lon) points that follow streets — for the NAV map's navigation path.
 * Defensive: any failure (offline, rate-limit, no route) yields null so the caller falls back to a
 * straight line.
 *
 * The demo server is community-hosted and asks for light use, which shapes everything below: one
 * gate for the host, an in-memory cache so an unchanged request is not re-asked, and — the one that
 * actually matters — a short negative cache. The map only records its route origin on *success*, so
 * while OSRM is down every GPS tick looked like a fresh journey and fired another request. A failure
 * now costs one request a minute rather than one per tick.
 */
class RoutingRepository(private val http: HttpClient) {

    @Serializable
    private data class OsrmResponse(val routes: List<OsrmRoute> = emptyList())

    @Serializable
    private data class OsrmRoute(
        val geometry: OsrmGeometry = OsrmGeometry(),
        val distance: Double = 0.0,   // metres along the road
        val duration: Double = 0.0,   // seconds (driving)
    )

    @Serializable
    private data class OsrmGeometry(val coordinates: List<List<Double>> = emptyList())

    /** A road-snapped route: the street-following geometry plus its driving distance + duration. */
    data class RoadRoute(
        val points: List<Pair<Double, Double>>,
        val distanceMeters: Double,
        val durationSeconds: Double,
    )

    private data class Entry(val route: RoadRoute?, val atMs: Long)

    private val mutex = Mutex()
    /** Access-ordered so eviction drops the least recently used rather than the oldest inserted. */
    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>): Boolean = size > MAX_ENTRIES
    }

    /**
     * Driving route from (fromLat,fromLon) → (toLat,toLon), or null on failure.
     *
     * Never throws. The doc above has always promised that; previously it was true only because the
     * single caller happened to wrap the call, which is not a contract a second caller could rely on.
     */
    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): RoadRoute? {
        // ~11 m of granularity. The map re-routes after 60 m of movement, so a real journey still
        // gets fresh geometry while a stationary phone stops re-asking the same question.
        val key = "${k(fromLat)},${k(fromLon)}>${k(toLat)},${k(toLon)}"
        val now = System.currentTimeMillis()

        mutex.withLock {
            cache[key]?.let { hit ->
                val ttl = if (hit.route != null) TTL_MS else FAILURE_TTL_MS
                if (now - hit.atMs < ttl) return hit.route
            }
        }

        val result = runCatching { gate.withPermit { fetch(fromLat, fromLon, toLat, toLon) } }.getOrNull()
        mutex.withLock { cache[key] = Entry(result, System.currentTimeMillis()) }
        return result
    }

    private suspend fun fetch(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): RoadRoute? {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
        val resp = http.getJson(url, OsrmResponse.serializer())
        val osrm = resp.routes.firstOrNull() ?: return null
        // GeoJSON coordinates are [lon, lat]; emit (lat, lon). Need at least two points to draw a line.
        val path = osrm.geometry.coordinates.mapNotNull { c -> if (c.size >= 2) c[1] to c[0] else null }
        return path.takeIf { it.size >= 2 }?.let { RoadRoute(it, osrm.distance, osrm.duration) }
    }

    /** Locale.US: these are coordinates, and a comma decimal would split one place into two keys. */
    private fun k(v: Double): String = String.format(Locale.US, "%.4f", v)

    private companion object {
        /** Roads do not move. This only needs to outlive a stationary phone's GPS chatter. */
        const val TTL_MS = 10 * 60 * 1000L

        /** A failure is usually transient, so it is remembered only long enough to stop a storm. */
        const val FAILURE_TTL_MS = 60 * 1000L

        const val MAX_ENTRIES = 24

        /** One gate per host, as everywhere else. */
        val gate = Semaphore(1)
    }
}
