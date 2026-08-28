package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Turning the sky into a map you can pan and zoom.
 *
 * The observatory's existing chart is a **whole-sky polar plot** — horizon at the rim, zenith at the
 * centre — which is the right shape for "what is up right now" and the wrong one for a map. It
 * cannot zoom, so everything below about magnitude 3 would be a smear; it cannot pan, because it
 * already shows everything; and it distorts savagely near the rim, which is exactly where a person
 * standing outside is looking. This is the other kind of chart: a window onto part of the sky,
 * pointed somewhere, with a field of view.
 *
 * ## Why stereographic
 *
 * ⚠️ **A gnomonic (tangent-plane) projection is the obvious choice and it is wrong here.** It maps
 * great circles to straight lines, which is lovely for a telescope's narrow field, and it sends
 * anything 90° from the centre to infinity — so a 120° field, which is roughly what somebody sweeping
 * a phone across the sky wants, tears apart at the edges. Stereographic is **conformal**: it keeps
 * shapes locally undistorted at every scale, so a constellation looks like itself whether it is in
 * the middle of the view or at the corner, and it stays finite right out to the point opposite the
 * one you are looking at. Scale grows toward the edge, which is the honest trade and the one every
 * planetarium makes.
 *
 * ## Conventions, stated once because getting one backwards is invisible
 *
 * - **Azimuth is degrees clockwise from true north**, matching [Ephemeris.Horizontal] and everything
 *   else in this app.
 * - The frame is **east-north-up**: x east, y north, z up.
 * - Screen coordinates are **+x right, +y DOWN**, because that is what a canvas wants. Turning to
 *   your right moves the sky left, so a star at a larger azimuth than the view centre sits to the
 *   right — which is what "looking at it" means, and the opposite of a paper star chart held over
 *   your head.
 * - A radius of 1.0 is the edge of the field of view. The caller multiplies by half the smaller
 *   screen dimension.
 */
object SkyProjection {

    private const val DEG = Math.PI / 180.0

    /**
     * Where the viewer is pointed and how much sky is in the window.
     *
     * @param altitudeDeg clamped away from the poles by [MAX_ALTITUDE_DEG]; see [pan].
     * @param fovDeg the FULL field across the smaller screen dimension, not the half-angle.
     */
    data class View(
        val azimuthDeg: Double,
        val altitudeDeg: Double,
        val fovDeg: Double,
    )

    /**
     * Where something landed.
     *
     * ⚠️ [visible] is about the projection, not about the screen: it is false only for a point so
     * far round the sky that it cannot be drawn at all. A point can be perfectly valid and still sit
     * outside the visible circle — the caller decides whether to clip on radius, because a label
     * just past the edge is often worth drawing and a star is not.
     */
    data class Screen(val x: Double, val y: Double, val visible: Boolean) {
        val radius: Double get() = sqrt(x * x + y * y)

        /** Inside the field of view as declared, which is what [radius] is normalised against. */
        val inField: Boolean get() = visible && radius <= 1.0
    }

    /** Nothing may be drawn closer than this to the point opposite the view — it projects to infinity. */
    private const val MIN_FORWARD = -0.999

    /**
     * ⚠️ **The look direction is clamped away from the zenith and nadir, and that is not cosmetic.**
     * The screen's "up" is derived from the look direction crossed with the world's vertical, and at
     * exactly the zenith that cross product is the zero vector — the view has no defined orientation
     * and the map would spin or vanish. Every planetarium has this seam; clamping half a degree short
     * of it means a person can look as near straight up as makes any visual difference and never
     * reach the singularity.
     */
    const val MAX_ALTITUDE_DEG = 89.5

    /** Wide enough to sweep, narrow enough to separate a double star. */
    const val MIN_FOV_DEG = 4.0
    const val MAX_FOV_DEG = 150.0

    /** Project a horizon position into the view. */
    fun project(azimuthDeg: Double, altitudeDeg: Double, view: View): Screen {
        val v = unit(azimuthDeg, altitudeDeg)
        val f = unit(view.azimuthDeg, view.altitudeDeg.coerceIn(-MAX_ALTITUDE_DEG, MAX_ALTITUDE_DEG))
        // Right is the look direction crossed with the world's vertical; up completes the frame.
        val rx = f[1]
        val ry = -f[0]
        val rn = sqrt(rx * rx + ry * ry)
        if (rn < 1e-9) return Screen(0.0, 0.0, visible = false)
        val r = doubleArrayOf(rx / rn, ry / rn, 0.0)
        val u = cross(r, f)

        val z = dot(v, f)
        if (z <= MIN_FORWARD) return Screen(0.0, 0.0, visible = false)
        val x = dot(v, r)
        val y = dot(v, u)

        // Stereographic from the antipode of the view centre.
        val k = 2.0 / (1.0 + z)
        val scale = edgeRadius(view.fovDeg)
        // Screen y grows downward, so the sign flips here and nowhere else.
        return Screen(x * k / scale, -y * k / scale, visible = true)
    }

    /**
     * A screen point back to a horizon position — what a tap is asking about.
     *
     * ⚠️ Exact, not a search. The stereographic inverse is closed-form, so a tap resolves to the
     * direction it actually points at rather than to whichever catalogued object happened to be
     * nearest in screen space, and the caller can then hit-test in **angle** where a person's finger
     * covers a fixed number of degrees regardless of the zoom.
     */
    fun unproject(x: Double, y: Double, view: View): Pair<Double, Double> {
        val scale = edgeRadius(view.fovDeg)
        val bigX = x * scale
        val bigY = -y * scale
        val s = bigX * bigX + bigY * bigY
        val z = (4.0 - s) / (4.0 + s)
        val k = (4.0 + s) / 4.0
        val px = bigX / k
        val py = bigY / k

        val f = unit(view.azimuthDeg, view.altitudeDeg.coerceIn(-MAX_ALTITUDE_DEG, MAX_ALTITUDE_DEG))
        val rx = f[1]
        val ry = -f[0]
        val rn = sqrt(rx * rx + ry * ry)
        if (rn < 1e-9) return view.azimuthDeg to view.altitudeDeg
        val r = doubleArrayOf(rx / rn, ry / rn, 0.0)
        val u = cross(r, f)

        val vx = r[0] * px + u[0] * py + f[0] * z
        val vy = r[1] * px + u[1] * py + f[1] * z
        val vz = r[2] * px + u[2] * py + f[2] * z

        val alt = Math.toDegrees(Math.asin(vz.coerceIn(-1.0, 1.0)))
        val az = norm360(Math.toDegrees(Math.atan2(vx, vy)))
        return az to alt
    }

    /**
     * Drag the view.
     *
     * ⚠️ **The horizontal step is divided by the cosine of the altitude**, so a drag near the zenith
     * does not whip the sky round. Without it, panning up feels progressively faster sideways until
     * the map is unusable at the top — the same reason a globe's meridians converge.
     */
    fun pan(view: View, dAzimuthDeg: Double, dAltitudeDeg: Double): View {
        val alt = (view.altitudeDeg + dAltitudeDeg).coerceIn(-MAX_ALTITUDE_DEG, MAX_ALTITUDE_DEG)
        val shrink = cos(view.altitudeDeg.coerceIn(-MAX_ALTITUDE_DEG, MAX_ALTITUDE_DEG) * DEG)
            .coerceAtLeast(0.05)
        return view.copy(
            azimuthDeg = norm360(view.azimuthDeg + dAzimuthDeg / shrink),
            altitudeDeg = alt,
        )
    }

    /** Pinch. A factor above 1 zooms IN, which is the direction a pinch-out gesture reports. */
    fun zoom(view: View, factor: Double): View =
        view.copy(fovDeg = (view.fovDeg / factor).coerceIn(MIN_FOV_DEG, MAX_FOV_DEG))

    /**
     * How many degrees a screen distance is worth at the centre of the view.
     *
     * The point of it is hit-testing: a fingertip is about the same number of screen units whatever
     * the zoom, and it has to become a tolerance in degrees that shrinks as you zoom in.
     */
    fun degreesPerUnit(view: View): Double = view.fovDeg / 2.0

    /**
     * How faint a star is worth drawing at this zoom.
     *
     * ⚠️ **A fixed limit is wrong at both ends.** Show everything down to magnitude 6.5 across a
     * 150° field and the bright stars vanish into eight thousand identical dots — the shapes people
     * actually navigate by stop being visible. Show only the bright ones at a 5° field and the
     * window is nearly empty. This tracks what a real eyepiece does: the narrower the field, the
     * deeper you can usefully go.
     */
    fun magnitudeLimit(fovDeg: Double, deepest: Double = 6.5): Double {
        val wide = 3.6
        val narrow = deepest
        val t = ((MAX_FOV_DEG - fovDeg) / (MAX_FOV_DEG - MIN_FOV_DEG)).coerceIn(0.0, 1.0)
        return wide + (narrow - wide) * t
    }

    /**
     * Angular separation between two horizon positions, degrees.
     *
     * ⚠️ Uses the half-angle form rather than `acos` of the dot product. At the small separations
     * that matter for hit-testing, `acos` near 1.0 loses most of its significant figures — a
     * textbook catastrophic cancellation, and it would make a tap on a star land a degree away.
     */
    fun separationDeg(az1: Double, alt1: Double, az2: Double, alt2: Double): Double {
        val a = unit(az1, alt1)
        val b = unit(az2, alt2)
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        val chord = sqrt(dx * dx + dy * dy + dz * dz)
        return Math.toDegrees(2.0 * Math.asin((chord / 2.0).coerceIn(0.0, 1.0)))
    }

    // ---- small helpers ----------------------------------------------------------------------

    /** East-north-up unit vector for a horizon position. */
    private fun unit(azimuthDeg: Double, altitudeDeg: Double): DoubleArray {
        val a = azimuthDeg * DEG
        val h = altitudeDeg * DEG
        val ch = cos(h)
        return doubleArrayOf(ch * sin(a), ch * cos(a), sin(h))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    /** Projected radius of the field's edge, before normalising to 1.0. */
    private fun edgeRadius(fovDeg: Double): Double =
        2.0 * tan((fovDeg.coerceIn(MIN_FOV_DEG, MAX_FOV_DEG) / 4.0) * DEG)

    private fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    /** True when two angles are the same direction, allowing for the wrap at 360. */
    internal fun sameAzimuth(a: Double, b: Double, toleranceDeg: Double): Boolean {
        val d = abs(norm360(a - b))
        return d <= toleranceDeg || d >= 360.0 - toleranceDeg
    }
}
