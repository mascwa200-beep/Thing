package dev.mascwa.pulse.core.telemetry

import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Run the SHIPPED PlanetDisc against DE421 ground truth, and against the Laplace resonance. */
object DiscProbe

private fun field(row: String, key: String): Double? {
    val i = row.indexOf("\"$key\"")
    if (i < 0) return null
    val c = row.indexOf(':', i)
    var j = c + 1
    while (j < row.length && (row[j] == ' ' || row[j] == '\n')) j++
    var k = j
    while (k < row.length && (row[k].isDigit() || row[k] == '-' || row[k] == '+' ||
            row[k] == '.' || row[k] == 'e' || row[k] == 'E')) k++
    return row.substring(j, k).toDoubleOrNull()
}

fun main() {
    val json = File("scratchpad/sky/jup_ref.json").readText()
    // Crude split on the body objects — enough for a probe, and the values are unambiguous.
    val bodies = listOf("SUN", "MOON", "MERCURY", "VENUS", "MARS", "JUPITER", "SATURN")
    val radii = mapOf(
        "SUN" to 696_000.0, "MOON" to 1737.4, "MERCURY" to 2439.7, "VENUS" to 6051.8,
        "MARS" to 3396.2, "JUPITER" to 71_492.0, "SATURN" to 60_268.0,
    )
    var worstDiam = 0.0
    var worstLit = 0.0
    var worstRing = 0.0
    var n = 0
    // Each top-level entry starts at "iso".
    val rows = json.split("\"iso\"").drop(1)
    for (row in rows) {
        val iso = row.substringAfter("\"").substringBefore("\"")
        println(iso)
        for (b in bodies) {
            val seg = row.substringAfter("\"$b\": {", "").substringBefore("}")
            if (seg.isEmpty()) continue
            val dKm = field(seg, "distanceKm") ?: continue
            val refDiam = field(seg, "diamDeg") ?: continue
            val got = PlanetDisc.apparentDiameterDeg(radii.getValue(b), dKm)
            val relDiam = abs(got - refDiam) / refDiam
            worstDiam = maxOf(worstDiam, relDiam)
            val phase = field(seg, "phaseAngleDeg")
            var line = "  %-8s diam %8.3f\" vs %8.3f\"  rel %.2e".format(
                b, got * 3600, refDiam * 3600, relDiam,
            )
            if (phase != null) {
                val refLit = field(seg, "illuminated")!!
                val lit = PlanetDisc.illuminatedFraction(phase)
                worstLit = maxOf(worstLit, abs(lit - refLit))
                line += "   lit %.5f vs %.5f".format(lit, refLit)
            }
            val refB = field(seg, "ringOpeningDeg")
            if (refB != null) {
                val r = PlanetDisc.rings(field(seg, "raDeg")!!, field(seg, "decDeg")!!)
                worstRing = maxOf(worstRing, abs(r.openingDeg - refB))
                line += "   B %+.4f vs %+.4f  PA %.2f  squash %.4f".format(
                    r.openingDeg, refB, r.positionAngleDeg, r.squash,
                )
            }
            println(line)
            n++
        }
    }
    println()
    println("worst diameter rel error %.3e over %d cases".format(worstDiam, n))
    println("worst illuminated-fraction error %.3e".format(worstLit))
    println("worst ring-opening error %.3e deg".format(worstRing))

    // ---- the Galilean moons, checked against a physical law rather than an ephemeris -------------
    // DE421 holds only Jupiter's barycentre, so there is no satellite ground truth in it. What IS
    // externally known is the LAPLACE RESONANCE: the three inner moons are locked so that
    // l1 - 3*l2 + 2*l3 = 180 degrees at every instant. It is a law of the system, not a fitted
    // number, and a wrong coefficient in ANY of the three longitudes breaks it.
    println()
    var worstLaplace = 0.0
    var t = 1_700_000_000_000L
    repeat(40) {
        val m = PlanetDisc.galileanMoons(t)
        // Recover each longitude from the projected offsets: x = r sin u, and `behind` is cos u > 0.
        val radiiJ = doubleArrayOf(5.9073, 9.3991, 14.9924)
        val ang = DoubleArray(3)
        for (i in 0 until 3) {
            val s = (m[i].x / radiiJ[i]).coerceIn(-1.0, 1.0)
            val a = Math.toDegrees(Math.asin(s))
            ang[i] = if (m[i].behind) a else 180.0 - a
        }
        var res = ang[0] - 3 * ang[1] + 2 * ang[2]
        res = ((res % 360.0) + 360.0) % 360.0
        val off = minOf(abs(res - 180.0), abs(res - 180.0 - 360.0), abs(res - 180.0 + 360.0))
        worstLaplace = maxOf(worstLaplace, off)
        t += 37 * 3_600_000L
    }
    println("Laplace resonance l1 - 3 l2 + 2 l3: worst departure from 180 deg = %.3f deg".format(worstLaplace))

    // Separations must stay inside the orbital radii, and Io's period must come out at 1.769 days.
    var maxIo = 0.0
    var lastSign = 0
    var firstCross = -1L
    var lastCross = -1L
    var crossings = 0
    var u = 1_700_000_000_000L
    repeat(20000) {
        val io = PlanetDisc.galileanMoons(u)[0]
        maxIo = maxOf(maxIo, abs(io.x))
        val sign = if (io.x >= 0) 1 else -1
        if (lastSign != 0 && sign != lastSign && sign == 1) {
            if (firstCross < 0) firstCross = u else lastCross = u
            crossings++
        }
        lastSign = sign
        u += 600_000L // ten minutes
    }
    val period = if (crossings > 1) (lastCross - firstCross) / (crossings - 1.0) / 86_400_000.0 else 0.0
    println("Io: max |x| = %.4f Jovian radii (orbit 5.9073); synodic period %.4f d (true 1.7691 d)"
        .format(maxIo, period))

    // The terminator factor must be the cosine, and its sign must flip at exactly half phase.
    println("terminator: 0deg %.3f  60deg %.3f  90deg %.3f  120deg %.3f  180deg %.3f".format(
        PlanetDisc.terminatorFactor(0.0), PlanetDisc.terminatorFactor(60.0),
        PlanetDisc.terminatorFactor(90.0), PlanetDisc.terminatorFactor(120.0),
        PlanetDisc.terminatorFactor(180.0),
    ))
    println("limb darkening: centre %.3f  half %.3f  limb %.3f".format(
        PlanetDisc.limbDarkening(0.0), PlanetDisc.limbDarkening(0.5), PlanetDisc.limbDarkening(1.0),
    ))
    println("flattening: Saturn %.4f  Jupiter %.4f  Mars %.4f  Venus %.4f".format(
        PlanetDisc.flattening(PlanetDisc.Body.SATURN), PlanetDisc.flattening(PlanetDisc.Body.JUPITER),
        PlanetDisc.flattening(PlanetDisc.Body.MARS), PlanetDisc.flattening(PlanetDisc.Body.VENUS),
    ))
    println("rings: inner %.3f  Cassini %.3f-%.3f  outer %.3f (Jovian... Saturnian radii)".format(
        PlanetDisc.RING_INNER, PlanetDisc.CASSINI_INNER, PlanetDisc.CASSINI_OUTER, PlanetDisc.RING_OUTER,
    ))
    val unused = sin(0.0) + cos(0.0)
    if (unused < -1e9) println("unreachable")
}
