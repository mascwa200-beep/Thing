package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turning "where the phone is pointed" into "where the map looks".
 *
 * The handset already knows its own attitude — `CompassController`
 * reads the fused rotation-vector sensor and remaps it for a phone held like a viewfinder. What was
 * missing is the arithmetic that turns that into a view, and the two things it is easy to get
 * backwards: which way the sky turns when you tip the handset, and what happens when somebody points
 * it straight up.
 *
 * ## ⚠️ Vectors, not three angles, and that is what removes the seam
 *
 * The obvious shape is to hand [SkyProjection.View] an azimuth, an altitude and a roll. It works
 * everywhere except overhead, and overhead is exactly where somebody using this will point:
 *
 * - `SkyFrame` builds its basis as `forward × up` with **up = the
 *   observer's zenith**. Aim at the zenith and `forward` IS `up`, the cross product is the zero
 *   vector, `Basis.usable` goes false and **the map draws nothing at all**. Dragging cannot reach
 *   that — `SkyProjection.pan` clamps to [SkyProjection.MAX_ALTITUDE_DEG] — but a hand can.
 * - Azimuth is ill-conditioned near the pole regardless: a centimetre of hand movement swings it
 *   through tens of degrees, so anything smoothing azimuth as a number whips the picture round.
 *
 * So this core carries the attitude as **two orthonormal directions** — where the camera looks, and
 * which way is up the screen — and hands both to
 * [SkyProjection.basisOf]. The screen-up is perpendicular to the look direction by construction, so
 * the cross product can never vanish, there is nothing to clamp, and **the roll needs no sign at
 * all**: it is already in the up vector. [equivalentView] exists only to cross-check that against
 * the angle form, and `SkyPointingTest` pins the two together.
 *
 * ## ⚠️ The roll sign, measured rather than reasoned about
 *
 * `org.robolectric:android-all` carries real bodies for `SensorManager`'s static orientation maths,
 * so the whole sensor path — rotation vector, `remapCoordinateSystem`, `getOrientation` — can be
 * simulated off-device. Run over 36 attitudes spanning every quadrant, altitudes from −30° to +70°
 * and rolls from −90° through 170°, and compared against the picture computed straight from the
 * attitude matrix with no angles in between (`scratchpad/sky/PointProbe.kt`):
 *
 * ```
 * rollDeg = -orientation[2]   worst disagreement 2.4e-07 screen units
 * rollDeg = +orientation[2]   worst disagreement 2.98      screen units
 * ```
 *
 * The same run confirms `azimuthDeg = orientation[0]` and `altitudeDeg = -orientation[1]`, which is
 * what `CompassController` already assumed for its pitch. What stays device-only is the **feel** —
 * how stiff the filter should be, and how far a real magnetometer is off in a real hand.
 */
object SkyPointing {

    private const val DEG = Math.PI / 180.0

    /**
     * Where the handset is aimed, in this app's own conventions.
     *
     * @param azimuthDeg degrees clockwise from true north, matching [Ephemeris.Horizontal].
     * @param altitudeDeg degrees above the horizon, positive up. **Not clamped** — the vector path
     *   is defined at the zenith and the whole point is that it does not need to be.
     * @param rollDeg how far the handset is tipped in its own plane, positive when the TOP of the
     *   phone goes to the right.
     */
    data class Attitude(
        val azimuthDeg: Double,
        val altitudeDeg: Double,
        val rollDeg: Double,
    )

    /**
     * The attitude from what `SensorManager.getOrientation` reports, in degrees, for a matrix
     * remapped with `remapCoordinateSystem(r, AXIS_X, AXIS_Z, out)` — the camera-upright remap
     * `CompassController` already applies.
     *
     * ⚠️ **Both negations are measured, not assumed** — see the class note. Doing them here rather
     * than at the sensor is the point: this module has tests and the sensor callback does not.
     */
    fun fromDeviceOrientation(azimuthDeg: Double, pitchDeg: Double, rollDeg: Double): Attitude =
        Attitude(azimuthDeg = azimuthDeg, altitudeDeg = -pitchDeg, rollDeg = -rollDeg)

    /**
     * Where the camera looks, as an east/north/up unit vector.
     *
     * The same arithmetic [SkyProjection] uses for a view centre, so a pointed map and a dragged one
     * cannot disagree about what an azimuth means.
     */
    fun forward(a: Attitude, out: DoubleArray) {
        val az = a.azimuthDeg * DEG
        val alt = a.altitudeDeg * DEG
        val c = cos(alt)
        out[0] = c * sin(az)
        out[1] = c * cos(az)
        out[2] = sin(alt)
    }

    /**
     * Which way is up the screen, as an east/north/up unit vector.
     *
     * ⚠️ **With no roll this is simply the look direction a quarter-turn higher**, which is worth
     * stating because it is the reason there is no singularity: the component of the world vertical
     * across the look direction, normalised, works out to exactly `unit(azimuth, altitude + 90°)`.
     * That is defined at every altitude including the zenith, where it correctly says that which way
     * the top of the handset points is decided by the azimuth.
     *
     * Roll then turns it toward the screen's right, which is `forward × up`.
     */
    fun screenUp(a: Attitude, out: DoubleArray) {
        val az = a.azimuthDeg * DEG
        val alt = a.altitudeDeg * DEG
        // unit(az, alt + 90).
        val s = sin(alt)
        val ux = -s * sin(az)
        val uy = -s * cos(az)
        val uz = cos(alt)
        val roll = a.rollDeg * DEG
        if (roll == 0.0) {
            out[0] = ux; out[1] = uy; out[2] = uz
            return
        }
        // right = forward x up, with forward written out rather than recomputed.
        val c = cos(alt)
        val fx = c * sin(az)
        val fy = c * cos(az)
        val fz = s
        val rx = fy * uz - fz * uy
        val ry = fz * ux - fx * uz
        val rz = fx * uy - fy * ux
        val cr = cos(roll)
        val sr = sin(roll)
        out[0] = ux * cr + rx * sr
        out[1] = uy * cr + ry * sr
        out[2] = uz * cr + rz * sr
    }

    /**
     * The angle form of the same attitude, for the paths that still speak in a [SkyProjection.View].
     *
     * ⚠️ **The altitude is clamped here and NOT in [forward]**, because a `View` is fed through a
     * basis whose up is the observer's zenith, where the pole is a genuine singularity. That
     * difference is the whole argument for the vector path and is asserted by the test rather than
     * left as a remark.
     */
    fun equivalentView(a: Attitude, fovDeg: Double): SkyProjection.View = SkyProjection.View(
        azimuthDeg = a.azimuthDeg,
        altitudeDeg = a.altitudeDeg.coerceIn(
            -SkyProjection.MAX_ALTITUDE_DEG,
            SkyProjection.MAX_ALTITUDE_DEG,
        ),
        fovDeg = fovDeg,
        // The screen turns the opposite way to the handset: tip the top to the right and the sky
        // swings anticlockwise across the glass.
        rollDeg = -a.rollDeg,
    )

    /** Degrees above the horizon of an east/north/up unit vector. */
    fun altitudeOf(v: DoubleArray): Double = asin(v[2].coerceIn(-1.0, 1.0)) / DEG

    /**
     * Degrees clockwise from north of an east/north/up vector, in `[0, 360)`.
     *
     * Straight up or straight down has no azimuth and answers zero rather than whatever `atan2` of
     * two zeros happens to be — a caller asking this of the zenith has already lost the information,
     * and a made-up direction is worse than an obvious one.
     */
    fun azimuthOf(v: DoubleArray): Double {
        if (abs(v[0]) < 1e-12 && abs(v[1]) < 1e-12) return 0.0
        val deg = atan2(v[0], v[1]) / DEG
        return if (deg < 0.0) deg + 360.0 else deg
    }

    /**
     * Blend an attitude toward a new reading, as directions rather than as angles.
     *
     * ⚠️ **Smoothing three Euler angles separately is right for a compass rose and wrong here.** A
     * compass is read with the phone roughly level, where azimuth is well-conditioned; a planetarium
     * is read pointing anywhere, and near the zenith azimuth swings through tens of degrees for a
     * centimetre of hand movement. Averaging that number makes the picture whip round exactly when
     * somebody is trying to hold still on something overhead. Blending the two directions has no
     * such failure: the result is re-orthonormalised, so it is a valid attitude at every step.
     *
     * @param alpha weight of the new reading, 0 (frozen) to 1 (no smoothing at all).
     */
    fun smooth(
        prevForward: DoubleArray,
        prevUp: DoubleArray,
        next: Attitude,
        alpha: Double,
        outForward: DoubleArray,
        outUp: DoubleArray,
    ) {
        // The fresh reading is written first and is what survives if the blend turns out degenerate,
        // so the caller always ends up holding a valid attitude.
        forward(next, outForward)
        screenUp(next, outUp)
        val w = alpha.coerceIn(0.0, 1.0)
        if (w >= 1.0) return

        var fx = prevForward[0] * (1 - w) + outForward[0] * w
        var fy = prevForward[1] * (1 - w) + outForward[1] * w
        var fz = prevForward[2] * (1 - w) + outForward[2] * w
        var n = sqrt(fx * fx + fy * fy + fz * fz)
        // ⚠️ Two look directions exactly opposite blend to nothing. At any real frame rate that is a
        // sensor glitch rather than a hand movement, so the newest reading is the better answer —
        // and a normalised zero vector would be an unusable basis, which is a BLANK MAP.
        if (n < DEGENERATE) return
        fx /= n; fy /= n; fz /= n

        var ux = prevUp[0] * (1 - w) + outUp[0] * w
        var uy = prevUp[1] * (1 - w) + outUp[1] * w
        var uz = prevUp[2] * (1 - w) + outUp[2] * w
        // ⚠️ A blend of two valid attitudes is NOT itself one — the pair drifts out of square, and
        // feeding that to a basis is not a crash but a picture very slightly sheared, which nothing
        // would ever report. Take off whatever now lies along the look direction.
        val d = ux * fx + uy * fy + uz * fz
        ux -= d * fx; uy -= d * fy; uz -= d * fz
        n = sqrt(ux * ux + uy * uy + uz * uz)
        // Same argument one axis over: a half-turn of the handset between two frames leaves nothing
        // to normalise, and the fresh reading already in `outUp` is what should stand.
        if (n < DEGENERATE) return

        outForward[0] = fx; outForward[1] = fy; outForward[2] = fz
        outUp[0] = ux / n; outUp[1] = uy / n; outUp[2] = uz / n
    }

    /**
     * Below this a blended direction carries no usable bearing and the fresh reading is kept.
     *
     * Not merely guarding a division: the length is the cosine of half the angle turned between two
     * frames, so 1e-6 is about a ten-thousandth of a degree short of an exact half-turn.
     */
    private const val DEGENERATE = 1e-6

    /**
     * Turn the handset's reported azimuth by a hand-set correction.
     *
     * ⚠️ **This exists because a phone magnetometer is good to a few degrees at best**, and no amount
     * of arithmetic fixes that — the field it measures is genuinely disturbed by whatever steel and
     * current happens to be nearby. Every serious sky app offers the same thing: drag until a star
     * you can see sits where the map draws it, and the offset sticks. Saying so is better than
     * implying the sensor is more honest than it is.
     */
    fun trimmed(a: Attitude, trimDeg: Double): Attitude =
        a.copy(azimuthDeg = wrap360(a.azimuthDeg + trimDeg))

    /** Accumulate a drag into the standing correction, kept in `[0, 360)`. */
    fun addTrim(trimDeg: Double, dragDeg: Double): Double = wrap360(trimDeg + dragDeg)

    /**
     * How far apart two attitudes point, in degrees — the angle between the look directions.
     *
     * Used to decide whether the handset is being held still enough to be worth saying so; the roll
     * is deliberately not part of it, because turning the phone in its own plane does not change
     * what you are looking at.
     */
    fun separationDeg(a: Attitude, b: Attitude): Double {
        val f = DoubleArray(3)
        val g = DoubleArray(3)
        forward(a, f)
        forward(b, g)
        val dot = (f[0] * g[0] + f[1] * g[1] + f[2] * g[2]).coerceIn(-1.0, 1.0)
        return Math.toDegrees(Math.acos(dot))
    }

    private fun wrap360(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0.0) d + 360.0 else d
    }
}
