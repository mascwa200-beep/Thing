package dev.mascwa.pulse.core.telemetry

import java.io.File
import kotlin.math.abs
import kotlin.math.sin

/**
 * S4 measurement probe: the shipped sidereal time and the shipped apparent-star chain against the
 * Skyfield fixtures in `$S/nutation/apparent_ref.tsv` (written by scratchpad/sky/apparent_ref.py).
 * Prints per-row errors and the worst/median, so every bar in EphemerisTest is a number that was
 * read off this output rather than guessed.
 */
fun main(args: Array<String>) {
    val path = args.getOrNull(0) ?: error("usage: S4Probe <apparent_ref.tsv>")
    val lat = 42.7875
    val lon = -86.1089

    println("== sidereal time (degrees) ==")
    for ((ms, sky) in listOf(
        1767225600000L to Triple(100.661152, 100.662533, 100.662224),
        1782777600000L to Triple(278.077576, 278.079660, 278.079454),
    )) {
        val jd = Ephemeris.julianDate(ms)
        val gmst = Ephemeris.gmstDeg(jd)
        val gast = Ephemeris.gastDeg(jd)
        println(
            "$ms gmst=%.6f (skyfield %.6f, diff %.2f\") gast=%.6f (skyfield real-dut1 %.6f diff %.2f\", fixed %.6f diff %.2f\") eqeq ours %.3f\" skyfield %.3f\"".format(
                java.util.Locale.US,
                gmst, sky.first, (gmst - sky.first) * 3600,
                gast, sky.second, (gast - sky.second) * 3600, sky.third, (gast - sky.third) * 3600,
                (gast - gmst) * 3600, (sky.second - sky.first) * 3600,
            ),
        )
    }

    println("== one-term shortcut vs IAU 2000B, 1900-2100 ==")
    var worstShort = 0.0
    var t = -1.0
    while (t <= 1.0) {
        val omega = (125.04 - 1934.136 * t) * Math.PI / 180.0
        val shortcut = -0.00478 * 3600.0 * sin(omega)
        worstShort = maxOf(worstShort, abs(Nutation.arcsec(t)[0] - shortcut))
        t += 0.002
    }
    println("worst |dpsi_full - dpsi_shortcut| = %.3f\"".format(java.util.Locale.US, worstShort))

    println("== apparent stars ==")
    val radec = ArrayList<Double>()
    val altazFixed = ArrayList<Double>()
    val altazReal = ArrayList<Double>()
    File(path).forEachLine { line ->
        if (line.startsWith("#") || line.isBlank()) return@forEachLine
        val p = line.split('\t')
        if (p.size < 11) return@forEachLine
        val ms = p[0].toLongOrNull() ?: return@forEachLine
        val name = p[1]
        val ra = p[2].toDouble(); val dec = p[3].toDouble()
        val raD = p[4].toDouble(); val decD = p[5].toDouble()
        val altF = p[6].toDouble(); val azF = p[7].toDouble()
        val altR = p[8].toDouble(); val azR = p[9].toDouble()
        val ours = Ephemeris.apparentStarOfDate(ra, dec, ms)
        val sep = Ephemeris.angularSeparationDeg(ours[0], ours[1], raD, decD) * 3600
        val h = Ephemeris.apparentStarHorizontal(ra, dec, lat, lon, ms)
        val eF = sepHorizon(h.altitudeDeg, h.azimuthDeg, altF, azF)
        val eR = sepHorizon(h.altitudeDeg, h.azimuthDeg, altR, azR)
        radec += sep; altazFixed += eF; altazReal += eR
        println("%-22s %d radec %.3f\"  altaz(fixed-dut1) %.3f\"  altaz(real-dut1) %.3f\"  alt=%.2f".format(java.util.Locale.US, name, ms, sep, eF, eR, h.altitudeDeg))
    }
    fun stats(l: List<Double>) = "n=${l.size} median=%.3f\" worst=%.3f\"".format(java.util.Locale.US, l.sorted()[l.size / 2], l.max())
    println("RA/Dec of date:        ${stats(radec)}")
    println("alt/az vs fixed-dut1:  ${stats(altazFixed)}")
    println("alt/az vs real-dut1:   ${stats(altazReal)}")
}

private fun sepHorizon(alt1: Double, az1: Double, alt2: Double, az2: Double): Double =
    Ephemeris.angularSeparationDeg(az1, alt1, az2, alt2) * 3600
