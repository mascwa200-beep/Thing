package dev.mascwa.pulse.core.telemetry

import kotlin.math.cos

/**
 * Projects **real OSM building footprints** into the AR wasteland's local metric frame so the renderer can
 * draw each building as a wireframe at its true position, shape and scale relative to the player — the "exact
 * physical recorded shape of the exact buildings it was scanning for". Pure + deterministic so CI gates it;
 * the network fetch (Overpass) and the Filament geometry live in the app layer.
 *
 * Frame convention matches [WastelandRenderer]'s compass camera: **x = east metres, z = −north metres**
 * (heading 0 / north looks toward −Z), origin at the player's current GPS fix. A local equirectangular
 * projection is exact enough at the ≤ few-hundred-metre scale of a visible city block.
 */

/** One WGS84 point (a footprint vertex, or the player origin). */
data class GeoPoint(val lat: Double, val lon: Double)

/** A real building outline (WGS84 ring) plus an estimated height in metres. */
data class BuildingFootprint(val outline: List<GeoPoint>, val heightM: Float)

/** A footprint vertex in the local AR frame (metres): x = east, z = −north, relative to the player. */
data class LocalPoint(val x: Float, val z: Float)

/** A footprint projected into the local AR frame, ready to extrude to [heightM]. */
data class LocalFootprint(val points: List<LocalPoint>, val heightM: Float)

object BuildingFootprints {
    /** Metres per degree of latitude (WGS84 mean); longitude shrinks by cos(lat). */
    const val M_PER_DEG_LAT = 111_320.0

    /** A sane height envelope so a garbled tag can't produce a 10 km spike or a zero-height sliver. */
    private const val MIN_HEIGHT = 2f
    private const val MAX_HEIGHT = 120f
    const val DEFAULT_HEIGHT = 6f          // ~2 storeys when a building has no height/levels tag
    private const val METRES_PER_LEVEL = 3f

    fun metersPerDegLon(lat: Double): Double = M_PER_DEG_LAT * cos(Math.toRadians(lat))

    /** Project one WGS84 point to the local AR frame (metres) relative to the player origin. */
    fun toLocal(originLat: Double, originLon: Double, lat: Double, lon: Double): LocalPoint {
        val east = (lon - originLon) * metersPerDegLon(originLat)
        val north = (lat - originLat) * M_PER_DEG_LAT
        return LocalPoint(east.toFloat(), (-north).toFloat())
    }

    /**
     * Project every footprint's ring around the player at ([originLat], [originLon]). Rings with < 2 points
     * are dropped (nothing to draw); a repeated closing vertex (OSM closes rings by repeating the first node)
     * is trimmed so it doesn't emit a zero-length edge.
     */
    fun project(originLat: Double, originLon: Double, footprints: List<BuildingFootprint>): List<LocalFootprint> =
        footprints.mapNotNull { fp ->
            val ring = fp.outline.let { if (it.size >= 2 && it.first() == it.last()) it.dropLast(1) else it }
            if (ring.size < 2) return@mapNotNull null
            LocalFootprint(ring.map { toLocal(originLat, originLon, it.lat, it.lon) }, fp.heightM)
        }

    /**
     * Estimate a building's height from OSM tags: an explicit `height` (metres, tolerating a "12 m" suffix)
     * wins, else `building:levels` × 3 m, else [DEFAULT_HEIGHT]. Always clamped to [MIN_HEIGHT]..[MAX_HEIGHT].
     */
    fun estimateHeight(heightTag: String?, levelsTag: String?, default: Float = DEFAULT_HEIGHT): Float {
        heightTag?.trim()?.takeWhile { it.isDigit() || it == '.' }?.toFloatOrNull()?.let { v ->
            if (v > 0f) return v.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        }
        levelsTag?.trim()?.toFloatOrNull()?.let { lv ->
            if (lv > 0f) return (lv * METRES_PER_LEVEL).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        }
        return default.coerceIn(MIN_HEIGHT, MAX_HEIGHT)
    }
}
