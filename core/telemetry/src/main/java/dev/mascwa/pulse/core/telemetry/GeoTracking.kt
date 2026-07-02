package dev.mascwa.pulse.core.telemetry

import kotlin.math.floor

/**
 * Pure real-time tracking maths that the on-device layer feeds from live GPS fixes: **elevation climbed**
 * (accumulated positive altitude change, above a noise floor so GPS altitude jitter doesn't inflate it) and
 * **exploration cells** (a coarse grid id for a coordinate, so counting distinct cells ≈ how much ground
 * you've actually explored). Privacy: the cell id is a ~111 m grid bucket — a coarse aggregate, on-device
 * only, never a precise path. CI-tested; no Android types.
 */
object GeoTracking {

    /** Altitude change (m) below this is treated as sensor wobble, not real climbing. */
    const val ELEVATION_NOISE_M = 3.0

    /**
     * The positive climb between two altitude fixes (metres, floored to an Int), or 0 for a descent / flat /
     * missing data / a change under [ELEVATION_NOISE_M]. Only ascent counts (you climb 100 m, you climbed 100 m
     * — coming back down doesn't subtract it).
     */
    fun elevationGain(prevAltM: Double?, newAltM: Double?): Int {
        if (prevAltM == null || newAltM == null) return 0
        val d = newAltM - prevAltM
        return if (d >= ELEVATION_NOISE_M) floor(d).toInt() else 0
    }

    /** Grid resolution: 3 decimal degrees ≈ a ~111 m cell (coarse enough to be an aggregate, not a trace). */
    const val CELL_SCALE = 1000.0

    /**
     * A stable, coarse grid-cell id for a coordinate — a ~111 m bucket. Counting the DISTINCT cells you enter
     * measures exploration without keeping a precise path. Uses floor so a cell covers a fixed [lat,lat+res)
     * × [lon,lon+res) square deterministically (negative coords included).
     */
    fun cellId(lat: Double, lon: Double): String {
        val la = floor(lat * CELL_SCALE).toLong()
        val lo = floor(lon * CELL_SCALE).toLong()
        return "${la}_$lo"
    }
}
