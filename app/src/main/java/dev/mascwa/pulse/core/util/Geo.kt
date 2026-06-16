package dev.mascwa.pulse.core.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle geometry helpers shared by compass + nearest-places features. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Distance in metres between two lat/lon points (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing (degrees, 0..360, true north) from point 1 to point 2. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** "1.2 km" / "840 m" / "12.4 km". */
    fun formatDistance(meters: Double, metric: Boolean = true): String {
        return if (metric) {
            if (meters < 1000) "${meters.roundToInt()} m"
            else "${"%.1f".format(meters / 1000)} km"
        } else {
            val miles = meters / 1609.344
            if (miles < 0.1) "${(meters / 0.3048).roundToInt()} ft"
            else "${"%.1f".format(miles)} mi"
        }
    }

    /** 16-point compass label for a bearing in degrees. */
    fun cardinal(bearing: Double): String {
        val dirs = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val idx = (((bearing % 360) + 360) % 360 / 22.5).roundToInt() % 16
        return dirs[idx]
    }
}
