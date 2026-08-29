package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * How far a straight screen chord departs from the great circle it is meant to be, MEASURED through
 * the shipped stereographic projection rather than argued about.
 *
 * The KDoc on ReferenceCircles claims `r(1 - cos(step/2))` = "0.017 degrees" at a two-degree step.
 * That formula is the sphere-chord sagitta in LINEAR units on a unit sphere, and viewed from the
 * centre of the sphere every point of a chord lies in the plane of its own great circle — so it is
 * angularly indistinguishable from the arc. Any real error is a PROJECTION artefact, and this
 * projection is stereographic, under which a great circle becomes a circle rather than a line. So
 * measure it.
 */
private fun maxDeviationUnits(
    normal: DoubleArray,
    basis: SkyProjection.Basis,
    stepDeg: Double,
    samples: Int,
): Double {
    // An orthonormal pair spanning the plane whose normal is `normal`.
    val seed = if (abs(normal[2]) < 0.9) doubleArrayOf(0.0, 0.0, 1.0) else doubleArrayOf(1.0, 0.0, 0.0)
    var ax = normal[1] * seed[2] - normal[2] * seed[1]
    var ay = normal[2] * seed[0] - normal[0] * seed[2]
    var az = normal[0] * seed[1] - normal[1] * seed[0]
    val an = sqrt(ax * ax + ay * ay + az * az)
    ax /= an; ay /= an; az /= an
    val bx = normal[1] * az - normal[2] * ay
    val by = normal[2] * ax - normal[0] * az
    val bz = normal[0] * ay - normal[1] * ax

    fun at(tDeg: Double): SkyProjection.Screen {
        val t = Math.toRadians(tDeg)
        val c = cos(t); val s = sin(t)
        return SkyProjection.projectUnit(ax * c + bx * s, ay * c + by * s, az * c + bz * s, basis)
    }

    var worst = 0.0
    var deg = 0.0
    while (deg < 360.0) {
        val p = at(deg)
        val q = at(deg + stepDeg)
        // Only judge segments both of whose ends are near the middle of the picture, which is where
        // a reader would notice. Far out at the rim the stereographic scale is large and the whole
        // question is about visible error, not about the parameterisation.
        if (p.visible && q.visible && p.radius <= 2.2 && q.radius <= 2.2) {
            val dx = q.x - p.x
            val dy = q.y - p.y
            val len = hypot(dx, dy)
            if (len > 1e-12) {
                for (i in 1 until samples) {
                    val m = at(deg + stepDeg * i.toDouble() / samples)
                    if (!m.visible) continue
                    // Perpendicular distance from the true arc point to the straight chord.
                    val d = abs((m.x - p.x) * dy - (m.y - p.y) * dx) / len
                    if (d > worst) worst = d
                }
            }
        }
        deg += stepDeg
    }
    return worst
}

fun main() {
    println("field   offset  worst-units  deg-of-sky   px@1080")
    for (fov in doubleArrayOf(150.0, 90.0, 60.0, 20.0, 5.0, 1.0, 0.25)) {
        val view = SkyProjection.View(azimuthDeg = 0.0, altitudeDeg = 0.0, fovDeg = fov)
        val basis = SkyProjection.basisOf(view)
        val perUnit = SkyProjection.degreesPerUnit(view)
        var worstAll = 0.0
        var worstOffset = -1.0
        // Sweep the circle's distance from the view centre: a circle THROUGH the centre projects to
        // a straight line and has no error at all, so the worst case is somewhere else and guessing
        // which is exactly the mistake this probe exists to avoid.
        var off = 0.0
        while (off <= 89.0) {
            val a = Math.toRadians(off)
            // Normal tilted `off` from the look direction's own normal, so the circle sits `off`
            // degrees from the view centre at closest approach.
            val n = doubleArrayOf(sin(a) * 0.0 + 0.0, sin(a), cos(a))
            val d = maxDeviationUnits(n, basis, ReferenceCircles.STEP_DEG, 16)
            if (d > worstAll) { worstAll = d; worstOffset = off }
            off += 1.0
        }
        println(
            "%6.2f  %5.0f   %11.3e  %10.5f  %8.4f".format(
                fov, worstOffset, worstAll, worstAll * perUnit, worstAll * 540.0,
            ),
        )
    }
}
