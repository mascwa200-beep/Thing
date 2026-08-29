package dev.mascwa.pulse.core.telemetry

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Does the WHOLE shipped composition reconstruct the attitude the handset is actually in - and what
 * happens to it within a degree of straight up?
 *
 * ⚠️ **`PointProbe` does NOT answer this, which is why this file exists.** That one measured
 * `SkyProjection.basisOf(View(az, alt, fov, ±roll))` - the raw reported roll fed straight to a View.
 * The shipped path is longer: `fromDeviceOrientation` negates the roll, then `equivalentView` negates
 * it AGAIN, and the vector path reads the negated Attitude. Nothing has ever measured that
 * composition. Its cases also stop at 70 deg of altitude, so the pole is untested.
 *
 * The reference here is a ground-truth **(forward, screen-up)** pair rather than a projected picture:
 * that is what the map actually needs, it is defined at the pole where an "unrolled up" is not, and a
 * round trip is a stronger statement than an agreement between two of our own code paths.
 *
 * ⚠️ The up direction is parameterised by turning an arbitrary perpendicular about the look axis,
 * NOT by removing the world vertical's component along it. The latter is what `PointProbe.attitude`
 * does and it is exactly zero at the zenith - the construction would fail at the one aim under test.
 */
private const val D = Math.PI / 180.0

private fun norm(v: DoubleArray): DoubleArray {
    val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
}

private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
)

private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

/** A direction in east/north/up from an azimuth clockwise of north and an altitude. */
private fun dir(azDeg: Double, altDeg: Double): DoubleArray {
    val a = azDeg * D
    val h = altDeg * D
    return doubleArrayOf(cos(h) * sin(a), cos(h) * cos(a), sin(h))
}

/** Some unit vector perpendicular to [f], defined at every aim including the poles. */
private fun anyPerp(f: DoubleArray): DoubleArray {
    val a = if (abs(f[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
    return norm(cross(f, a))
}

/** [anyPerp] turned [deg] about the look axis - a full circle of valid screen-up directions. */
private fun upAt(f: DoubleArray, deg: Double): DoubleArray {
    val p = anyPerp(f)
    val q = cross(f, p)
    val c = cos(deg * D)
    val s = sin(deg * D)
    return norm(doubleArrayOf(p[0] * c + q[0] * s, p[1] * c + q[1] * s, p[2] * c + q[2] * s))
}

/**
 * The device→world rotation for a camera aimed along [f] with screen-up [u].
 *
 * Device x is screen-right, y is screen-up, z is out of the screen, so the camera looks along −z and
 * right = f × u. Columns of R are the world images of the device axes.
 */
private fun matrixOf(f: DoubleArray, u: DoubleArray): FloatArray {
    val r = cross(f, u)
    return floatArrayOf(
        r[0].toFloat(), u[0].toFloat(), (-f[0]).toFloat(),
        r[1].toFloat(), u[1].toFloat(), (-f[1]).toFloat(),
        r[2].toFloat(), u[2].toFloat(), (-f[2]).toFloat(),
    )
}

private fun angleBetweenDeg(a: DoubleArray, b: DoubleArray): Double =
    Math.toDegrees(acos(dot(a, b).coerceIn(-1.0, 1.0)))

/**
 * How far the screen-up has turned ABOUT the look axis, signed, in degrees.
 *
 * ⚠️ This is the number the report is about. Comparing the up vectors by angle alone would also
 * count the tiny tilt that comes from the aim itself moving, which is not what "the picture rotated"
 * means. Projecting both into the plane perpendicular to [f] isolates the rotation of the picture.
 */
private fun rollBetweenDeg(f: DoubleArray, a: DoubleArray, b: DoubleArray): Double {
    fun flatten(v: DoubleArray): DoubleArray {
        val d = dot(v, f)
        return norm(doubleArrayOf(v[0] - d * f[0], v[1] - d * f[1], v[2] - d * f[2]))
    }
    val p = flatten(a)
    val q = flatten(b)
    val c = dot(p, q).coerceIn(-1.0, 1.0)
    val s = dot(cross(p, q), f)
    return Math.toDegrees(atan2(s, c))
}

/** The three numbers the sensor reports for an attitude, through the REAL Android maths. */
private class Reported(m: FloatArray) {
    val azimuthDeg: Double
    val pitchDeg: Double
    val rollDeg: Double

    init {
        val remapped = FloatArray(9)
        val o = FloatArray(3)
        SensorManager.remapCoordinateSystem(m, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, o)
        azimuthDeg = Math.toDegrees(o[0].toDouble())
        pitchDeg = Math.toDegrees(o[1].toDouble())
        rollDeg = Math.toDegrees(o[2].toDouble())
    }
}

/** The shipped composition, and the same with the roll negation removed. */
private fun compose(r: Reported, negateRoll: Boolean): Pair<DoubleArray, DoubleArray> {
    val a = SkyPointing.Attitude(
        azimuthDeg = r.azimuthDeg,
        altitudeDeg = -r.pitchDeg,
        rollDeg = if (negateRoll) -r.rollDeg else r.rollDeg,
    )
    val f = DoubleArray(3)
    val u = DoubleArray(3)
    SkyPointing.forward(a, f)
    SkyPointing.screenUp(a, u)
    return f to u
}

private fun aims(): List<Triple<String, Double, Double>> = listOf(
    Triple("horizon N", 0.0, 0.0),
    Triple("horizon E", 90.0, 0.0),
    Triple("up 30", 40.0, 30.0),
    Triple("up 70", 210.0, 70.0),
    Triple("up 85", 130.0, 85.0),
    Triple("up 89", 300.0, 89.0),
    Triple("up 89.9", 15.0, 89.9),
    Triple("up 89.99", 15.0, 89.99),
    Triple("down 45", 75.0, -45.0),
    Triple("down 89", 260.0, -89.0),
    Triple("down 89.9", 190.0, -89.9),
)

fun main() {
    println("PART 1 - does the composition reconstruct the attitude it was given?")
    println()
    println("%-12s %6s | %-22s | %-22s".format("aim", "up deg", "SHIPPED (roll negated)", "CORRECTED (as reported)"))
    println("%-12s %6s | %10s %11s | %10s %11s".format("", "", "aim err", "picture err", "aim err", "picture err"))

    var worstShipped = 0.0
    var worstCorrected = 0.0
    for ((name, az, alt) in aims()) {
        val f = dir(az, alt)
        for (upDeg in doubleArrayOf(0.0, 37.0, 90.0, -115.0, 179.0)) {
            val u = upAt(f, upDeg)
            val r = Reported(matrixOf(f, u))
            val (fs, us) = compose(r, negateRoll = true)
            val (fc, uc) = compose(r, negateRoll = false)
            val aimS = angleBetweenDeg(f, fs)
            val picS = abs(rollBetweenDeg(f, u, us))
            val aimC = angleBetweenDeg(f, fc)
            val picC = abs(rollBetweenDeg(f, u, uc))
            worstShipped = maxOf(worstShipped, picS)
            worstCorrected = maxOf(worstCorrected, picC)
            println(
                "%-12s %6.0f | %9.4f deg %10.4f deg | %9.4f deg %10.4f deg".format(
                    name, upDeg, aimS, picS, aimC, picC,
                ),
            )
        }
    }
    println()
    println("worst picture error - SHIPPED %.4e deg   CORRECTED %.4e deg".format(worstShipped, worstCorrected))
    // ⚠️ The tolerance is 1e-3 and not 1e-6 because the rotation matrix is FLOAT32: at 89.99 deg the
    // aim itself comes back 0.01 deg out for BOTH compositions, which is the matrix's own precision
    // and nothing to do with the roll. A tighter bar would report a defect that is arithmetic.
    println(
        "verdict: " + when {
            worstCorrected < 1e-3 && worstShipped > 1.0 -> "the roll negation in fromDeviceOrientation is WRONG"
            worstShipped < 1e-3 && worstCorrected > 1.0 -> "the shipped negation is right; the hypothesis is REFUTED"
            worstShipped < 1e-3 && worstCorrected < 1e-3 -> "INCONCLUSIVE - no case exercises the roll"
            else -> "NEITHER reconstructs the attitude; something else is wrong"
        },
    )

    println()
    println("PART 2 - a 0.25 deg nudge of the aim: how far does the picture turn?")
    println()
    println("%-10s | %14s | %14s".format("from vertical", "SHIPPED", "CORRECTED"))
    val nudge = 0.25
    for (delta in doubleArrayOf(10.0, 5.0, 2.0, 1.0, 0.5, 0.25)) {
        // Two aims the same small distance from the zenith, a nudge apart in the direction the tilt
        // points - the hand movement the report describes.
        val alt = 90.0 - delta
        var worstS = 0.0
        var worstC = 0.0
        for (az in 0 until 360 step 45) {
            for (upDeg in doubleArrayOf(0.0, 60.0, -140.0)) {
                val f0 = dir(az.toDouble(), alt)
                val u0 = upAt(f0, upDeg)
                // The nudge turns the aim about the screen-right axis, which is what tipping the
                // handset a little does; the picture SHOULD barely move.
                val right = cross(f0, u0)
                val c = cos(nudge * D)
                val s = sin(nudge * D)
                val f1 = norm(
                    doubleArrayOf(
                        f0[0] * c + right[0] * s, f0[1] * c + right[1] * s, f0[2] * c + right[2] * s,
                    ),
                )
                // Carry the screen-up rigidly through the same rotation, so the handset really has
                // only been nudged - any picture rotation the composition reports is invented.
                val u1 = norm(
                    doubleArrayOf(
                        u0[0], u0[1], u0[2],
                    ).let {
                        val d = dot(it, f1)
                        doubleArrayOf(it[0] - d * f1[0], it[1] - d * f1[1], it[2] - d * f1[2])
                    },
                )
                val r0 = Reported(matrixOf(f0, u0))
                val r1 = Reported(matrixOf(f1, u1))
                val trueTurn = rollBetweenDeg(f1, u0, u1)
                for (negate in booleanArrayOf(true, false)) {
                    val (_, a0) = compose(r0, negate)
                    val (b1, a1) = compose(r1, negate)
                    val got = rollBetweenDeg(b1, a0, a1)
                    val err = abs(got - trueTurn)
                    if (negate) worstS = maxOf(worstS, err) else worstC = maxOf(worstC, err)
                }
            }
        }
        println("%9.2f deg | %13.3f deg | %13.3f deg".format(delta, worstS, worstC))
    }

    println()
    println("PART 3 - what the sensor actually reports near the pole (the degeneracy itself)")
    println()
    println("%-10s %8s | %9s %9s %9s | %9s".format("from vert", "up deg", "azimuth", "pitch", "roll", "az+roll"))
    for (delta in doubleArrayOf(5.0, 1.0, 0.25)) {
        for (az in intArrayOf(0, 90, 180)) {
            val f = dir(az.toDouble(), 90.0 - delta)
            val u = upAt(f, 0.0)
            val r = Reported(matrixOf(f, u))
            println(
                "%9.2f deg %8.0f | %9.3f %9.3f %9.3f | %9.3f".format(
                    delta, 0.0, r.azimuthDeg, r.pitchDeg, r.rollDeg, r.azimuthDeg + r.rollDeg,
                ),
            )
        }
    }
}
