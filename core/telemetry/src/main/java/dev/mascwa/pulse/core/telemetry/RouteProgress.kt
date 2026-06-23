package dev.mascwa.pulse.core.telemetry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry for a live "distance remaining" readout along a road route. Given the route polyline and
 * the user's current position, it returns how far is left to the destination — recomputed on every GPS
 * tick without re-hitting the routing server, so the figure counts down as you drive. Kept Android-free so
 * the (fiddly) maths is unit-tested in CI.
 *
 * Approach: project the position onto the **nearest segment** (local equirectangular planar projection —
 * accurate for the short, dense segments of OSRM `overview=full` geometry), take the fraction `t` along
 * that segment, then `remaining = (1-t)·segmentLength + Σ later segments` (segment lengths via the exact
 * great-circle formula). Projecting onto the segment (not the nearest vertex) makes the readout decrease
 * monotonically as you advance.
 */
object RouteProgress {

    /** Metres remaining along [points] (lat,lon) from the projection of ([lat],[lon]); null if no route. */
    fun remainingMeters(points: List<Pair<Double, Double>>, lat: Double, lon: Double): Double? {
        if (points.size < 2) return null
        var bestDist = Double.MAX_VALUE
        var bestSeg = 0
        var bestT = 0.0
        for (i in 0 until points.size - 1) {
            val (t, d) = projectOntoSegment(lat, lon, points[i], points[i + 1])
            if (d < bestDist) {
                bestDist = d
                bestSeg = i
                bestT = t
            }
        }
        var remaining = (1.0 - bestT) * haversine(
            points[bestSeg].first, points[bestSeg].second,
            points[bestSeg + 1].first, points[bestSeg + 1].second,
        )
        for (i in bestSeg + 1 until points.size - 1) {
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

    /** Project P onto segment A→B in local planar metres; returns (fraction t in [0,1], distance to it). */
    private fun projectOntoSegment(
        plat: Double, plon: Double, a: Pair<Double, Double>, b: Pair<Double, Double>,
    ): Pair<Double, Double> {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(a.first))
        val ax = a.second * mPerDegLon; val ay = a.first * mPerDegLat
        val bx = b.second * mPerDegLon; val by = b.first * mPerDegLat
        val px = plon * mPerDegLon; val py = plat * mPerDegLat
        val abx = bx - ax; val aby = by - ay
        val denom = abx * abx + aby * aby
        val t = if (denom <= 0.0) 0.0 else (((px - ax) * abx + (py - ay) * aby) / denom).coerceIn(0.0, 1.0)
        val dx = px - (ax + t * abx); val dy = py - (ay + t * aby)
        return t to sqrt(dx * dx + dy * dy)
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
