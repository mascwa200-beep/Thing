package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the Sun and Moon actually are, and when they rise and set.
 *
 * This replaces three approximations that were each honest about being rough but too rough to
 * point at anything:
 *  - `MoonPhase.at()` is a **mean synodic** model — a fixed 29.53-day cycle counted from a
 *    reference new moon. It ignores the Moon's eccentric orbit, so its phase timing drifts by up
 *    to about half a day, and it offers no altitude, distance or rise time at all.
 *  - `SunCalc.azimuth()` and `MoonCalc.azimuth()` return **azimuth only** — no altitude, so
 *    nothing can tell whether the body is even above the horizon.
 *
 * The algorithms here are Meeus, *Astronomical Algorithms*: chapter 25 for the Sun (good to about
 * 0.01°) and a truncated chapter 47 for the Moon (the 25 largest longitude and distance terms and
 * the 15 largest latitude terms, good to roughly 0.1°). Both are far inside what matters for
 * pointing a phone at the sky or drawing a chart.
 *
 * Angles are degrees, distances kilometres, times epoch milliseconds UTC.
 */
object Ephemeris {

    private const val DEG = Math.PI / 180.0
    private const val J2000 = 2451545.0

    /** A body's position in the observer's sky. */
    data class Horizontal(
        val altitudeDeg: Double,
        val azimuthDeg: Double,
        val distanceKm: Double = 0.0,
    ) {
        val aboveHorizon: Boolean get() = altitudeDeg > 0.0
    }

    /** Equatorial coordinates plus distance — the intermediate every body passes through. */
    data class Equatorial(
        val rightAscensionDeg: Double,
        val declinationDeg: Double,
        val distanceKm: Double,
    )

    /** Rise, transit (highest point) and set for one day. Null means it never crossed. */
    data class RiseSet(
        val riseEpochMs: Long?,
        val transitEpochMs: Long?,
        val setEpochMs: Long?,
        /** True when the body stayed up all day — a polar summer, or a circumpolar star. */
        val alwaysUp: Boolean = false,
        /** True when it never came up at all. */
        val alwaysDown: Boolean = false,
    )

    /** The Moon's appearance, not just its position. */
    data class MoonPhase(
        val illuminatedFraction: Double,
        val phaseAngleDeg: Double,
        /** 0 = new, 0.5 = full, approaching 1 = waning back to new. */
        val cyclePosition: Double,
        val name: String,
        val emoji: String,
        val distanceKm: Double,
        val ageDays: Double,
    ) {
        val waxing: Boolean get() = cyclePosition < 0.5
    }

    // ---- time ------------------------------------------------------------------------------

    /** Julian Date from epoch milliseconds. */
    fun julianDate(epochMs: Long): Double = epochMs / 86_400_000.0 + 2440587.5

    /** Julian centuries since J2000.0. */
    private fun centuries(jd: Double): Double = (jd - J2000) / 36525.0

    /** Greenwich mean sidereal time in degrees. */
    fun gmstDeg(jd: Double): Double {
        val t = centuries(jd)
        val theta = 280.46061837 + 360.98564736629 * (jd - J2000) +
            0.000387933 * t * t - t * t * t / 38_710_000.0
        return norm360(theta)
    }

    // ---- the Sun ---------------------------------------------------------------------------

    /**
     * The Sun's apparent geocentric ecliptic longitude, degrees — **solar longitude**, the coordinate
     * the sky's calendar is actually kept in.
     *
     * ⚠️ **This was computed inside [sunEquatorial] and thrown away**, which is why anything wanting
     * to date a recurring event had to fall back on a calendar date. It is the wrong instrument: a
     * meteor shower peaks when the Earth reaches a fixed point on its orbit, and that point falls on
     * a date that slides by up to a day across the leap-year cycle. Published shower peaks are given
     * as a solar longitude for exactly that reason, and so is the equinox. Now they can be solved
     * for rather than approximated by a day number.
     *
     * ⚠️ [sunEquatorial] calls this rather than repeating the series, so the two can never disagree
     * about where the Sun is — the duplicated-definition mistake this project has corrected seven
     * times over.
     */
    fun sunApparentLongitudeDeg(epochMs: Long): Double {
        val t = centuries(julianDate(epochMs))
        val l0 = norm360(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val m = norm360(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mRad = m * DEG
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mRad) +
            (0.019993 - 0.000101 * t) * sin(2 * mRad) +
            0.000289 * sin(3 * mRad)
        val omega = 125.04 - 1934.136 * t
        // Apparent longitude: the true longitude corrected for nutation and aberration.
        return norm360(l0 + c - 0.00569 - 0.00478 * sin(omega * DEG))
    }

    /** Geocentric equatorial position of the Sun (Meeus ch. 25, apparent). */
    fun sunEquatorial(epochMs: Long): Equatorial {
        val jd = julianDate(epochMs)
        val t = centuries(jd)
        val m = norm360(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mRad = m * DEG
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mRad) +
            (0.019993 - 0.000101 * t) * sin(2 * mRad) +
            0.000289 * sin(3 * mRad)
        val omega = 125.04 - 1934.136 * t
        val lambda = sunApparentLongitudeDeg(epochMs)
        val eps = obliquityDeg(t) + 0.00256 * cos(omega * DEG)
        val lambdaRad = lambda * DEG
        val epsRad = eps * DEG
        val ra = atan2(cos(epsRad) * sin(lambdaRad), cos(lambdaRad))
        val dec = asin(sin(epsRad) * sin(lambdaRad))
        // Radius vector in AU -> km.
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val v = m + c
        val r = (1.000001018 * (1 - e * e)) / (1 + e * cos(v * DEG))
        return Equatorial(norm360(ra / DEG), dec / DEG, r * 149_597_870.7)
    }

    /** The Sun's altitude and azimuth for an observer. */
    fun sunPosition(latDeg: Double, lonDeg: Double, epochMs: Long): Horizontal =
        toHorizontal(sunEquatorial(epochMs), latDeg, lonDeg, epochMs)

    // ---- the Moon --------------------------------------------------------------------------

    /** Geocentric equatorial position of the Moon (truncated Meeus ch. 47). */
    fun moonEquatorial(epochMs: Long): Equatorial {
        val jd = julianDate(epochMs)
        val t = centuries(jd)

        val lp = norm360(218.3164477 + 481267.88123421 * t - 0.0015786 * t * t +
            t.pow(3) / 538841.0 - t.pow(4) / 65_194_000.0)     // mean longitude
        val d = norm360(297.8501921 + 445267.1114034 * t - 0.0018819 * t * t +
            t.pow(3) / 545868.0 - t.pow(4) / 113_065_000.0)    // mean elongation
        val m = norm360(357.5291092 + 35999.0502909 * t - 0.0001536 * t * t +
            t.pow(3) / 24_490_000.0)                            // Sun's mean anomaly
        val mp = norm360(134.9633964 + 477198.8675055 * t + 0.0087414 * t * t +
            t.pow(3) / 69699.0 - t.pow(4) / 14_712_000.0)      // Moon's mean anomaly
        val f = norm360(93.2720950 + 483202.0175233 * t - 0.0036539 * t * t -
            t.pow(3) / 3_526_000.0 + t.pow(4) / 863_310_000.0) // argument of latitude

        // Terms involving the Sun's anomaly are scaled by the Earth's varying eccentricity.
        val ecc = 1.0 - 0.002516 * t - 0.0000074 * t * t

        var sumL = 0.0
        var sumR = 0.0
        for (term in LONGITUDE_TERMS) {
            val arg = (term.d * d + term.m * m + term.mp * mp + term.f * f) * DEG
            val scale = eccScale(ecc, term.m)
            sumL += term.l * sin(arg) * scale
            sumR += term.r * cos(arg) * scale
        }
        var sumB = 0.0
        for (term in LATITUDE_TERMS) {
            val arg = (term.d * d + term.m * m + term.mp * mp + term.f * f) * DEG
            sumB += term.l * sin(arg) * eccScale(ecc, term.m)
        }

        val lambda = norm360(lp + sumL / 1_000_000.0)
        val beta = sumB / 1_000_000.0
        val distance = 385_000.56 + sumR / 1000.0

        val eps = obliquityDeg(t) * DEG
        val lRad = lambda * DEG
        val bRad = beta * DEG
        val ra = atan2(
            sin(lRad) * cos(eps) - tan(bRad) * sin(eps),
            cos(lRad),
        )
        val dec = asin(sin(bRad) * cos(eps) + cos(bRad) * sin(eps) * sin(lRad))
        return Equatorial(norm360(ra / DEG), dec / DEG, distance)
    }

    /** The Moon's altitude and azimuth for an observer, corrected for parallax. */
    fun moonPosition(latDeg: Double, lonDeg: Double, epochMs: Long): Horizontal {
        val eq = moonEquatorial(epochMs)
        val topo = toHorizontal(eq, latDeg, lonDeg, epochMs)
        // The Moon is close enough that an observer on the surface sees it up to ~1 degree lower
        // than a hypothetical observer at the Earth's centre would.
        val parallax = asin(6378.14 / eq.distanceKm) / DEG
        return topo.copy(altitudeDeg = topo.altitudeDeg - parallax * cos(topo.altitudeDeg * DEG))
    }

    /** Illumination, phase name and distance — the readable summary of the Moon right now. */
    fun moonPhase(epochMs: Long): MoonPhase {
        val sun = sunEquatorial(epochMs)
        val moon = moonEquatorial(epochMs)
        // Elongation between the two, then the phase angle at the Moon.
        val elongation = angularSeparationDeg(
            sun.rightAscensionDeg, sun.declinationDeg,
            moon.rightAscensionDeg, moon.declinationDeg,
        )
        val eRad = elongation * DEG
        val phaseAngle = atan2(
            sun.distanceKm * sin(eRad),
            moon.distanceKm - sun.distanceKm * cos(eRad),
        )
        val illuminated = (1 + cos(phaseAngle)) / 2

        // Waxing or waning comes from whether the Moon leads or trails the Sun in ecliptic
        // longitude — illumination alone is symmetric and cannot tell the two halves apart.
        val diff = norm360(moon.rightAscensionDeg - sun.rightAscensionDeg)
        val cycle = diff / 360.0
        return MoonPhase(
            illuminatedFraction = illuminated,
            phaseAngleDeg = phaseAngle / DEG,
            cyclePosition = cycle,
            name = phaseName(cycle),
            emoji = phaseEmoji(cycle),
            distanceKm = moon.distanceKm,
            ageDays = cycle * 29.530588853,
        )
    }

    // ---- rise, set and twilight --------------------------------------------------------------

    /** Standard altitudes (degrees) at which each event is defined to occur. */
    object Altitudes {
        /** Sun's upper limb touching the horizon, including refraction. */
        const val SUNRISE = -0.833
        const val CIVIL_TWILIGHT = -6.0
        const val NAUTICAL_TWILIGHT = -12.0
        const val ASTRONOMICAL_TWILIGHT = -18.0
        /** The Moon's own standard altitude — refraction less its parallax. */
        const val MOONRISE = 0.125
    }

    /**
     * Rise, transit and set for the 24 hours starting at [dayStartEpochMs].
     *
     * Found by sampling the altitude and refining each crossing by bisection rather than by
     * Meeus's interpolation formula: it costs a few hundred cheap evaluations, but it is correct
     * at high latitudes where the interpolation degenerates, and it reports the polar cases
     * honestly instead of returning a fabricated time.
     */
    fun riseSet(
        latDeg: Double,
        lonDeg: Double,
        dayStartEpochMs: Long,
        targetAltitudeDeg: Double = Altitudes.SUNRISE,
        body: (Long) -> Equatorial = ::sunEquatorial,
        stepMinutes: Int = 5,
    ): RiseSet {
        val dayMs = 86_400_000L
        fun altAt(ms: Long) =
            toHorizontal(body(ms), latDeg, lonDeg, ms).altitudeDeg - targetAltitudeDeg

        var rise: Long? = null
        var set: Long? = null
        var transit: Long? = null
        var bestAlt = -1e9
        var previous = altAt(dayStartEpochMs)
        val startedAbove = previous > 0
        val stepMs = stepMinutes * 60_000L

        var ms = dayStartEpochMs + stepMs
        while (ms <= dayStartEpochMs + dayMs) {
            val current = altAt(ms)
            // Track the highest point for transit.
            val absolute = toHorizontal(body(ms), latDeg, lonDeg, ms).altitudeDeg
            if (absolute > bestAlt) { bestAlt = absolute; transit = ms }
            if (previous <= 0 && current > 0 && rise == null) rise = bisect(ms - stepMs, ms, ::altAt)
            if (previous > 0 && current <= 0 && set == null) set = bisect(ms - stepMs, ms, ::altAt)
            previous = current
            ms += stepMs
        }

        val neverCrossed = rise == null && set == null
        return RiseSet(
            riseEpochMs = rise,
            transitEpochMs = transit,
            setEpochMs = set,
            alwaysUp = neverCrossed && startedAbove,
            alwaysDown = neverCrossed && !startedAbove,
        )
    }

    /** Convenience: the Moon's rise/set, using its own standard altitude. */
    fun moonRiseSet(latDeg: Double, lonDeg: Double, dayStartEpochMs: Long): RiseSet =
        riseSet(latDeg, lonDeg, dayStartEpochMs, Altitudes.MOONRISE, ::moonEquatorial)

    /** All three twilights plus sunrise/sunset for one day. */
    data class DayLight(
        val sunrise: Long?,
        val sunset: Long?,
        val solarNoon: Long?,
        val civilDawn: Long?, val civilDusk: Long?,
        val nauticalDawn: Long?, val nauticalDusk: Long?,
        val astronomicalDawn: Long?, val astronomicalDusk: Long?,
        val polarDay: Boolean = false,
        val polarNight: Boolean = false,
    ) {
        /** Daylight length in minutes, when the Sun both rose and set. */
        val daylightMinutes: Int?
            get() = if (sunrise != null && sunset != null && sunset > sunrise)
                ((sunset - sunrise) / 60_000L).toInt() else null
    }

    fun daylight(latDeg: Double, lonDeg: Double, dayStartEpochMs: Long): DayLight {
        val day = riseSet(latDeg, lonDeg, dayStartEpochMs, Altitudes.SUNRISE)
        val civil = riseSet(latDeg, lonDeg, dayStartEpochMs, Altitudes.CIVIL_TWILIGHT)
        val nautical = riseSet(latDeg, lonDeg, dayStartEpochMs, Altitudes.NAUTICAL_TWILIGHT)
        val astro = riseSet(latDeg, lonDeg, dayStartEpochMs, Altitudes.ASTRONOMICAL_TWILIGHT)
        return DayLight(
            sunrise = day.riseEpochMs, sunset = day.setEpochMs, solarNoon = day.transitEpochMs,
            civilDawn = civil.riseEpochMs, civilDusk = civil.setEpochMs,
            nauticalDawn = nautical.riseEpochMs, nauticalDusk = nautical.setEpochMs,
            astronomicalDawn = astro.riseEpochMs, astronomicalDusk = astro.setEpochMs,
            polarDay = day.alwaysUp, polarNight = day.alwaysDown,
        )
    }

    // ---- shared maths -------------------------------------------------------------------------

    /** Equatorial to the observer's horizon. Azimuth is degrees clockwise from true north. */
    fun toHorizontal(eq: Equatorial, latDeg: Double, lonDeg: Double, epochMs: Long): Horizontal {
        val lst = gmstDeg(julianDate(epochMs)) + lonDeg
        val h = norm360(lst - eq.rightAscensionDeg) * DEG
        val lat = latDeg * DEG
        val dec = eq.declinationDeg * DEG
        val alt = asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(h))
        val az = atan2(sin(h), cos(h) * sin(lat) - tan(dec) * cos(lat))
        return Horizontal(alt / DEG, norm360(az / DEG + 180.0), eq.distanceKm)
    }

    /** Great-circle angle between two equatorial positions, degrees. */
    fun angularSeparationDeg(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val d1 = dec1 * DEG
        val d2 = dec2 * DEG
        val dRa = (ra2 - ra1) * DEG
        val cosSep = sin(d1) * sin(d2) + cos(d1) * cos(d2) * cos(dRa)
        return kotlin.math.acos(cosSep.coerceIn(-1.0, 1.0)) / DEG
    }

    private fun obliquityDeg(t: Double): Double =
        23.439291111 - 0.0130041667 * t - 1.638889e-7 * t * t + 5.036111e-7 * t * t * t

    private fun eccScale(ecc: Double, m: Int): Double = when (abs(m)) {
        0 -> 1.0
        1 -> ecc
        else -> ecc * ecc
    }

    private fun norm360(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Refine a crossing to the second by bisection. */
    private fun bisect(lowMs: Long, highMs: Long, f: (Long) -> Double): Long {
        var lo = lowMs
        var hi = highMs
        val fLo = f(lo)
        repeat(40) {
            if (hi - lo <= 1000L) return@repeat
            val mid = lo + (hi - lo) / 2
            if ((f(mid) > 0) == (fLo > 0)) lo = mid else hi = mid
        }
        return lo + (hi - lo) / 2
    }

    private fun phaseName(cycle: Double): String = when {
        cycle < 0.03 || cycle >= 0.97 -> "New moon"
        cycle < 0.22 -> "Waxing crescent"
        cycle < 0.28 -> "First quarter"
        cycle < 0.47 -> "Waxing gibbous"
        cycle < 0.53 -> "Full moon"
        cycle < 0.72 -> "Waning gibbous"
        cycle < 0.78 -> "Last quarter"
        else -> "Waning crescent"
    }

    private fun phaseEmoji(cycle: Double): String = when {
        cycle < 0.03 || cycle >= 0.97 -> "🌑"
        cycle < 0.22 -> "🌒"
        cycle < 0.28 -> "🌓"
        cycle < 0.47 -> "🌔"
        cycle < 0.53 -> "🌕"
        cycle < 0.72 -> "🌖"
        cycle < 0.78 -> "🌗"
        else -> "🌘"
    }

    /** One periodic term: multiples of (D, M, M', F) and its longitude/distance coefficients. */
    private class Term(
        val d: Int, val m: Int, val mp: Int, val f: Int,
        val l: Double, val r: Double = 0.0,
    )

    // Meeus table 47.A, the 25 largest terms. Longitude in 1e-6 degrees, distance in 1e-3 km.
    private val LONGITUDE_TERMS = listOf(
        Term(0, 0, 1, 0, 6288774.0, -20905355.0),
        Term(2, 0, -1, 0, 1274027.0, -3699111.0),
        Term(2, 0, 0, 0, 658314.0, -2955968.0),
        Term(0, 0, 2, 0, 213618.0, -569925.0),
        Term(0, 1, 0, 0, -185116.0, 48888.0),
        Term(0, 0, 0, 2, -114332.0, -3149.0),
        Term(2, 0, -2, 0, 58793.0, 246158.0),
        Term(2, -1, -1, 0, 57066.0, -152138.0),
        Term(2, 0, 1, 0, 53322.0, -170733.0),
        Term(2, -1, 0, 0, 45758.0, -204586.0),
        Term(0, 1, -1, 0, -40923.0, -129620.0),
        Term(1, 0, 0, 0, -34720.0, 108743.0),
        Term(0, 1, 1, 0, -30383.0, 104755.0),
        Term(2, 0, -3, 0, 15327.0, 10321.0),
        Term(0, 0, 1, 2, -12528.0, 0.0),
        Term(0, 0, 1, -2, 10980.0, 79661.0),
        Term(4, 0, -1, 0, 10675.0, -34782.0),
        Term(0, 0, 3, 0, 10034.0, -23210.0),
        Term(4, 0, -2, 0, 8548.0, -21636.0),
        Term(2, 1, -1, 0, -7888.0, 24208.0),
        Term(2, 1, 0, 0, -6766.0, 30824.0),
        Term(1, 0, -1, 0, -5163.0, -8379.0),
        Term(1, 1, 0, 0, 4987.0, -16675.0),
        Term(2, -1, 1, 0, 4036.0, -12831.0),
        Term(2, 0, 2, 0, 3994.0, -10445.0),
    )

    // Meeus table 47.B, the 15 largest latitude terms, in 1e-6 degrees.
    private val LATITUDE_TERMS = listOf(
        Term(0, 0, 0, 1, 5128122.0),
        Term(0, 0, 1, 1, 280602.0),
        Term(0, 0, 1, -1, 277693.0),
        Term(2, 0, 0, -1, 173237.0),
        Term(2, 0, -1, 1, 55413.0),
        Term(2, 0, -1, -1, 46271.0),
        Term(2, 0, 0, 1, 32573.0),
        Term(0, 0, 2, 1, 17198.0),
        Term(2, 0, 1, -1, 9266.0),
        Term(0, 0, 2, -1, 8822.0),
        Term(2, -1, 0, -1, 8216.0),
        Term(2, 0, -2, -1, 4324.0),
        Term(2, 0, 1, 1, 4200.0),
        Term(2, 1, 0, -1, -3359.0),
        Term(2, -1, -1, 1, 2463.0),
    )
}
