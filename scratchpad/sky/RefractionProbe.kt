package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Prints the numbers RefractionTest asserts — computed from the SHIPPED functions before a single
 * expectation is written, which is the discipline this repository records after a dozen wrong
 * guesses. Run with scratchpad/sky/runmain.sh.
 */
fun main() {
    val us = java.util.Locale.US
    fun f(d: Double) = "%.6f".format(us, d)
    fun arcsec(d: Double) = "%.3f\"".format(us, d * 3600.0)
    fun arcmin(d: Double) = "%.3f'".format(us, d * 60.0)

    println("== reference values ==")
    println("bennettDeg(0 apparent)  = ${arcmin(Refraction.bennettDeg(0.0))}")
    for (h in listOf(-4.0, -3.0, -2.0, -1.0, -0.5, 0.0, 1.0, 2.0, 5.0, 10.0, 20.0, 45.0, 60.0, 80.0, 89.0, 89.9, 90.0)) {
        val b = Refraction.bendingDeg(h)
        println("bendingDeg(${f(h)}) = ${f(b)} deg = ${arcmin(b)} = ${arcsec(b)}   apparent=${f(Refraction.apparentOf(h))}")
    }

    println("== monotonic ==")
    var minSlope = Double.MAX_VALUE
    var minAt = 0.0
    var prev = Refraction.apparentOf(-5.0)
    var h = -5.0
    val step = 0.001
    while (h < 90.0) {
        h += step
        val a = Refraction.apparentOf(h)
        val slope = (a - prev) / step
        if (slope < minSlope) { minSlope = slope; minAt = h }
        prev = a
    }
    println("min d(apparent)/d(true) = ${f(minSlope)} at true ${f(minAt)}")

    println("== inverse round trip ==")
    var worst = 0.0
    var worstAt = 0.0
    h = -5.0
    while (h <= 90.0) {
        val back = Refraction.trueOf(Refraction.apparentOf(h))
        val e = abs(back - h)
        if (e > worst) { worst = e; worstAt = h }
        h += 0.01
    }
    println("worst |trueOf(apparentOf(h)) - h| = ${arcsec(worst)} at ${f(worstAt)}")
    // and the other direction, over apparent altitudes
    worst = 0.0
    var a = -5.0
    while (a <= 90.0) {
        val e = abs(Refraction.apparentOf(Refraction.trueOf(a)) - a)
        if (e > worst) { worst = e; worstAt = a }
        a += 0.01
    }
    println("worst |apparentOf(trueOf(a)) - a| = ${arcsec(worst)} at ${f(worstAt)}")

    println("== table vs exact ==")
    worst = 0.0
    h = -4.5
    var worstAbove = 0.0
    var worstAboveAt = 0.0
    while (h <= 90.0) {
        val e = abs(Refraction.fastBendingDeg(h) - Refraction.bendingDeg(h))
        if (e > worst) { worst = e; worstAt = h }
        if (h >= 0.0 && e > worstAbove) { worstAbove = e; worstAboveAt = h }
        h += 0.0007
    }
    println("worst |fast - exact| = ${arcsec(worst)} at ${f(worstAt)}; above the horizon ${arcsec(worstAbove)} at ${f(worstAboveAt)}")
    println("table entries reachable: fast(-4)=${f(Refraction.fastBendingDeg(-4.0))} fast(-3.99)=${f(Refraction.fastBendingDeg(-3.99))} fast(89.99)=${f(Refraction.fastBendingDeg(89.99))} fast(90)=${f(Refraction.fastBendingDeg(90.0))}")

    println("== taper joints ==")
    val eps = 1e-9
    println("at -1: below ${f(Refraction.bendingDeg(-1.0 - eps))} at ${f(Refraction.bendingDeg(-1.0))} above ${f(Refraction.bendingDeg(-1.0 + eps))}")
    println("at -4: below ${f(Refraction.bendingDeg(-4.0 - eps))} at ${f(Refraction.bendingDeg(-4.0))} above ${f(Refraction.bendingDeg(-4.0 + eps))}")
    println("SIN_TAPER_BOTTOM = ${f(Refraction.SIN_TAPER_BOTTOM)}; asin(SIN_NO_BEND) = ${f(Math.toDegrees(asin(Refraction.SIN_NO_BEND)))}; bending there = ${arcsec(Refraction.bendingDeg(Math.toDegrees(asin(Refraction.SIN_NO_BEND))))}")

    println("== lift ==")
    // A direction at altitude 10 deg, azimuth 40 deg east of north, with the zenith straight up.
    val alt = Math.toRadians(10.0)
    val az = Math.toRadians(40.0)
    val vx = kotlin.math.cos(alt) * kotlin.math.sin(az)
    val vy = kotlin.math.cos(alt) * kotlin.math.cos(az)
    val vz = kotlin.math.sin(alt)
    val out = DoubleArray(3)
    val bend = Refraction.bendingDeg(10.0)
    Refraction.lift(vx, vy, vz, 0.0, 0.0, 1.0, vz, bend, out)
    val n = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2])
    val liftedAlt = Math.toDegrees(asin(out[2] / n))
    val liftedAz = Math.toDegrees(kotlin.math.atan2(out[0], out[1]))
    println("lift 10deg by ${arcsec(bend)}: |v'|-1 = ${"%.3e".format(us, n - 1.0)}, alt' = ${f(liftedAlt)} (expected ${f(10.0 + bend)}), az' = ${f(liftedAz)}")
    // and back down by the same bend
    val back = DoubleArray(3)
    Refraction.lift(out[0], out[1], out[2], 0.0, 0.0, 1.0, out[2], -bend, back)
    println("lift back: alt = ${f(Math.toDegrees(asin(back[2])))}, err = ${arcsec(abs(Math.toDegrees(asin(back[2])) - 10.0))}")
    // tilted zenith: same test with the zenith at some odd direction
    val zx = 0.3; val zy = -0.5; val zz = sqrt(1 - 0.09 - 0.25)
    // a direction 10 deg from the horizon in that frame: take a perpendicular and mix
    val px = zy; val py = -zx; val pz = 0.0  // perpendicular to z
    val pn = sqrt(px * px + py * py)
    val ux = px / pn; val uy = py / pn; val uz = pz / pn
    val wx = kotlin.math.cos(alt) * ux + kotlin.math.sin(alt) * zx
    val wy = kotlin.math.cos(alt) * uy + kotlin.math.sin(alt) * zy
    val wz = kotlin.math.cos(alt) * uz + kotlin.math.sin(alt) * zz
    val s = wx * zx + wy * zy + wz * zz
    Refraction.lift(wx, wy, wz, zx, zy, zz, s, bend, out)
    val s2 = out[0] * zx + out[1] * zy + out[2] * zz
    println("tilted zenith: sinAlt before ${f(s)} (alt ${f(Math.toDegrees(asin(s)))}), after alt ${f(Math.toDegrees(asin(s2)))}, expected ${f(10.0 + bend)}")
}
