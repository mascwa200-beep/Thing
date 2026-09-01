package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs

/** The exact values every S9 assertion is derived from — computed from the shipped functions. */
fun main() {
    // The five occultation stars already fixtured in EphemerisTest, at its three epochs.
    val stars = listOf(
        Triple("Aldebaran", 68.98016279, 16.50930235),
        Triple("Regulus", 152.09296244, 11.96720878),
        Triple("Spica", 201.29824736, -11.16131949),
        Triple("Antares", 247.35191542, -26.43200261),
        Triple("Alcyone", 56.87116533, 24.10516667),
    )
    val epochs = longArrayOf(1781481600000L, 2051222400000L, 2354331600000L)

    println("=== 1. the pair is an exact inverse ===")
    var worstRt = 0.0
    for (ms in epochs) {
        var ra = 0
        while (ra < 360) {
            var dec = -85
            while (dec <= 85) {
                val j = Ephemeris.meanOfDateToJ2000(ra.toDouble(), dec.toDouble(), ms)
                val back = Ephemeris.j2000ToMeanOfDate(j[0], j[1], ms)
                worstRt = maxOf(
                    worstRt,
                    Ephemeris.angularSeparationDeg(ra.toDouble(), dec.toDouble(), back[0], back[1]),
                )
                dec += 5
            }
            ra += 5
        }
    }
    println("  worst round trip over 72x35x3 = %.3e deg = %.3e arcsec".format(worstRt, worstRt * 3600))

    println()
    println("=== 2. negating the epoch is NOT the inverse and looks like one ===")
    var worstNeg = 0.0
    for (ms in epochs) {
        for ((_, ra, dec) in stars) {
            val j = Ephemeris.meanOfDateToJ2000(ra, dec, ms)
            // the tempting wrong inverse: the same direction with the rotation angles negated
            val t = (Ephemeris.julianDateTT(ms) - 2451545.0) / 36525.0
            val back = Ephemeris.precessToJ2000(j[0], j[1], -t)
            worstNeg = maxOf(worstNeg, Ephemeris.angularSeparationDeg(ra, dec, back[0], back[1]))
        }
    }
    println("  worst error using the negated rotation as the inverse: %.5f deg = %.3f arcsec"
        .format(worstNeg, worstNeg * 3600))

    println()
    println("=== 3. the two directions are twice the drift apart (the swap this catches) ===")
    var minGap = 999.0
    var maxGap = 0.0
    var minDrift = 999.0
    var maxDrift = 0.0
    for (ms in epochs) {
        for ((n, ra, dec) in stars) {
            val back = Ephemeris.meanOfDateToJ2000(ra, dec, ms)
            val fwd = Ephemeris.j2000ToMeanOfDate(ra, dec, ms)
            val gap = Ephemeris.angularSeparationDeg(back[0], back[1], fwd[0], fwd[1])
            val drift = Ephemeris.angularSeparationDeg(ra, dec, fwd[0], fwd[1])
            minGap = minOf(minGap, gap); maxGap = maxOf(maxGap, gap)
            minDrift = minOf(minDrift, drift); maxDrift = maxOf(maxDrift, drift)
            if (ms == epochs[0]) {
                println("  %-10s drift %.4f deg = %5.1f arcmin   the two answers %.4f deg apart"
                    .format(n, drift, drift * 60, gap))
            }
        }
    }
    println("  drift over all: %.4f .. %.4f deg   gap: %.4f .. %.4f deg"
        .format(minDrift, maxDrift, minGap, maxGap))

    println()
    println("=== 4. the mean rotation vs precessFromJ2000 (which carries the nutation) ===")
    var minNut = 999.0
    var maxNut = 0.0
    for (ms in epochs) {
        for ((n, ra, dec) in stars) {
            val mean = Ephemeris.j2000ToMeanOfDate(ra, dec, ms)
            val app = Ephemeris.precessFromJ2000(ra, dec, ms)
            val d = Ephemeris.angularSeparationDeg(
                mean[0], mean[1], app.rightAscensionDeg, app.declinationDeg,
            )
            minNut = minOf(minNut, d); maxNut = maxOf(maxNut, d)
            if (ms == epochs[0]) println("  %-10s nutation moves it %.2f arcsec".format(n, d * 3600))
        }
    }
    println("  over all: %.2f .. %.2f arcsec".format(minNut * 3600, maxNut * 3600))

    println()
    println("=== 5. the vector form agrees with the degree form, and stays a unit vector ===")
    var worstVec = 0.0
    var worstLen = 0.0
    for (ms in epochs) {
        var ra = 0
        while (ra < 360) {
            var dec = -85
            while (dec <= 85) {
                val v = SkyProjection.equatorialVector(ra.toDouble(), dec.toDouble())
                Ephemeris.precessVectorToJ2000(v, ms)
                val j = Ephemeris.meanOfDateToJ2000(ra.toDouble(), dec.toDouble(), ms)
                val w = SkyProjection.equatorialVector(j[0], j[1])
                worstVec = maxOf(
                    worstVec,
                    maxOf(abs(v[0] - w[0]), maxOf(abs(v[1] - w[1]), abs(v[2] - w[2]))),
                )
                worstLen = maxOf(worstLen, abs(v[0] * v[0] + v[1] * v[1] + v[2] * v[2] - 1.0))
                dec += 5
            }
            ra += 5
        }
    }
    println("  worst component disagreement %.3e   worst |v|^2 - 1 %.3e".format(worstVec, worstLen))

    println()
    println("=== 6. the celestial pole: the arbitrary right ascension drops out ===")
    for (ms in epochs.take(1)) {
        for (poleZ in doubleArrayOf(1.0, -1.0)) {
            val v = doubleArrayOf(0.0, 0.0, poleZ)
            Ephemeris.precessVectorToJ2000(v, ms)
            println("  pole z=%+.0f -> (%.9f, %.9f, %.9f)".format(poleZ, v[0], v[1], v[2]))
            for (anyRa in doubleArrayOf(0.0, 90.0, 217.0)) {
                val j = Ephemeris.meanOfDateToJ2000(anyRa, 90.0 * poleZ, ms)
                println("    via ra=%6.1f -> ra %.6f dec %.6f".format(anyRa, j[0], j[1]))
            }
        }
    }

    println()
    println("=== 7. what the map's frame actually moves by ===")
    val lat = 51.5074
    val lon = -0.1278
    for (ms in epochs) {
        val year = 2000.0 + (Ephemeris.julianDateTT(ms) - 2451545.0) / 36525.0 * 100.0
        var lo = 999.0
        var hi = 0.0
        var az = 0
        while (az < 360) {
            for (alt in intArrayOf(0, 30, 60, 90)) {
                val eq = Ephemeris.toEquatorial(
                    Ephemeris.Horizontal(alt.toDouble(), az.toDouble(), 0.0), lat, lon, ms,
                )
                val j = Ephemeris.meanOfDateToJ2000(eq.rightAscensionDeg, eq.declinationDeg, ms)
                val d = Ephemeris.angularSeparationDeg(
                    eq.rightAscensionDeg, eq.declinationDeg, j[0], j[1],
                )
                lo = minOf(lo, d); hi = maxOf(hi, d)
            }
            az += 15
        }
        println("  %.1f: the frame moves %.1f .. %.1f arcmin".format(year, lo * 60, hi * 60))
    }
}
