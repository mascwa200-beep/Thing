package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Every expectation `SkyPointingTest` asserts, computed from the shipped functions first. */
private const val D = Math.PI / 180.0

private fun unit(azDeg: Double, altDeg: Double): DoubleArray {
    val a = azDeg * D
    val h = altDeg * D
    return doubleArrayOf(cos(h) * sin(a), cos(h) * cos(a), sin(h))
}

private fun angleBetween(a: DoubleArray, b: DoubleArray): Double {
    val d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(d))
}

fun main() {
    val f = DoubleArray(3)
    val u = DoubleArray(3)

    // 1. screenUp at roll 0 is unit(az, alt + 90).
    var worstQuarterTurn = 0.0
    var worstOrtho = 0.0
    var worstNorm = 0.0
    for (az in 0 until 360 step 7) {
        for (alt in -90..90 step 5) {
            for (roll in intArrayOf(0, 17, -33, 90, -90, 179)) {
                val a = SkyPointing.Attitude(az.toDouble(), alt.toDouble(), roll.toDouble())
                SkyPointing.forward(a, f)
                SkyPointing.screenUp(a, u)
                worstNorm = maxOf(
                    worstNorm,
                    abs(sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]) - 1.0),
                    abs(sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]) - 1.0),
                )
                worstOrtho = maxOf(worstOrtho, abs(f[0] * u[0] + f[1] * u[1] + f[2] * u[2]))
                if (roll == 0) {
                    val q = unit(az.toDouble(), alt + 90.0)
                    worstQuarterTurn = maxOf(
                        worstQuarterTurn,
                        abs(u[0] - q[0]), abs(u[1] - q[1]), abs(u[2] - q[2]),
                    )
                }
            }
        }
    }
    println("screenUp(roll 0) vs unit(az, alt+90): worst component diff %.3e".format(worstQuarterTurn))
    println("orthonormality: worst |f.u| %.3e, worst |len-1| %.3e".format(worstOrtho, worstNorm))

    // 2. The vector path and the angle path draw the same picture, away from the pole.
    val targets = ArrayList<DoubleArray>()
    for (az in 0 until 360 step 10) for (alt in -80..80 step 10) targets.add(unit(az.toDouble(), alt.toDouble()))
    var worstSame = 0.0
    val fov = 60.0
    for (az in 0 until 360 step 30) {
        for (alt in intArrayOf(-89, -60, -20, 0, 20, 60, 89)) {
            for (roll in intArrayOf(0, 17, -33, 90, -90, 179)) {
                val a = SkyPointing.Attitude(az.toDouble(), alt.toDouble(), roll.toDouble())
                SkyPointing.forward(a, f)
                SkyPointing.screenUp(a, u)
                val vec = SkyProjection.basisOf(f, u[0], u[1], u[2], fov, 0.0)
                val ang = SkyProjection.basisOf(SkyPointing.equivalentView(a, fov))
                for (t in targets) {
                    val p = SkyProjection.projectUnit(t[0], t[1], t[2], vec)
                    val q = SkyProjection.projectUnit(t[0], t[1], t[2], ang)
                    if (!p.visible || !q.visible) continue
                    if (hypot(p.x, p.y) > 1.5) continue
                    worstSame = maxOf(worstSame, hypot(p.x - q.x, p.y - q.y))
                }
            }
        }
    }
    println("vector path vs equivalentView, |alt| <= 89: worst %.3e screen units".format(worstSame))

    // 3. The zenith. Two attitudes a fifth of a degree apart in aim and half a turn apart in roll.
    val a0 = SkyPointing.Attitude(0.0, 89.9, 0.0)
    val a1 = SkyPointing.Attitude(180.0, 89.9, 0.0)
    val f0 = DoubleArray(3); val u0 = DoubleArray(3)
    val f1 = DoubleArray(3); val u1 = DoubleArray(3)
    SkyPointing.forward(a0, f0); SkyPointing.screenUp(a0, u0)
    SkyPointing.forward(a1, f1); SkyPointing.screenUp(a1, u1)
    println()
    println("at the zenith, az 0 vs az 180 at altitude 89.9:")
    println("  look directions differ by %.4f deg".format(angleBetween(f0, f1)))
    println("  screen-up directions differ by %.2f deg".format(angleBetween(u0, u1)))
    // What a per-angle smoother would do: average the azimuth circularly to 90 (or 270).
    val perAngle = SkyPointing.Attitude(90.0, 89.9, 0.0)
    val fp = DoubleArray(3); val up = DoubleArray(3)
    SkyPointing.forward(perAngle, fp); SkyPointing.screenUp(perAngle, up)
    println("  per-angle blend (azimuth averaged to 90): up turns %.1f deg from the first reading"
        .format(angleBetween(u0, up)))
    // The vector smoother on the same pair.
    SkyPointing.smooth(f0, u0, a1, 0.5, f, u)
    println("  vector blend at alpha 0.5: look %.4f deg from each, up %.1f / %.1f deg"
        .format(angleBetween(f, f0), angleBetween(u, u0), angleBetween(u, u1)))

    // 4. A more ordinary blend: is the result orthonormal?
    val prevF = DoubleArray(3); val prevU = DoubleArray(3)
    var worstBlendOrtho = 0.0
    var worstBlendNorm = 0.0
    for (az in 0 until 360 step 23) {
        val p = SkyPointing.Attitude(az.toDouble(), 20.0, 10.0)
        val n = SkyPointing.Attitude(az + 8.0, 25.0, -14.0)
        SkyPointing.forward(p, prevF); SkyPointing.screenUp(p, prevU)
        SkyPointing.smooth(prevF, prevU, n, 0.25, f, u)
        worstBlendOrtho = maxOf(worstBlendOrtho, abs(f[0] * u[0] + f[1] * u[1] + f[2] * u[2]))
        worstBlendNorm = maxOf(
            worstBlendNorm,
            abs(sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]) - 1.0),
            abs(sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]) - 1.0),
        )
    }
    println()
    println("blended pair: worst |f.u| %.3e, worst |len-1| %.3e".format(worstBlendOrtho, worstBlendNorm))

    // 5. The degenerate blend: exactly opposite look directions.
    val north = SkyPointing.Attitude(0.0, 0.0, 0.0)
    val south = SkyPointing.Attitude(180.0, 0.0, 0.0)
    SkyPointing.forward(north, prevF); SkyPointing.screenUp(north, prevU)
    SkyPointing.smooth(prevF, prevU, south, 0.5, f, u)
    SkyPointing.forward(south, f1); SkyPointing.screenUp(south, u1)
    println("opposite look directions at alpha 0.5 -> kept the fresh reading: " +
        "forward %s  up %s".format(
            (0..2).all { abs(f[it] - f1[it]) < 1e-12 },
            (0..2).all { abs(u[it] - u1[it]) < 1e-12 },
        ))

    // 6. alpha = 1 is the identity; alpha = 0 keeps the previous direction.
    SkyPointing.forward(north, prevF); SkyPointing.screenUp(north, prevU)
    val east = SkyPointing.Attitude(90.0, 30.0, 12.0)
    SkyPointing.smooth(prevF, prevU, east, 1.0, f, u)
    SkyPointing.forward(east, f1); SkyPointing.screenUp(east, u1)
    println("alpha 1 is the new reading: %s".format((0..2).all { abs(f[it] - f1[it]) < 1e-15 }))
    SkyPointing.smooth(prevF, prevU, east, 0.0, f, u)
    println("alpha 0 keeps the old direction, to %.3e deg".format(angleBetween(f, prevF)))

    // 7. Trim.
    println()
    println("trim: 350 + 20 -> %.1f ; addTrim(-5, -10) -> %.1f".format(
        SkyPointing.trimmed(SkyPointing.Attitude(350.0, 0.0, 0.0), 20.0).azimuthDeg,
        SkyPointing.addTrim(-5.0, -10.0),
    ))
    println("azimuthOf(zenith) = %.1f ; altitudeOf(zenith) = %.1f".format(
        SkyPointing.azimuthOf(doubleArrayOf(0.0, 0.0, 1.0)),
        SkyPointing.altitudeOf(doubleArrayOf(0.0, 0.0, 1.0)),
    ))
    val fd = SkyPointing.fromDeviceOrientation(123.0, -45.0, 30.0)
    println("fromDeviceOrientation(123, -45, 30) = $fd")

    // 8. The claim the whole design rests on: aimed exactly at the zenith, is the basis usable?
    val zen = SkyPointing.Attitude(37.0, 90.0, 0.0)
    SkyPointing.forward(zen, f); SkyPointing.screenUp(zen, u)
    val withUp = SkyProjection.basisOf(f, u[0], u[1], u[2], fov, 0.0)
    val withZenith = SkyProjection.basisOf(f, 0.0, 0.0, 1.0, fov, 0.0)
    println()
    println("aimed at the zenith: basis from the screen-up usable = ${withUp.usable}; " +
        "basis from the observer's zenith usable = ${withZenith.usable}")
}
