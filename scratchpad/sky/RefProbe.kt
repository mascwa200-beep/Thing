package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.sky.ReferenceLines
import dev.mascwa.pulse.sky.SkyLines
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Run the SHIPPED reference-circle build and the SHIPPED cap test over the real thing, at every
 * field the map allows, pointed straight at the line.
 *
 * The failure this exists to catch is silent: [SkyLines.visible] rejects a whole run with one dot
 * product, so a badly chosen [ReferenceCircles.ARCS] would make the ecliptic disappear at a narrow
 * field with nothing on screen to say why. A unit test on the geometry would not notice.
 */
private fun eqOf(v: DoubleArray): Pair<Double, Double> {
    val ra = Math.toDegrees(atan2(v[1], v[0])).let { if (it < 0) it + 360.0 else it }
    val dec = Math.toDegrees(asin(v[2].coerceIn(-1.0, 1.0)))
    return ra to dec
}

/** Count runs that survive the cap test and segments that would reach the batch. */
private fun survey(lines: SkyLines, ra: Double, dec: Double, fovDeg: Double): Triple<Int, Int, Int> {
    val f = SkyProjection.equatorialVector(ra, dec)
    // Equatorial frame: "up" for the viewer is the celestial pole, which is what SkyMapViewModel
    // passes for a chart in this frame.
    val basis = SkyProjection.basisOf(f, 0.0, 0.0, 1.0, fovDeg, 0.0)
    val viewport = SkyProjection.viewportOf(1080.0, 2400.0)
    val cone = Math.toRadians(SkyProjection.coneRadiusDeg(fovDeg, viewport))
    val coneCos = kotlin.math.cos(cone)
    val coneSin = kotlin.math.sin(cone)
    val marginW = viewport.halfWidth + 0.08
    val marginH = viewport.halfHeight + 0.08
    var runs = 0
    var projected = 0
    var drawn = 0
    for (run in 0 until lines.lines) {
        if (!lines.visible(run, f[0], f[1], f[2], coneCos, coneSin)) continue
        runs++
        val from = lines.start[run]
        val to = from + lines.length[run]
        var haveLast = false
        var lastX = 0.0
        var lastY = 0.0
        for (i in from until to) {
            val p = SkyProjection.projectUnit(lines.vx[i], lines.vy[i], lines.vz[i], basis)
            projected++
            if (!p.visible) { haveLast = false; continue }
            if (haveLast) {
                val outside =
                    (lastX > marginW && p.x > marginW) || (lastX < -marginW && p.x < -marginW) ||
                        (lastY > marginH && p.y > marginH) || (lastY < -marginH && p.y < -marginH)
                if (!outside) drawn++
            }
            lastX = p.x; lastY = p.y; haveLast = true
        }
    }
    return Triple(runs, projected, drawn)
}

fun main() {
    val eq = SkyLines(ReferenceCircles.ARCS * ReferenceCircles.PER_ARC, ReferenceCircles.ARCS)
    val ecl = SkyLines(ReferenceCircles.ARCS * ReferenceCircles.PER_ARC, ReferenceCircles.ARCS)
    ReferenceLines.fill(eq, null)
    val obl = Ephemeris.trueObliquityDeg(1_756_000_000_000L)
    ReferenceLines.fill(ecl, obl)

    println("obliquity used: %.6f deg".format(obl))
    println("equator: %d runs, %d vertices; ecliptic: %d runs, %d vertices"
        .format(eq.lines, eq.count, ecl.lines, ecl.count))

    // Every vertex must be a unit vector, and the equator's must be exactly on the equator.
    var worstUnit = 0.0
    var worstZ = 0.0
    for (i in 0 until eq.count) {
        val n = sqrt(eq.vx[i] * eq.vx[i] + eq.vy[i] * eq.vy[i] + eq.vz[i] * eq.vz[i])
        worstUnit = maxOf(worstUnit, abs(n - 1.0))
        worstZ = maxOf(worstZ, abs(eq.vz[i]))
    }
    var worstEclUnit = 0.0
    var worstLat = 0.0
    for (i in 0 until ecl.count) {
        val n = sqrt(ecl.vx[i] * ecl.vx[i] + ecl.vy[i] * ecl.vy[i] + ecl.vz[i] * ecl.vz[i])
        worstEclUnit = maxOf(worstEclUnit, abs(n - 1.0))
        worstLat = maxOf(worstLat, abs(Math.toDegrees(asin(ecl.vz[i]))))
    }
    println("unit-vector error: equator %.3e  ecliptic %.3e".format(worstUnit, worstEclUnit))
    println("equator max |z| = %.3e ; ecliptic max |declination| = %.6f (obliquity %.6f)"
        .format(worstZ, worstLat, obl))

    // The worst case for the cap test: point exactly AT the line, so only the runs near the look
    // direction can help. Sweep right ascension so no arc boundary is special.
    println()
    println(" fov     worst-runs  worst-proj  worst-drawn   (over 360 look directions on each circle)")
    for (fov in doubleArrayOf(150.0, 90.0, 60.0, 20.0, 5.0, 1.0, SkyProjection.MIN_FOV_DEG)) {
        var wr = 99; var wp = 999_999; var wd = 999_999
        var mr = 0; var mp = 0
        for (deg in 0 until 360) {
            for ((lines, isEcl) in listOf(eq to false, ecl to true)) {
                val v = DoubleArray(3)
                if (isEcl) ReferenceCircles.eclipticPoint(deg.toDouble(), obl, v)
                else ReferenceCircles.equatorPoint(deg.toDouble(), v)
                val (ra, dec) = eqOf(v)
                val (r, p, d) = survey(lines, ra, dec, fov)
                if (r < wr) wr = r
                if (p < wp) wp = p
                if (d < wd) wd = d
                if (r > mr) mr = r
                if (p > mp) mp = p
            }
        }
        println("%6.2f   %3d (max %2d)  %5d (max %5d)  %5d".format(fov, wr, mr, wp, mp, wd))
    }
}
