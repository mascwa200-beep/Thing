package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs

/**
 * The maths behind the AR wasteland camera view — projecting a geo-located thing (a [WorldSite]) onto the
 * live camera picture using only the device's compass heading and the bearing to the thing. A "magic window"
 * projection (no true 3D anchoring / ARCore needed): as you pan the phone, a site slides across the view
 * because its bearing stays fixed while your heading changes. Pure + CI-tested; the on-device layer supplies
 * the heading (from the rotation-vector compass) and the per-site bearing (great-circle from the GPS fix).
 */
object ArProjection {

    /** Approximate horizontal field of view (degrees) of a typical phone camera in portrait. */
    const val DEFAULT_FOV_DEG = 60.0

    /**
     * The bearing of [bearingDeg] relative to where you're facing ([headingDeg]), normalized to `(-180, 180]`.
     * Negative = to your left, positive = to your right, 0 = dead ahead. Handles the 359°→1° wrap.
     */
    fun relativeBearing(headingDeg: Double, bearingDeg: Double): Double {
        var d = (bearingDeg - headingDeg) % 360.0
        if (d > 180.0) d -= 360.0
        if (d <= -180.0) d += 360.0
        return d
    }

    /** Whether something at [bearingDeg] falls within the camera's horizontal [fovDeg] given [headingDeg]. */
    fun inView(headingDeg: Double, bearingDeg: Double, fovDeg: Double = DEFAULT_FOV_DEG): Boolean =
        abs(relativeBearing(headingDeg, bearingDeg)) <= fovDeg / 2.0

    /**
     * Horizontal screen fraction for something at [bearingDeg]: `0.5` = dead ahead (centre), `0.0` = the left
     * edge of the FOV, `1.0` = the right edge. Values outside `[0,1]` are out of view — the caller skips them
     * (or uses [inView]). Linear in the relative bearing, which is a good-enough small-angle approximation.
     */
    fun screenX(headingDeg: Double, bearingDeg: Double, fovDeg: Double = DEFAULT_FOV_DEG): Double =
        0.5 + relativeBearing(headingDeg, bearingDeg) / fovDeg

    /**
     * A rough on-screen size fraction (0..1) for a marker by real-world [distanceM]: near things read bigger,
     * far things smaller, clamped so nothing vanishes or fills the screen. Not physically exact — just a
     * legible depth cue for the floating site cards.
     */
    fun sizeForDistance(distanceM: Double, nearM: Double = 30.0, farM: Double = 2000.0): Double {
        if (distanceM <= nearM) return 1.0
        if (distanceM >= farM) return 0.35
        val t = (distanceM - nearM) / (farM - nearM) // 0 at near … 1 at far
        return (1.0 - t * 0.65).coerceIn(0.35, 1.0)
    }
}
