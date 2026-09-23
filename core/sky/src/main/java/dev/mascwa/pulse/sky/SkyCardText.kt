package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.PlanetDisc
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the identify card says, in words — the one place the numbers a tap turns up are turned into
 * text, so the two applications' cards cannot phrase a declination differently.
 *
 * Everything here is `Locale.US` on purpose: these strings are numbers a person reads against a
 * catalogue or an almanac, and `5h 55m 10s` has one spelling in every atlas ever printed. The
 * Sexagesimal forms carry their own rounding — the seconds are rounded first and carried into the
 * minutes, so `59.7″` becomes the next minute rather than printing as `60″`.
 *
 * Pure and Android-free; the clock zone is a parameter so a test can pin a wall-clock time.
 */
object SkyCardText {

    /** Right ascension in hours, minutes and whole seconds: `5h 55m 10s`. */
    fun raText(raDeg: Double): String {
        var total = (norm360(raDeg) / 15.0 * 3600.0).roundToLong()
        if (total >= 86_400L) total -= 86_400L
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return "${h}h ${m}m ${s}s"
    }

    /** Declination in signed degrees, arcminutes and whole arcseconds: `+7° 24′ 26″`. */
    fun decText(decDeg: Double): String {
        val total = (abs(decDeg) * 3600.0).roundToLong()
        val d = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val sign = if (decDeg < 0.0) "−" else "+"
        return "$sign$d° $m′ $s″"
    }

    /** An angular size in the unit it is worth reading in: `1.5°`, `31.2′`, `39.1″`. */
    fun sizeText(diameterDeg: Double): String = when {
        diameterDeg >= 1.0 -> "%.1f°".format(Locale.US, diameterDeg)
        diameterDeg * 60.0 >= 1.0 -> "%.1f′".format(Locale.US, diameterDeg * 60.0)
        else -> "%.1f″".format(Locale.US, diameterDeg * 3600.0)
    }

    /**
     * A distance in the unit it is worth reading in: kilometres with thousands grouped for the
     * Moon, astronomical units for everything further. The cut is at a fiftieth of an AU, which is
     * eight times the Moon's distance and a two-hundredth of Venus's nearest approach.
     */
    fun distanceText(distanceKm: Double): String {
        val au = distanceKm / PlanetDisc.AU_KM
        if (au >= 0.02) return "%.2f AU".format(Locale.US, au)
        return grouped(distanceKm.roundToLong()) + " km"
    }

    /** `Alt +35.2° · Az 225.4° SW`, with `· below the horizon` when it is. */
    fun whereLine(azimuthDeg: Double, drawnAltitudeDeg: Double): String {
        val az = norm360(azimuthDeg)
        val base = "Alt %+.1f° · Az %.1f° %s".format(Locale.US, drawnAltitudeDeg, az, cardinal(az))
        return if (drawnAltitudeDeg < 0.0) "$base · below the horizon" else base
    }

    /** `RA 5h 55m 10s · Dec +7° 24′ 26″`, labelled with the frame it is in. */
    fun coordinateLine(label: String, raDeg: Double, decDeg: Double): String =
        "$label  RA ${raText(raDeg)} · Dec ${decText(decDeg)}"

    /** `4.21 AU · 39.1″ across`, or whichever half is known; null when neither is. */
    fun distanceLine(distanceKm: Double?, diameterDeg: Double?): String? {
        val parts = ArrayList<String>(2)
        if (distanceKm != null && distanceKm > 0.0) parts += distanceText(distanceKm)
        if (diameterDeg != null && diameterDeg > 0.0) parts += "${sizeText(diameterDeg)} across"
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /**
     * Today's rise, highest point and set as local wall-clock times, or the polar truth when there
     * is no crossing: `Rises 18:42 · highest 01:15 · sets 07:48`.
     *
     * A body already up at the start of the day has no rise today and the line simply omits it —
     * `Highest 01:15 · sets 07:48` — rather than inventing yesterday's.
     */
    fun riseSetLine(rs: Ephemeris.RiseSet?, zone: TimeZone): String? {
        if (rs == null) return null
        val transit = rs.transitEpochMs?.let { "highest ${SkyClock.formatTime(it, zone)}" }
        if (rs.alwaysUp) return listOfNotNull("Up all day today", transit).joinToString(" · ")
        if (rs.alwaysDown) return "Below the horizon all day today"
        val parts = listOfNotNull(
            rs.riseEpochMs?.let { "rises ${SkyClock.formatTime(it, zone)}" },
            transit,
            rs.setEpochMs?.let { "sets ${SkyClock.formatTime(it, zone)}" },
        )
        if (parts.isEmpty()) return null
        return parts.joinToString(" · ").replaceFirstChar { it.uppercaseChar() }
    }

    /** The compass word for an azimuth, sixteen points: `N`, `NNE`, `NE` … */
    fun cardinal(azimuthDeg: Double): String =
        POINTS[((norm360(azimuthDeg) + 11.25) / 22.5).toInt() % POINTS.size]

    private fun norm360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0.0) d += 360.0
        return d
    }

    /** `384400` → `384,400`, without asking the locale. */
    private fun grouped(n: Long): String {
        val digits = abs(n).toString()
        val sb = StringBuilder()
        for ((i, ch) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return (if (n < 0) "-" else "") + sb
    }

    private val POINTS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
}
