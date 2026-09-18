package dev.mascwa.pulse.core.telemetry

import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Run the SHIPPED galileanMoons against JPL Horizons, in Jupiter's own equatorial frame.
 *
 * The reference file holds, per epoch, each moon's offset already rotated by the position angle of
 * Jupiter's pole — so X is along the orbits' apparent major axis (toward celestial west when the
 * pole is up) and Y is across it, which is exactly what Meeus's method produces.
 */
object MoonProbe

private fun num(row: String, key: String): Double? {
    val i = row.indexOf("\"$key\"")
    if (i < 0) return null
    val c = row.indexOf(':', i)
    var j = c + 1
    while (j < row.length && row[j] == ' ') j++
    var k = j
    while (k < row.length && (row[k].isDigit() || row[k] == '-' || row[k] == '+' ||
            row[k] == '.' || row[k] == 'e' || row[k] == 'E')) k++
    return row.substring(j, k).toDoubleOrNull()
}

fun main() {
    val json = File("scratchpad/sky/moons_ref.json").readText()
    val fmt = SimpleDateFormat("yyyy-MMM-dd HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val names = listOf("Io", "Europa", "Ganymede", "Callisto")
    val worst = DoubleArray(4)
    val sum = DoubleArray(4)
    var n = 0
    var worstPa = 0.0
    val blocks = json.split("\"iso\"").drop(1)
    for (blk in blocks) {
        val iso = blk.substringAfter("\"").substringBefore("\"")
        val ms = fmt.parse(iso)!!.time
        val model = PlanetDisc.galileanMoons(ms)
        // The position angle the reference used, recomputed by the shipped geometry.
        val refP = num(blk, "poleP")!!
        val got = PlanetDisc.axisPositionAngle(
            PlanetDisc.JUPITER_POLE_RA_DEG, PlanetDisc.JUPITER_POLE_DEC_DEG,
            num(blk, "raDeg")!!, num(blk, "decDeg")!!,
        )
        var dp = ((got - refP + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        worstPa = maxOf(worstPa, abs(dp))
        for ((i, name) in names.withIndex()) {
            val seg = blk.substringAfter("\"$name\": {", "").substringBefore("}")
            if (seg.isEmpty()) continue
            val rx = num(seg, "X")!!
            val ry = num(seg, "Y")!!
            val m = model[i]
            val e = hypot(m.x - rx, m.y - ry)
            worst[i] = maxOf(worst[i], e)
            sum[i] += e
            n++
        }
    }
    val epochs = n / 4
    println("epochs: $epochs")
    println("position angle: worst error %.4f deg".format(worstPa))
    println()
    println("%-10s %10s %10s   (Jovian radii)".format("moon", "mean err", "worst err"))
    for ((i, name) in names.withIndex()) {
        println("%-10s %10.4f %10.4f".format(name, sum[i] / epochs, worst[i]))
    }

    // Is what remains a BIAS (still a bug) or scatter (the method's own limit)? A systematic error
    // shows as a mean signed residual comparable to the spread; a random one averages to nothing.
    println()
    println("%-10s %9s %9s   %9s %9s".format("moon", "mean dX", "sd dX", "mean dY", "sd dY"))
    for ((i, name) in names.withIndex()) {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var m = 0
        for (blk in blocks) {
            val seg = blk.substringAfter("\"$name\": {", "").substringBefore("}")
            if (seg.isEmpty()) continue
            val ms = fmt.parse(blk.substringAfter("\"").substringBefore("\""))!!.time
            val mm = PlanetDisc.galileanMoons(ms)[i]
            val dx = mm.x - num(seg, "X")!!
            val dy = mm.y - num(seg, "Y")!!
            sx += dx; sy += dy; sxx += dx * dx; syy += dy * dy; m++
        }
        val mx = sx / m; val my = sy / m
        println("%-10s %9.4f %9.4f   %9.4f %9.4f".format(
            name, mx, Math.sqrt(sxx / m - mx * mx), my, Math.sqrt(syy / m - my * my)))
    }
}
