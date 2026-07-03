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

    /** Approximate vertical field of view (degrees) in portrait — the long screen axis, so wider than [DEFAULT_FOV_DEG]. */
    const val DEFAULT_VFOV_DEG = 74.0

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
     * Vertical screen fraction for a target at elevation [targetElevDeg] (≈ 0 for a far, ground-level site)
     * given the camera's [pitchDeg] (0 = level, positive = tilted up): `0.5` = the horizon at centre, `0.0` =
     * the top edge, `1.0` = the bottom edge. Tilting UP slides ground targets DOWN the frame (toward 1);
     * tilting DOWN slides them UP (toward 0) — the parallax that makes markers feel pinned to the world.
     * This is what fixes "tilting made things drift sideways": the heading no longer absorbs the tilt.
     */
    fun screenY(pitchDeg: Double, targetElevDeg: Double = 0.0, vFovDeg: Double = DEFAULT_VFOV_DEG): Double =
        0.5 + (pitchDeg - targetElevDeg) / vFovDeg

    /** Whether a target at [targetElevDeg] falls within the vertical [vFovDeg] given the camera [pitchDeg]. */
    fun inViewVertical(pitchDeg: Double, targetElevDeg: Double = 0.0, vFovDeg: Double = DEFAULT_VFOV_DEG): Boolean =
        abs(pitchDeg - targetElevDeg) <= vFovDeg / 2.0

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
