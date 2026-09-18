package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How far apart are the two frames the sky map draws in?
 *
 * Everything the handset aims is drawn twice over: the STARS through
 * `SkyProjection.basisOf(pointForward, pointUp, fov, 0)`, and the horizon, the four compass letters
 * and every tap through `SkyProjection.basisOf(SkyPointing.equivalentView(a, fov))`. Slice 5 makes
 * them one frame; this measures what that is worth, and — the reason it exists — WHICH of the two
 * differences between them actually contributes.
 *
 * ⚠️ **The claim under test is my own, and it is the one I am least sure of.** I wrote in
 * `pointedHorizonBasis`'s KDoc that the view "clamps the altitude AND carries a roll sign the vector
 * pair does not need", implying both cause error. But `SkyPointingTest` asserts the two paths agree,
 * so the negation in `equivalentView` may be exactly what makes them agree — in which case naming it
 * as a source of disagreement is the overstated comment this tree treats as a defect. Measured, in
 * three columns: the real gap, the gap with the clamp alone, and the gap with the roll alone.
 */
private const val D = Math.PI / 180.0

/** A horizon direction as an east/north/up unit vector. */
private fun dir(azDeg: Double, altDeg: Double): DoubleArray {
    val a = azDeg * D
    val h = altDeg * D
    return doubleArrayOf(cos(h) * sin(a), cos(h) * cos(a), sin(h))
}

/**
 * The worst screen-unit gap between two bases, over a spread of horizon directions.
 *
 * Only points BOTH bases put on the screen are compared: a direction one of them declines to draw
 * is a difference of a different kind, and averaging a "not drawn" into a distance would be
 * meaningless. Screen units are the same ones the renderer multiplies by `half`, so 2.0 is the
 * width of the short screen dimension.
 */
private fun worstGap(a: SkyProjection.Basis, b: SkyProjection.Basis): Double {
    var worst = 0.0
    var az = 0.0
    while (az < 360.0) {
        for (alt in doubleArrayOf(0.0, 10.0, 30.0, 60.0, 80.0, -20.0)) {
            val v = dir(az, alt)
            val pa = SkyProjection.projectUnit(v[0], v[1], v[2], a)
            val pb = SkyProjection.projectUnit(v[0], v[1], v[2], b)
            if (!pa.visible || !pb.visible) continue
            // Off-screen points run away to infinity under a stereographic projection, so a
            // comparison out there measures the projection rather than the frames.
            if (abs(pa.x) > 2.0 || abs(pa.y) > 2.0) continue
            worst = maxOf(worst, hypot(pa.x - pb.x, pa.y - pb.y))
        }
        az += 5.0
    }
    return worst
}

fun main() {
    val fov = 60.0
    println("Screen units, where 2.0 is the short screen dimension. Field ${fov}°.")
    println()
    println("%-24s | %10s | %10s | %10s".format("attitude", "real gap", "clamp only", "roll only"))

    var worstReal = 0.0
    var worstRollOnly = 0.0
    for (alt in doubleArrayOf(0.0, 45.0, 80.0, 89.0, 89.4, 89.6, 89.9, -89.9)) {
        for (roll in doubleArrayOf(0.0, 30.0, 90.0, -140.0)) {
            val att = SkyPointing.Attitude(azimuthDeg = 37.0, altitudeDeg = alt, rollDeg = roll)
            val f = DoubleArray(3)
            val u = DoubleArray(3)
            SkyPointing.forward(att, f)
            SkyPointing.screenUp(att, u)

            val vectors = SkyProjection.basisOf(f, u[0], u[1], u[2], fov, 0.0)
            val angles = SkyProjection.basisOf(SkyPointing.equivalentView(att, fov))

            // The clamp alone: the same view with the altitude already inside the limit, so the
            // coerce in basisOf is a no-op and only the roll convention can still differ.
            val unclamped = SkyPointing.Attitude(
                azimuthDeg = att.azimuthDeg,
                altitudeDeg = alt.coerceIn(-SkyProjection.MAX_ALTITUDE_DEG, SkyProjection.MAX_ALTITUDE_DEG),
                rollDeg = roll,
            )
            val fu = DoubleArray(3)
            val uu = DoubleArray(3)
            SkyPointing.forward(unclamped, fu)
            SkyPointing.screenUp(unclamped, uu)
            // Roll only: both sides at the SAME altitude, so anything left is the roll convention.
            val rollOnly = worstGap(
                SkyProjection.basisOf(fu, uu[0], uu[1], uu[2], fov, 0.0),
                SkyProjection.basisOf(SkyPointing.equivalentView(unclamped, fov)),
            )
            // Clamp only: the vector path at the real altitude against the vector path at the
            // clamped one, which isolates what the coerce costs with no roll convention involved.
            val clampOnly = worstGap(
                vectors,
                SkyProjection.basisOf(fu, uu[0], uu[1], uu[2], fov, 0.0),
            )
            val real = worstGap(vectors, angles)
            worstReal = maxOf(worstReal, real)
            worstRollOnly = maxOf(worstRollOnly, rollOnly)
            println(
                "alt %6.1f roll %6.1f     | %10.6f | %10.6f | %10.6f".format(
                    alt, roll, real, clampOnly, rollOnly,
                ),
            )
        }
    }
    println()
    println("worst real gap %.6f   worst gap attributable to the roll alone %.9f".format(worstReal, worstRollOnly))
    println(
        "verdict: " + when {
            worstRollOnly < 1e-9 && worstReal > 1e-6 ->
                "the roll conventions AGREE — the clamp is the whole difference, and the KDoc overstates it"
            worstRollOnly > 1e-6 -> "the roll conventions genuinely differ too"
            else -> "the two frames do not measurably differ at all — the whole slice would be cosmetic"
        },
    )

    // ⚠️ **A tap, not a horizon sample, and the first version of this measured nothing.** Screen
    // units are normalised to the FIELD, so one number at 60° says little; but sweeping HORIZON
    // directions at a 5° field compares no points at all — none of them is on screen — and reported
    // a flat 0.0000, which reads as "no error" and is really "the fixture never reached the branch".
    // What a tap actually asks is where the middle of the screen points, which is defined at every
    // field, so that is what this measures: the same question `identify` asks, both ways.
    println()
    println("Where does a tap in the MIDDLE of the screen resolve to, each way, aimed 89.9° up?")
    println()
    println("%8s | %10s | %s".format("field", "error", "as a fraction of the screen"))
    val att = SkyPointing.Attitude(azimuthDeg = 37.0, altitudeDeg = 89.9, rollDeg = 30.0)
    val f = DoubleArray(3)
    val u = DoubleArray(3)
    SkyPointing.forward(att, f)
    SkyPointing.screenUp(att, u)
    for (field in doubleArrayOf(120.0, 60.0, 20.0, 5.0, 1.0, 0.25)) {
        val vectors = SkyProjection.basisOf(f, u[0], u[1], u[2], field, 0.0)
        val view = SkyPointing.equivalentView(att, field)
        val a = DoubleArray(3)
        SkyProjection.unprojectUnit(0.0, 0.0, vectors, a)
        val (bAz, bAlt) = SkyProjection.unproject(0.0, 0.0, view)
        val b = dir(bAz, bAlt)
        val sep = Math.toDegrees(
            kotlin.math.acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)),
        )
        // A half-field is one screen unit, so the error in screen units is sep / (field / 2).
        val units = sep / (field / 2.0)
        println("%7.2f° | %8.4f° | %.0f%% of the short dimension".format(field, sep, 100.0 * units / 2.0))
    }
}
