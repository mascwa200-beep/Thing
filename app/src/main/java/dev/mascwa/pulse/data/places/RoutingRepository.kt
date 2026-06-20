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
    private data class OsrmRoute(val geometry: OsrmGeometry = OsrmGeometry())

    @Serializable
    private data class OsrmGeometry(val coordinates: List<List<Double>> = emptyList())

    /** Driving route from (fromLat,fromLon) → (toLat,toLon) as (lat,lon) points, or null on failure. */
    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
    ): List<Pair<Double, Double>>? {
        val url = "https://router.project-osrm.org/route/v1/driving/" +
            "$fromLon,$fromLat;$toLon,$toLat?overview=full&geometries=geojson"
        val resp = http.getJson(url, OsrmResponse.serializer())
        val coords = resp.routes.firstOrNull()?.geometry?.coordinates ?: return null
        // GeoJSON coordinates are [lon, lat]; emit (lat, lon). Need at least two points to draw a line.
        val path = coords.mapNotNull { c -> if (c.size >= 2) c[1] to c[0] else null }
        return path.takeIf { it.size >= 2 }
    }
}
