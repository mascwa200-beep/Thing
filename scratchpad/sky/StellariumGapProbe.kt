package dev.mascwa.pulse.core.telemetry

/**
 * The star map's own coordinate path, run over gap_ref.py's fixture, so Python can score it
 * against Skyfield. Reads `ms<TAB>ra<TAB>dec` (or `ms<TAB>SUN|MOON<TAB>0`) on stdin, writes
 * `ms<TAB>key<TAB>alt<TAB>az`.
 *
 * ⚠️ The star half is EXACTLY what `SkyFrame.horizonOf` does — the true-of-date pair on the way
 * back from the catalogue — with `SkyFrame` itself not compiled here because it lives in
 * `:core:sky`. Keep this in step with that function or the measurement measures a paraphrase.
 * Before S4 the pair was `j2000ToMeanOfDate` + `toHorizontal` on GMST; since S4 it is
 * `Ephemeris.apparentStarHorizontal` — the true-of-date pair, aberration and GAST — one call.
 */
fun main() {
    val lat = 42.7875
    val lon = -86.1089
    // REFRACT=1 prints APPARENT altitudes, exactly as the chart draws them with the atmosphere on:
    // `Refraction.apparentOf` on the true altitude is what `SkyFrame.project` does per star
    // (through its table, within a tenth of an arcsecond) and what the chart does per body.
    val refract = System.getenv("REFRACT") == "1"
    fun drawn(alt: Double) = if (refract) Refraction.apparentOf(alt) else alt
    println("# ms\tkey\talt\taz")
    generateSequence(::readLine).forEach { line ->
        if (line.startsWith("#") || line.isBlank()) return@forEach
        val parts = line.split('\t')
        val ms = parts[0].toLong()
        when (parts[1]) {
            "SUN" -> {
                val h = Ephemeris.sunPosition(lat, lon, ms)
                println("$ms\tSUN\t${drawn(h.altitudeDeg)}\t${h.azimuthDeg}")
            }
            "MOON" -> {
                val h = Ephemeris.moonPosition(lat, lon, ms)
                println("$ms\tMOON\t${drawn(h.altitudeDeg)}\t${h.azimuthDeg}")
            }
            else -> {
                val ra = parts[1].toDouble()
                val dec = parts[2].toDouble()
                val h = starHorizon(ra, dec, lat, lon, ms)
                println("$ms\t${"%.6f,%.6f".format(java.util.Locale.US, ra, dec)}\t${drawn(h.altitudeDeg)}\t${h.azimuthDeg}")
            }
        }
    }
}

/**
 * Since S4 this IS the shared core function: aberration, the true-of-date pair, apparent sidereal
 * time — the same arithmetic SkyFrame.project + horizonOf do per star, in scalar form, so the probe
 * cannot measure a paraphrase.
 */
private fun starHorizon(raDeg: Double, decDeg: Double, lat: Double, lon: Double, ms: Long): Ephemeris.Horizontal =
    Ephemeris.apparentStarHorizontal(raDeg, decDeg, lat, lon, ms)
