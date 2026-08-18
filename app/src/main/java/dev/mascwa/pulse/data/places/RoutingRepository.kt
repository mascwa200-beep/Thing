package dev.mascwa.pulse.data.places

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.RouteReach
import dev.mascwa.pulse.core.telemetry.RouteSteps
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
    private data class OsrmResponse(
        val routes: List<OsrmRoute> = emptyList(),
        /**
         * Where OSRM actually started and finished, which is not where you asked.
         *
         * Present on every response and never parsed until now. It is the only thing that
         * distinguishes a real route from a confident route to somewhere else entirely — see
         * [RouteReach].
         */
        val waypoints: List<OsrmWaypoint> = emptyList(),
    )

    @Serializable
    private data class OsrmWaypoint(
        /** How far the requested coordinate had to move to reach the road network, in metres. */
        val distance: Double = 0.0,
    )

    @Serializable
    private data class OsrmRoute(
        val geometry: OsrmGeometry = OsrmGeometry(),
        val distance: Double = 0.0,   // metres along the road
        val duration: Double = 0.0,   // seconds (driving)
        /**
         * The turns, one list per pair of waypoints.
         *
         * Empty unless the request asks for `steps=true`, which it did not until now — so the map
         * had a road-snapped line to draw and no idea what any of it meant.
         */
        val legs: List<OsrmLeg> = emptyList(),
    )

    @Serializable
    private data class OsrmLeg(val steps: List<OsrmStep> = emptyList())

    @Serializable
    private data class OsrmStep(
        val maneuver: OsrmManeuver = OsrmManeuver(),
        /** The road being joined. Blank on an unnamed road, which is common and not an error. */
        val name: String = "",
        /** The road's designation, e.g. "A4" — present on some steps only. */
        val ref: String = "",
        /** Length of this step: the ground covered before the *next* manoeuvre. */
        val distance: Double = 0.0,
    )

    @Serializable
    private data class OsrmManeuver(
        val type: String = "",
        val modifier: String = "",
        /** `[lon, lat]`, GeoJSON order. */
        val location: List<Double> = emptyList(),
        val bearing_after: Double = 0.0,
        /** Which exit, on a roundabout or rotary. Absent everywhere else. */
        val exit: Int? = null,
    )

    @Serializable
    private data class OsrmGeometry(val coordinates: List<List<Double>> = emptyList())

    /** A road-snapped route: the street-following geometry plus its driving distance + duration. */
    data class RoadRoute(
        val points: List<Pair<Double, Double>>,
        val distanceMeters: Double,
        val durationSeconds: Double,
        /**
         * How far the *destination* had to move to reach a road, or null if the server did not say.
         *
         * Only the destination end is kept. The origin snap is the user's own GPS meeting the kerb
         * and is never interesting; the destination snap is the whole question.
         */
        val destinationSnapMeters: Double? = null,
        /** The turns along the way, in order. Empty when the server sent none. */
        val steps: List<RouteSteps.Step> = emptyList(),
    ) {
        /** Whether this route goes where it was asked to, and what to say if not. */
        val reach: RouteReach.Reach
            get() = RouteReach.classify(destinationSnapMeters, distanceMeters)

        /** True when the distance and ETA may be shown as reaching the destination. */
        val reachesDestination: Boolean get() = RouteReach.trustworthy(reach)
    }

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
            "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson&steps=true"
        val resp = http.getJson(url, OsrmResponse.serializer())
        val osrm = resp.routes.firstOrNull() ?: return null
        // GeoJSON coordinates are [lon, lat]; emit (lat, lon). Need at least two points to draw a line.
        val path = osrm.geometry.coordinates.mapNotNull { c -> if (c.size >= 2) c[1] to c[0] else null }
        // The destination is the LAST waypoint. Null when the server omitted them, which RouteReach
        // reads as "no claim either way" rather than as an all-clear.
        val snap = resp.waypoints.lastOrNull()?.distance?.takeIf { resp.waypoints.size >= 2 }
        // One leg per pair of waypoints. Only one is ever requested, but flattening is what the
        // shape means rather than what today's request happens to produce.
        val steps = osrm.legs.flatMap { leg -> leg.steps }.map { it.toStep() }
        return path.takeIf { it.size >= 2 }
            ?.let { RoadRoute(it, osrm.distance, osrm.duration, snap, steps) }
    }

    private fun OsrmStep.toStep(): RouteSteps.Step = RouteSteps.Step(
        type = maneuver.type,
        modifier = maneuver.modifier,
        name = name,
        ref = ref,
        // GeoJSON order again: [lon, lat].
        latitude = maneuver.location.getOrNull(1) ?: 0.0,
        longitude = maneuver.location.getOrNull(0) ?: 0.0,
        bearingAfterDeg = maneuver.bearing_after,
        distanceMeters = distance,
        exit = maneuver.exit,
    )

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
