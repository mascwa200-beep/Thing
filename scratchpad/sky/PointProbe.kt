package dev.mascwa.pulse.core.telemetry

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Does `SkyProjection` fed from the phone's own orientation sensor draw what the camera is actually
 * pointed at — and with WHICH SIGN of roll?
 *
 * ⚠️ **This is measurable here, and the plan assumed it was not.** `org.robolectric:android-all`
 * carries REAL bodies for `SensorManager`'s static orientation maths — `getRotationMatrixFromVector`,
 * `remapCoordinateSystem` and `getOrientation` are pure arithmetic in the framework — so the whole
 * sensor path can be simulated on this machine. What stays device-only is the FEEL: filter
 * stiffness, and how far a magnetometer is off in a real hand. The CONVENTION is arithmetic.
 *
 * The reference is computed straight from the device attitude with no angles in between: Android's
 * rotation matrix maps device coordinates to world east/north/up, so a world direction in device
 * coordinates is `Rᵀ w`; the camera looks along device −z, screen right is device +x and screen up is
 * device +y. That is exactly the frame `SkyProjection.Basis` builds, so the two must agree to
 * floating point if — and only if — the angles are read out and put back correctly.
 *
 * ⚠️ **The scale is READ from the shipped definition, not guessed.** The first version of this probe
 * used `1/tan(fov/4)` where `SkyProjection.edgeRadius` is `2·tan(fov/4)`, so every attitude — even
 * the one with no roll at all — disagreed by a factor of two, which looks exactly like a sign error
 * and is not one. Derive constants from the source.
 */
private const val D = Math.PI / 180.0

private val UP = doubleArrayOf(0.0, 0.0, 1.0)

private fun norm(v: DoubleArray): DoubleArray {
    val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
}

private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
)

/** A direction in east/north/up from an azimuth clockwise of north and an altitude. */
private fun dir(azDeg: Double, altDeg: Double): DoubleArray {
    val a = azDeg * D
    val h = altDeg * D
    return doubleArrayOf(cos(h) * sin(a), cos(h) * cos(a), sin(h))
}

/**
 * The device attitude for a camera aimed along [f] with the phone tipped [rollDeg] clockwise —
 * positive meaning the TOP of the handset goes to the right, which is what the sensor reports.
 *
 * Device x is screen-right, y is screen-up, z is out of the screen, so the camera looks along −z and
 * `r = f × u` (checked: aimed north with the phone upright gives right = east).
 */
private fun attitude(f: DoubleArray, rollDeg: Double): FloatArray {
    val u0 = norm(doubleArrayOf(UP[0] - UP[0] * 0.0, 0.0, 0.0).let {
        val d = UP[0] * f[0] + UP[1] * f[1] + UP[2] * f[2]
        doubleArrayOf(UP[0] - d * f[0], UP[1] - d * f[1], UP[2] - d * f[2])
    })
    val r0 = cross(f, u0)
    val c = cos(rollDeg * D)
    val s = sin(rollDeg * D)
    val u = norm(doubleArrayOf(u0[0] * c + r0[0] * s, u0[1] * c + r0[1] * s, u0[2] * c + r0[2] * s))
    val r = cross(f, u)
    val zdev = doubleArrayOf(-f[0], -f[1], -f[2])
    // Column-wise: R maps device -> world, so its columns are the world images of the device axes.
    return floatArrayOf(
        r[0].toFloat(), u[0].toFloat(), zdev[0].toFloat(),
        r[1].toFloat(), u[1].toFloat(), zdev[1].toFloat(),
        r[2].toFloat(), u[2].toFloat(), zdev[2].toFloat(),
    )
}

/** What the camera really sees, straight from the attitude matrix — the scale from the source. */
private fun reference(r: FloatArray, w: DoubleArray, fovDeg: Double): DoubleArray? {
    val dx = r[0] * w[0] + r[3] * w[1] + r[6] * w[2]
    val dy = r[1] * w[0] + r[4] * w[1] + r[7] * w[2]
    val dz = r[2] * w[0] + r[5] * w[1] + r[8] * w[2]
    val z = -dz
    if (z <= -0.999) return null
    val k = 2.0 / (1.0 + z)
    val invScale = 1.0 / (2.0 * tan(fovDeg * D / 4.0))
    return doubleArrayOf(dx * k * invScale, -dy * k * invScale)
}

fun main() {
    val fov = 60.0
    val remapped = FloatArray(9)
    val o = FloatArray(3)

    val targets = ArrayList<DoubleArray>()
    for (az in 0 until 360 step 10) for (alt in -70..80 step 10) targets.add(dir(az.toDouble(), alt.toDouble()))

    val cases = ArrayList<Triple<String, DoubleArray, Double>>()
    for (aim in listOf(
        "N horizon" to dir(0.0, 0.0),
        "E horizon" to dir(90.0, 0.0),
        "SW horizon" to dir(225.0, 0.0),
        "N up 45" to dir(0.0, 45.0),
        "W down 30" to dir(270.0, -30.0),
        "SE up 70" to dir(135.0, 70.0),
    )) {
        for (roll in doubleArrayOf(0.0, 15.0, -25.0, 90.0, -90.0, 170.0)) {
            cases.add(Triple("${aim.first} roll ${roll.toInt()}", aim.second, roll))
        }
    }

    var worstMinusAll = 0.0
    var worstPlusAll = 0.0
    println("case                     reported az    alt   roll |  worst(-roll)  worst(+roll)")
    for ((name, f, rollIn) in cases) {
        val r = attitude(f, rollIn)
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, o)
        val az = Math.toDegrees(o[0].toDouble())
        val alt = -Math.toDegrees(o[1].toDouble())
        val roll = Math.toDegrees(o[2].toDouble())

        val worst = DoubleArray(2)
        for ((i, sign) in intArrayOf(-1, 1).withIndex()) {
            val basis = SkyProjection.basisOf(SkyProjection.View(az, alt, fov, sign * roll))
            var w = 0.0
            for (t in targets) {
                val ref = reference(r, t, fov) ?: continue
                if (hypot(ref[0], ref[1]) > 1.5) continue
                val got = SkyProjection.projectUnit(t[0], t[1], t[2], basis)
                if (!got.visible) continue
                w = maxOf(w, hypot(got.x - ref[0], got.y - ref[1]))
            }
            worst[i] = w
        }
        worstMinusAll = maxOf(worstMinusAll, worst[0])
        worstPlusAll = maxOf(worstPlusAll, worst[1])
        println(
            "%-24s %8.1f %6.1f %6.1f |   %10.2e   %10.2e".format(name, az, alt, roll, worst[0], worst[1]),
        )
    }

    println()
    println("worst over every case:  -roll %.3e   +roll %.3e".format(worstMinusAll, worstPlusAll))
    val verdict = when {
        worstMinusAll < 1e-4 && worstPlusAll >= 1e-4 -> "rollDeg = -orientation[2]"
        worstPlusAll < 1e-4 && worstMinusAll >= 1e-4 -> "rollDeg = +orientation[2]"
        worstMinusAll < 1e-4 && worstPlusAll < 1e-4 -> "INCONCLUSIVE — both agree, so no case exercises roll"
        else -> "NEITHER agrees; something else is wrong"
    }
    println("verdict: $verdict")
    println("(the reported roll is non-zero in ${cases.indices.count { abs(cases[it].third) > 1 }} of ${cases.size} cases by construction)")
}
