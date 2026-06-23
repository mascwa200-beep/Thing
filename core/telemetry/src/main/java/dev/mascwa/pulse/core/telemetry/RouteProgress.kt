package dev.mascwa.pulse.core.telemetry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry for a live "distance remaining" readout along a road route. Given the route polyline and
 * the user's current position, it returns how far is left to the destination — recomputed on every GPS
 * tick without re-hitting the routing server, so the figure counts down as you drive. Kept Android-free so
 * the (fiddly) great-circle maths is unit-tested in CI.
 *
 * Approach: snap to the nearest polyline vertex, then sum the segment lengths from there to the end (plus
 * the hop from the user to that vertex). OSRM `overview=full` geometry is dense, so the per-vertex
 * approximation error is small — good enough for a glanceable readout, and robust/stable as you move.
 */
object RouteProgress {

    /** Metres remaining along [points] (lat,lon) from the position nearest ([lat],[lon]); null if no route. */
    fun remainingMeters(points: List<Pair<Double, Double>>, lat: Double, lon: Double): Double? {
        if (points.size < 2) return null
        var nearest = 0
        var best = Double.MAX_VALUE
        for (i in points.indices) {
            val d = haversine(lat, lon, points[i].first, points[i].second)
            if (d < best) {
                best = d
                nearest = i
            }
        }
        var remaining = best
        for (i in nearest until points.size - 1) {
            remaining += haversine(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
        }
        return remaining
    }

    /** Total length of the polyline in metres (0 for < 2 points). */
    fun totalMeters(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 0 until points.size - 1) {
            total += haversine(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
        }
        return total
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
