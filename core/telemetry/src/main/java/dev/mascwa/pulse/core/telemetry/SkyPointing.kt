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
 * ## ⚠️ The roll sign, and the negation that was applied twice
 *
 * `org.robolectric:android-all` carries real bodies for `SensorManager`'s static orientation maths,
 * so the whole sensor path — rotation vector, `remapCoordinateSystem`, `getOrientation` — can be
 * simulated off-device. `scratchpad/sky/PointProbe.kt` did that and measured
 * `View.rollDeg = -orientation[2]`, out by 2.98 screen units the other way.
 *
 * ⚠️ **That verdict is about [equivalentView]'s output and NOT about this [Attitude], and reading it
 * as though it were cost a shipped defect.** [equivalentView] negates the roll on its way to a
 * `View`, so `Attitude.rollDeg` has to be `+orientation[2]` for the `View` to come out at
 * `-orientation[2]` — and it was set to the negative, which made the composition `+orientation[2]`,
 * exactly the sign that probe had rejected. Two statements of one fact, one of them second-hand.
 *
 * The tests could not see it either: they compared the vector path against the angle path, both
 * built from the same wrong `Attitude`, so the two agreed perfectly and said nothing about the
 * sensor. `scratchpad/sky/PoleProbe.kt` is what measures the composition end to end, and
 * `the sensor's roll is read back the way round the handset is really held` is what holds it.
 *
 * `azimuthDeg = orientation[0]` and `altitudeDeg = -orientation[1]` were right all along and are
 * confirmed by the same run. What stays device-only is the **feel** — how stiff the filter should
 * be, and how far a real magnetometer is off in a real hand.
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
     * ⚠️ **The pitch is negated and the ROLL IS NOT, and that asymmetry is the whole of a defect
     * this shipped with.** The roll was negated here too, on the strength of a probe whose verdict
     * was about [equivalentView]'s `View.rollDeg` — and `equivalentView` applies that negation
     * itself, so the sign was turned round twice. Doing the negations here rather than at the sensor
     * is still right, for the reason it always was: this module has tests and a sensor callback does
     * not. What was missing is that nothing measured the COMPOSITION.
     *
     * Measured against the real Android orientation maths by reconstructing the handset's own
     * screen-up from these three numbers (`scratchpad/sky/PoleProbe.kt`, aims out to 89.99°):
     *
     * ```
     * rollDeg = +orientation[2]   worst picture error   7.0e-06°
     * rollDeg = −orientation[2]   worst picture error        180°
     * ```
     *
     * ⚠️ **And it was not merely a picture rolled the wrong way — near the pole the wrong sign
     * AMPLIFIES.** Within a degree of vertical the reported azimuth and roll are each
     * ill-conditioned, and the combination that is not is their DIFFERENCE, which is what the
     * correct sign forms. The wrong sign formed the sum, so a hand movement that turned the handset
     * rigidly — one that must not move the picture at all — spun it:
     *
     * ```
     * a 0.25° nudge, this far from vertical:   10.0°   1.0°   0.5°   0.25°
     *   picture turned, wrong sign:             2.8°  29.0°  60.0°  175.0°
     *   picture turned, correct sign:           0.0°   0.0°   0.0°    0.0°
     * ```
     */
    fun fromDeviceOrientation(azimuthDeg: Double, pitchDeg: Double, rollDeg: Double): Attitude =
        Attitude(azimuthDeg = azimuthDeg, altitudeDeg = -pitchDeg, rollDeg = rollDeg)

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
     * The same direction, expressed in the frame the star catalogue is held in.
     *
     * ⚠️ **Both of the attitude's directions have to come through here, and the reason is that the
     * stars do not move and the observer does.** A catalogue held in horizon coordinates goes stale
     * within seconds of being loaded — within a single frame at a narrow field — so `SkyFrame` holds
     * equatorial positions and rebuilds the *basis* each frame instead. That basis is
     * `forward × up`, and both of those arrive from the handset in east/north/up. Rotating one and
     * not the other would build a basis out of two frames at once: it compiles, it draws a sky, and
     * the sky is wrong by however far the Earth has turned since midnight.
     *
     * ⚠️ **The rotation is [Ephemeris.toEquatorial] rather than arithmetic written here**, so a
     * pointed map and a dragged one cannot come to disagree about what an azimuth means — the same
     * reason [forward] mirrors [SkyProjection]'s own convention. It is a pure rotation with no
     * refraction in it, which is what makes the pair still orthonormal on the far side; `SkyPointingTest`
     * pins that rather than trusting it, because a sheared basis is not a crash, it is a picture
     * very slightly skewed that nothing would ever report.
     *
     * Passing the screen-up through [azimuthOf] costs nothing at the zenith even though a
     * straight-up vector has no bearing: at 90° of altitude the azimuth cannot change the direction,
     * so the zero that [azimuthOf] answers is as good as any other number.
     */
    fun toEquatorialVector(
        enu: DoubleArray,
        latitudeDeg: Double,
        longitudeDeg: Double,
        epochMs: Long,
        out: DoubleArray,
    ) {
        val eq = Ephemeris.toEquatorial(
            Ephemeris.Horizontal(altitudeOf(enu), azimuthOf(enu), 0.0),
            latitudeDeg,
            longitudeDeg,
            epochMs,
        )
        val v = SkyProjection.equatorialVector(eq.rightAscensionDeg, eq.declinationDeg)
        out[0] = v[0]; out[1] = v[1]; out[2] = v[2]
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
     * @param alpha weight of the new reading for the LOOK DIRECTION, 0 (frozen) to 1 (no smoothing).
     * @param upAlpha the same for the screen-up, which is allowed to be stiffer — see [upAlpha].
     *   Defaults to [alpha], which is exactly the single-weight behaviour this had.
     */
    fun smooth(
        prevForward: DoubleArray,
        prevUp: DoubleArray,
        next: Attitude,
        alpha: Double,
        outForward: DoubleArray,
        outUp: DoubleArray,
        upAlpha: Double = alpha,
    ) {
        // ⚠️ **The previous pair is read into locals BEFORE anything is written, and without that
        // this whole function is a no-op.** Its only caller passes the same two arrays as both
        // `prev` and `out` — which is the natural way to use it and what the signature invites —
        // so writing the fresh reading into `out` first destroys `prev`, every blend below becomes
        // `new·(1−w) + new·w`, and the map follows raw sensor jitter at every aim however small the
        // weight. `SkyBudget` is a whole file shaped around deriving that weight correctly and none
        // of it could have any effect. No test caught it because every one of them passes distinct
        // arrays; `a blend really blends when the caller aliases its arrays` now pins the call
        // site's own pattern.
        val pfx = prevForward[0]
        val pfy = prevForward[1]
        val pfz = prevForward[2]
        val pux = prevUp[0]
        val puy = prevUp[1]
        val puz = prevUp[2]

        // The fresh reading is written first and is what survives if the blend turns out degenerate,
        // so the caller always ends up holding a valid attitude.
        forward(next, outForward)
        screenUp(next, outUp)
        val w = alpha.coerceIn(0.0, 1.0)
        val wu = upAlpha.coerceIn(0.0, 1.0)
        // ⚠️ BOTH have to be at 1 to skip out, or a stiffened screen-up would never be blended. When
        // the caller wants the first reading taken whole it passes 1 for both and this is the branch
        // that gives it that — see the call site, where blending a first reading against the arrays'
        // starting values swept the sky in from due north.
        if (w >= 1.0 && wu >= 1.0) return

        var fx = outForward[0]
        var fy = outForward[1]
        var fz = outForward[2]
        if (w < 1.0) {
            fx = pfx * (1 - w) + fx * w
            fy = pfy * (1 - w) + fy * w
            fz = pfz * (1 - w) + fz * w
            val n = sqrt(fx * fx + fy * fy + fz * fz)
            // ⚠️ Two look directions exactly opposite blend to nothing. At any real frame rate that
            // is a sensor glitch rather than a hand movement, so the newest reading is the better
            // answer — and a normalised zero vector would be an unusable basis, which is a BLANK MAP.
            if (n < DEGENERATE) return
            fx /= n; fy /= n; fz /= n
        }

        var ux = outUp[0]
        var uy = outUp[1]
        var uz = outUp[2]
        if (wu < 1.0) {
            ux = pux * (1 - wu) + ux * wu
            uy = puy * (1 - wu) + uy * wu
            uz = puz * (1 - wu) + uz * wu
        }
        // ⚠️ A blend of two valid attitudes is NOT itself one — the pair drifts out of square, and
        // feeding that to a basis is not a crash but a picture very slightly sheared, which nothing
        // would ever report. Take off whatever now lies along the look direction.
        val d = ux * fx + uy * fy + uz * fz
        ux -= d * fx; uy -= d * fy; uz -= d * fz
        val n = sqrt(ux * ux + uy * uy + uz * uz)
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
     * The blend weight for the SCREEN-UP alone, stiffened as the aim nears straight up or down.
     *
     * ⚠️ **Only the screen-up, never the look direction, and that is the point.** Damping the aim
     * would make a deliberate sweep across the zenith lag the hand, which is a worse fault than the
     * one being fixed. [smooth] re-orthogonalises the up against the forward afterwards, so the two
     * may carry different weights and still leave a valid attitude — which is what makes this safe
     * rather than merely convenient.
     *
     * ⚠️ **Why the screen-up specifically.** Overhead, which way is up the screen is decided
     * entirely by the handset's heading, and a magnetometer is good to a degree or two at best. Near
     * the horizon that error is a degree or two of pan and barely visible; overhead it is the whole
     * picture turning. So the filter is stiffened exactly where the reading is least trustworthy,
     * rather than everywhere.
     *
     * ⚠️ **The stretch is exact rather than a fudge.** An exponential filter retains `(1 − w)` of the
     * old value per sample, so running its time constant `k` times longer is `1 − w' = (1 − w)^(1/k)`.
     * Working on the weight itself means it stays correct at every sensor rate for free — the trap
     * `SkyBudget` is shaped around — because the weight handed in has already been derived from the
     * rate. At `k = 1` this returns its argument, so a level aim is byte-for-byte unchanged.
     *
     * Both ends are answered unchanged: 0 stays frozen and 1 stays "take it whole", which is what
     * the caller passes for the very first reading.
     */
    fun upAlpha(alpha: Double, altitudeDeg: Double): Double {
        val w = alpha.coerceIn(0.0, 1.0)
        if (w <= 0.0 || w >= 1.0) return w
        val k = poleStretch(altitudeDeg)
        if (k <= 1.0) return w
        return 1.0 - Math.pow(1.0 - w, 1.0 / k)
    }

    /**
     * How many times longer the screen-up's filter runs at this aim — 1 outside [POLE_RAMP_DEG].
     *
     * ⚠️ Smoothstep rather than a straight ramp or a threshold: a step in the filter weight is a step
     * in how far the picture lags, which reads as a snap at the moment you tilt across it. This has
     * zero slope at both ends, so there is no crossing to see.
     */
    private fun poleStretch(altitudeDeg: Double): Double {
        val fromPole = 90.0 - abs(altitudeDeg.coerceIn(-90.0, 90.0))
        if (fromPole >= POLE_RAMP_DEG) return 1.0
        val t = ((POLE_RAMP_DEG - fromPole) / POLE_RAMP_DEG).coerceIn(0.0, 1.0)
        return 1.0 + (POLE_MAX_STRETCH - 1.0) * t * t * (3.0 - 2.0 * t)
    }

    /**
     * How close to straight up or down the extra damping starts, in degrees of altitude.
     *
     * ⚠️ A guess at FEEL and owner-tunable, which is why it is named rather than inlined. Nothing
     * here can wave a handset about; what is measured is only that the arithmetic is exact and that
     * a level aim is unaffected.
     */
    const val POLE_RAMP_DEG = 15.0

    /** How much longer the screen-up's filter runs when aimed straight at the pole. Owner-tunable. */
    const val POLE_MAX_STRETCH = 8.0

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
