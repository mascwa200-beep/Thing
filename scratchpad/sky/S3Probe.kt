package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.data.orbital.PlanetCalc
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs

/**
 * S3 measurement: the shipped PlanetCalc / Ephemeris against Skyfield + DE421.
 *
 *   ./scratchpad/sky/runmain.sh scratchpad/sky/S3Probe.kt dev.mascwa.pulse.core.telemetry.S3ProbeKt <planets_ref.json>
 *
 * Prints, per planet, the worst geocentric RA/Dec separation (arcsec), the worst topocentric
 * alt/az error (arcsec, at the reference site 42.7875 N 86.1089 W), and the worst magnitude
 * difference; then the Sun's geocentric error at the reference instants; then the Moon's
 * topocentric error at the EphemerisTest fixtures.
 */
fun main(args: Array<String>) {
    val root = Json.parseToJsonElement(File(args[0]).readText()).jsonArray
    val lat = 42.7875
    val lon = -86.1089
    data class Worst(var radec: Double = 0.0, var altaz: Double = 0.0, var mag: Double = 0.0, var au: Double = 0.0, var where: String = "")
    val worst = linkedMapOf<String, Worst>()
    var sunWorst = 0.0
    for (row in root) {
        val o = row.jsonObject
        val ms = o["ms"]!!.jsonPrimitive.long
        val ours = PlanetCalc.planetsNow(lat, lon, ms, includeOuter = true).associateBy { it.name }
        for ((name, bodyEl) in o["bodies"]!!.jsonObject) {
            val b = bodyEl.jsonObject
            val p = ours.getValue(name)
            val w = worst.getOrPut(name) { Worst() }
            val sep = Ephemeris.angularSeparationDeg(
                p.rightAscensionDeg, p.declinationDeg,
                b["ra"]!!.jsonPrimitive.double, b["dec"]!!.jsonPrimitive.double,
            ) * 3600.0
            if (sep > w.radec) { w.radec = sep; w.where = "$ms" }
            // alt/az: compare as a great-circle separation on the horizon sphere.
            val ha = altAzSepArcsec(p.altitudeDeg, p.azimuthDeg, b["alt"]!!.jsonPrimitive.double, b["az"]!!.jsonPrimitive.double)
            if (ha > w.altaz) w.altaz = ha
            // magSun: the same Mallama formula fed TRUE heliocentric vectors (Skyfield's own
            // planetary_magnitude puts the Sun at the barycentre, a tenth of a magnitude on Mercury).
            val dm = abs(p.magnitude - b["magSun"]!!.jsonPrimitive.double)
            if (name == "Uranus" || name == "Neptune") println(String.format(java.util.Locale.US, "  %-8s %d radec %.3f\"", name, ms, sep))
            if (dm > w.mag) w.mag = dm
            val dau = abs(p.distanceAu - b["au"]!!.jsonPrimitive.double)
            if (dau > w.au) w.au = dau
        }
        val s = o["sun"]!!.jsonObject
        val sun = Ephemeris.sunEquatorial(ms)
        val ssep = Ephemeris.angularSeparationDeg(
            sun.rightAscensionDeg, sun.declinationDeg,
            s["ra"]!!.jsonPrimitive.double, s["dec"]!!.jsonPrimitive.double,
        ) * 3600.0
        if (ssep > sunWorst) sunWorst = ssep
    }
    println("planet   radec\"   altaz\"   mag     au         worst-at")
    for ((n, w) in worst) {
        println(String.format(java.util.Locale.US, "%-8s %7.3f  %7.3f  %6.3f  %.2e  %s", n, w.radec, w.altaz, w.mag, w.au, w.where))
    }
    println(String.format(java.util.Locale.US, "Sun geocentric worst at the 12 planet instants: %.3f arcsec", sunWorst))

    // The EphemerisTest Sun fixtures (apparent RA/Dec of date), copied from the test.
    val sunRefs = listOf(
        Triple(1767225600000L, 281.494713, -23.017248),
        Triple(1782777600000L, 98.979832, 23.181026),
        Triple(1798329600000L, 275.692632, -23.334374),
        Triple(1755000000000L, 142.446750, 14.801528),
        Triple(1743245245000L, 8.262908, 3.565273),
        Triple(1772537622000L, 344.233516, -6.718436),
        Triple(1846520386000L, 106.486203, 22.571245),
        Triple(1847847329000L, 122.015976, 20.181379),
    )
    var w2 = 0.0
    for ((ms, ra, dec) in sunRefs) {
        val s = Ephemeris.sunEquatorial(ms)
        w2 = maxOf(w2, Ephemeris.angularSeparationDeg(s.rightAscensionDeg, s.declinationDeg, ra, dec) * 3600.0)
    }
    println(String.format(java.util.Locale.US, "Sun geocentric worst at the 8 EphemerisTest fixtures: %.3f arcsec (bar is 16.2)", w2))

    // The EphemerisTest topocentric fixtures: sunAlt, sunAz, moonAlt, moonAz.
    data class Ref(val lat: Double, val lon: Double, val ms: Long, val sunAlt: Double, val sunAz: Double, val moonAlt: Double, val moonAz: Double)
    val refs = listOf(
        Ref(51.5074, -0.1278, 1786795200000L, 52.4284, 178.0031, 28.5495, 140.0941),
        Ref(51.5074, -0.1278, 1786762800000L, -14.2455, 43.7696, -37.1414, 18.8990),
        Ref(51.5074, -0.1278, 1795155300000L, -10.9578, 107.9497, -32.1849, 328.9846),
        Ref(-33.8688, 151.2093, 1786795200000L, -56.3891, 240.9730, -22.1944, 252.3616),
        Ref(-33.8688, 151.2093, 1786762800000L, 39.9595, 340.7969, 52.7031, 23.0641),
        Ref(-33.8688, 151.2093, 1769970600000L, -9.7527, 118.1225, 5.9489, 299.5938),
        Ref(-33.8688, 151.2093, 1795155300000L, 28.2573, 264.2551, 25.9800, 66.1628),
        Ref(-1.2921, 36.8219, 1786795200000L, 51.4921, 294.5620, 86.9533, 259.6736),
        Ref(-1.2921, 36.8219, 1786762800000L, -9.3592, 75.9541, -38.6893, 90.2839),
        Ref(-1.2921, 36.8219, 1769970600000L, -38.3152, 247.0935, 37.8426, 63.0551),
        Ref(-1.2921, 36.8219, 1795155300000L, 41.5725, 115.5103, -79.2699, 82.1891),
        Ref(64.1466, -21.9426, 1786795200000L, 37.3223, 151.4312, 11.5454, 122.1277),
        Ref(64.1466, -21.9426, 1786762800000L, -10.0046, 21.5667, -25.9611, 352.6033),
        Ref(64.1466, -21.9426, 1769970600000L, -7.7268, 246.7769, 9.9305, 66.6475),
        Ref(64.1466, -21.9426, 1795155300000L, -23.9997, 85.8365, -15.3190, 310.0305),
    )
    var moonAltW = 0.0; var moonAzW = 0.0; var sunAltW = 0.0; var sunAzW = 0.0
    for (r in refs) {
        val m = Ephemeris.moonPosition(r.lat, r.lon, r.ms)
        moonAltW = maxOf(moonAltW, abs(m.altitudeDeg - r.moonAlt))
        moonAzW = maxOf(moonAzW, angleDiff(m.azimuthDeg, r.moonAz))
        val s = Ephemeris.sunPosition(r.lat, r.lon, r.ms)
        sunAltW = maxOf(sunAltW, abs(s.altitudeDeg - r.sunAlt))
        sunAzW = maxOf(sunAzW, angleDiff(s.azimuthDeg, r.sunAz))
    }
    println(String.format(java.util.Locale.US, "Moon topocentric worst: alt %.4f deg, az %.4f deg (bar 0.03; 4-decimal fixtures)", moonAltW, moonAzW))
    println(String.format(java.util.Locale.US, "Sun topocentric worst: alt %.4f deg, az %.4f deg (bar 0.05)", sunAltW, sunAzW))
}

private fun angleDiff(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180) 360 - d else d
}

private fun altAzSepArcsec(alt1: Double, az1: Double, alt2: Double, az2: Double): Double =
    Ephemeris.angularSeparationDeg(az1, alt1, az2, alt2) * 3600.0
