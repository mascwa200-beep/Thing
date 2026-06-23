package dev.mascwa.pulse.data.places

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.serialization.Serializable

/**
 * Road-snapped routing via the free, keyless **OSRM** demo server (driving profile). Returns the route
 * geometry as a list of (lat, lon) points that follow streets — for the NAV map's navigation path.
 * Defensive: any failure (offline, rate-limit, no route) yields null so the caller falls back to a
 * straight line.
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

    /** Driving route from (fromLat,fromLon) → (toLat,toLon), or null on failure. */
    suspend fun route(
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
}
