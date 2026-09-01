package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * S9 measurement: how far the star map's frame is out for want of precession, and what the
 * corrected numbers actually are — so every assertion that follows is derived from the shipped
 * function on real inputs rather than from a recollection.
 */

private const val D = Math.PI / 180.0

private fun cent(ms: Long) = (Ephemeris.julianDateTT(ms) - 2451545.0) / 36525.0

private fun sep(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
    val a = doubleArrayOf(
        cos(dec1 * D) * cos(ra1 * D), cos(dec1 * D) * sin(ra1 * D), sin(dec1 * D),
    )
    val b = doubleArrayOf(
        cos(dec2 * D) * cos(ra2 * D), cos(dec2 * D) * sin(ra2 * D), sin(dec2 * D),
    )
    val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)
    return acos(dot) / D
}

fun main() {
    val lat = 51.5074
    val lon = -0.1278
    // 2026-08-29T05:00:00Z, and two more so the drift with time is visible.
    val epochs = longArrayOf(1_787_979_600_000L, 1_893_456_000_000L, 2_524_608_000_000L)

    println("=== the frame's own error: an of-date direction treated as J2000 ===")
    for (ms in epochs) {
        val t = cent(ms)
        val year = 2000.0 + t * 100.0
        // The zenith and a look direction, the two vectors SkyFrame builds every frame.
        for ((label, alt, az) in listOf(
            Triple("zenith", 90.0, 0.0),
            Triple("S 45", 45.0, 180.0),
            Triple("N 20", 20.0, 0.0),
            Triple("E horizon", 0.0, 90.0),
        )) {
            val eq = Ephemeris.toEquatorial(Ephemeris.Horizontal(alt, az, 0.0), lat, lon, ms)
            val j = Ephemeris.precessToJ2000(eq.rightAscensionDeg, eq.declinationDeg, t)
            val s = sep(eq.rightAscensionDeg, eq.declinationDeg, j[0], j[1])
            println(
                "  %.1f  %-10s of-date ra %9.4f dec %8.4f  ->  J2000 ra %9.4f dec %8.4f   moved %.4f deg = %.1f arcmin"
                    .format(year, label, eq.rightAscensionDeg, eq.declinationDeg, j[0], j[1], s, s * 60),
            )
        }
    }

    println()
    println("=== the wrong sign lands at twice the offset (the documented failure) ===")
    val ms = epochs[0]
    val t = cent(ms)
    val eq = Ephemeris.toEquatorial(Ephemeris.Horizontal(45.0, 180.0, 0.0), lat, lon, ms)
    val right = Ephemeris.precessToJ2000(eq.rightAscensionDeg, eq.declinationDeg, t)
    val wrong = Ephemeris.precessToJ2000(eq.rightAscensionDeg, eq.declinationDeg, -t)
    println("  correct   moved %.4f deg".format(sep(eq.rightAscensionDeg, eq.declinationDeg, right[0], right[1])))
    println("  negated t moved %.4f deg".format(sep(eq.rightAscensionDeg, eq.declinationDeg, wrong[0], wrong[1])))
    println("  the two answers are %.4f deg apart".format(sep(right[0], right[1], wrong[0], wrong[1])))

    println()
    println("=== the pair is an exact inverse (this is what identify/horizonOf rest on) ===")
    var worst = 0.0
    for (e in epochs) {
        val tt = cent(e)
        var i = 0
        while (i < 360) {
            var dd = -85
            while (dd <= 85) {
                val ra0 = i.toDouble()
                val dec0 = dd.toDouble()
                val j = Ephemeris.precessToJ2000(ra0, dec0, tt)
                // The inverse is precessRotate(toDate = true), which is what j2000ToMeanOfDate will
                // be; approximate it here through precessFromJ2000 minus nutation is not possible,
                // so measure the round trip the other way: J2000 -> date -> J2000.
                val back = Ephemeris.precessToJ2000(j[0], j[1], -tt)
                worst = maxOf(worst, sep(ra0, dec0, back[0], back[1]))
                dd += 5
            }
            i += 15
        }
    }
    println("  worst round trip using the NEGATED rotation as the inverse: %.4f deg".format(worst))
    println("  (large is expected — a negated t is NOT the inverse; the real inverse is toDate=true)")

    println()
    println("=== what the reference circles would be out by if the frame moves and they do not ===")
    // The celestial equator of date, at declination zero, seen in J2000.
    var maxEq = 0.0
    var ra = 0.0
    while (ra < 360.0) {
        val j = Ephemeris.precessToJ2000(ra, 0.0, t)
        maxEq = maxOf(maxEq, sep(ra, 0.0, j[0], j[1]))
        ra += 15.0
    }
    println("  equator vertices move up to %.4f deg = %.1f arcmin".format(maxEq, maxEq * 60))

    println()
    println("=== against the field: how many pixels on a 1080-wide screen ===")
    val err = sep(
        Ephemeris.toEquatorial(Ephemeris.Horizontal(45.0, 180.0, 0.0), lat, lon, ms).rightAscensionDeg,
        Ephemeris.toEquatorial(Ephemeris.Horizontal(45.0, 180.0, 0.0), lat, lon, ms).declinationDeg,
        right[0], right[1],
    )
    for (fov in doubleArrayOf(150.0, 80.0, 20.0, 4.0, 1.0, 0.25)) {
        println("  fov %6.2f deg -> %6.1f%% of the field, %7.0f px".format(fov, 100 * err / fov, 1080 * err / fov))
    }

    println()
    println("=== proper motion: the catalogue's own epoch is J2016.0 ===")
    for (e in epochs) {
        val years = (Ephemeris.julianDate(e) - 2451545.0) / 365.25 + 2000.0 - 2016.0
        // The fastest proper motion the format can hold, and a typical bright star.
        for (pmMasPerYear in doubleArrayOf(10328.0, 500.0, 50.0)) {
            val moved = pmMasPerYear * years / 1000.0
            println("  %.1f yr from J2016 at %8.1f mas/yr -> %8.1f arcsec = %.2f arcmin"
                .format(years, pmMasPerYear, moved, moved / 60))
        }
    }

    println()
    println("=== precession AND nutation together, for scale ===")
    // ⚠️ This line is PRECESSION PLUS NUTATION, not nutation on its own — a first draft of this
    // probe labelled it "nutation" and the 17-arcminute figure would have been read as such.
    // Nutation alone is measured in S9Values by differencing this against the bare mean rotation,
    // and comes out at 5 to 11 ARCSECONDS.
    val star = doubleArrayOf(101.287, -16.716) // Sirius, J2000
    val withNut = Ephemeris.precessFromJ2000(star[0], star[1], ms)
    println("  Sirius J2000 %.4f %.4f -> apparent-of-date %.4f %.4f"
        .format(star[0], star[1], withNut.rightAscensionDeg, withNut.declinationDeg))
    println("  separation %.4f deg = %.1f arcmin"
        .format(
            sep(star[0], star[1], withNut.rightAscensionDeg, withNut.declinationDeg),
            sep(star[0], star[1], withNut.rightAscensionDeg, withNut.declinationDeg) * 60,
        ))

    println()
    println("=== refraction, for the record (S9's other big term) ===")
    for (altDeg in doubleArrayOf(0.0, 1.0, 2.0, 5.0, 10.0, 20.0, 45.0, 80.0)) {
        // Bennett's formula, arcminutes.
        val r = 1.0 / kotlin.math.tan((altDeg + 7.31 / (altDeg + 4.4)) * D)
        println("  altitude %5.1f deg -> refraction %6.2f arcmin".format(altDeg, r))
    }
    println()
    println("=== aberration, for the record ===")
    println("  constant of aberration 20.49552 arcsec = %.4f deg".format(20.49552 / 3600.0))
    // Silence unused warnings.
    if (abs(asin(0.0) + atan2(0.0, 1.0)) > 1.0) println("unreachable")
}
