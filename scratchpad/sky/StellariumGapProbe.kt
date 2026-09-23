package dev.mascwa.pulse.core.telemetry

/**
 * The star map's own coordinate path, run over gap_ref.py's fixture, so Python can score it
 * against Skyfield. Reads `ms<TAB>ra<TAB>dec` (or `ms<TAB>SUN|MOON<TAB>0`) on stdin, writes
 * `ms<TAB>key<TAB>alt<TAB>az`.
 *
 * ⚠️ The star half is EXACTLY what `SkyFrame.horizonOf` does — the true-of-date pair on the way
 * back from the catalogue — with `SkyFrame` itself not compiled here because it lives in
 * `:core:sky`. Keep this in step with that function or the measurement measures a paraphrase.
 * Before S4 the pair was `j2000ToMeanOfDate` + `toHorizontal` on GMST; after S4 it is the
 * true-of-date pair + aberration, and the probe follows.
 */
fun main() {
    val lat = 42.7875
    val lon = -86.1089
    println("# ms\tkey\talt\taz")
    generateSequence(::readLine).forEach { line ->
        if (line.startsWith("#") || line.isBlank()) return@forEach
        val parts = line.split('\t')
        val ms = parts[0].toLong()
        when (parts[1]) {
            "SUN" -> {
                val h = Ephemeris.sunPosition(lat, lon, ms)
                println("$ms\tSUN\t${h.altitudeDeg}\t${h.azimuthDeg}")
            }
            "MOON" -> {
                val h = Ephemeris.moonPosition(lat, lon, ms)
                println("$ms\tMOON\t${h.altitudeDeg}\t${h.azimuthDeg}")
            }
            else -> {
                val ra = parts[1].toDouble()
                val dec = parts[2].toDouble()
                val h = starHorizon(ra, dec, lat, lon, ms)
                println("$ms\t${"%.6f,%.6f".format(java.util.Locale.US, ra, dec)}\t${h.altitudeDeg}\t${h.azimuthDeg}")
            }
        }
    }
}

/** Mirror of SkyFrame.horizonOf. */
private fun starHorizon(raDeg: Double, decDeg: Double, lat: Double, lon: Double, ms: Long): Ephemeris.Horizontal {
    val d = Ephemeris.j2000ToMeanOfDate(raDeg, decDeg, ms)
    return Ephemeris.toHorizontal(Ephemeris.Equatorial(d[0], d[1], 0.0), lat, lon, ms)
}
