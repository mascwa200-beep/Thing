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
 * The algorithms are Meeus, *Astronomical Algorithms*: chapter 25 for the Sun and the full chapter
 * 47 tables for the Moon, both on Terrestrial Time and both carrying the nutation, plus the five
 * planetary-perturbation terms from Meeus's earlier book that chapter 25 leaves out.
 *
 * ⚠️ **Measured against JPL DE421 rather than quoted from the books: the Moon is within 7.4
 * arcseconds and the Sun within 11.2.** Each of those numbers is the end of a chain of defects
 * found by making that comparison and not by reading the code — the tables were truncated to 25 of
 * 60 terms (167 arcsec), they were handed UTC where they want TT (52), the Moon carried no nutation
 * while the Sun did, so the two sat in different frames (14), and then the SUN turned out to be the
 * worse body until its perturbation terms went in (24.5). The guards are
 * [EphemerisTest.geocentricMoonMatchesJplToBetterThanAHundredthOfADegree] and
 * [EphemerisTest.geocentricSunMatchesJplToBetterThanTenArcseconds]; nothing topocentric can see any
 * of this, because the parallax approximation in [moonPosition] is larger than all of it.
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
    /**
     * Julian date on the **UT** scale — the one the Earth's rotation keeps.
     *
     * ⚠️ **This is the right input for sidereal time and the wrong one for a theory.** Where a body
     * IS depends on dynamical time; where it APPEARS depends on how far the Earth has turned, and
     * the two clocks differ by [DELTA_T_SECONDS]. Feeding this to a theory costs the Moon about
     * forty arcseconds; feeding [julianDateTT] to [gmstDeg] would cost a thousand.
     */
    fun julianDate(epochMs: Long): Double = epochMs / 86_400_000.0 + 2440587.5

    /**
     * Julian date on the **Terrestrial Time** scale, which is what every theory below wants.
     *
     * ⚠️ **The Moon's position was out by up to forty arcseconds because this did not exist.**
     * Meeus's series are functions of Julian centuries of TT; they were being handed UTC. The Moon
     * moves 0.55 arcsec a second, so 69 seconds of clock is 38 arcseconds of sky — measured against
     * JPL DE421 the worst error fell from 52 to 14 arcseconds when this was applied, which is the
     * accuracy the full table is supposed to give.
     *
     * The Sun barely notices (0.04 arcsec a second, so under three arcseconds) and sidereal time
     * must NOT have it at all.
     */
    fun julianDateTT(epochMs: Long): Double =
        julianDate(epochMs) + DELTA_T_SECONDS / 86_400.0

    /**
     * TT − UTC, in seconds.
     *
     * ⚠️ **A constant, and measured rather than modelled.** It is 32.184 s of TT − TAI plus the 37
     * leap seconds standing since 2017. Espenak's extrapolation polynomial predicts 75 s for 2026
     * and would be five seconds wrong, because the Earth sped up rather than slowing as the fit
     * assumed — so a formula here would be worse than a number. Checked against real ΔT tables it
     * varies from 67.6 s in 2015 to 70.5 s in 2045, so a constant costs at most about 1.3 s over
     * the whole plausible life of this app, which is 0.7 arcseconds of Moon — one twentieth of what
     * the ephemeris itself is worth.
     *
     * It changes only if a leap second is inserted. None has been since 2016 and the General
     * Conference on Weights and Measures has resolved to stop by 2035.
     */
    const val DELTA_T_SECONDS = 69.184

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
        val t = centuries(julianDateTT(epochMs))
        // Apparent longitude: the true longitude corrected for nutation and aberration.
        //
        // ⚠️ The aberration term is the Sun's alone. The Moon accompanies the Earth, so it takes
        // the light-time correction its own theory already embeds and NOT this one; adding it there
        // as well would be a plausible-looking twenty arcseconds of pure invention.
        return norm360(sunGeometric(t).longitudeDeg - 0.00569 + nutationLongitudeDeg(t))
    }

    /** The Sun's geometric longitude and the Earth-Sun distance, from one solve. */
    private class SolarState(val longitudeDeg: Double, val radiusAu: Double)

    /**
     * Where the Sun geometrically is, referred to the MEAN equinox of date, and how far away.
     *
     * ⚠️ **Geometric on purpose: no aberration, no nutation.** Both of those are corrections to an
     * apparent DIRECTION, and this is used as one side of a vector subtraction — [Comets] turns it
     * around to get where the Earth is. Carrying the Sun's aberration into a position vector would
     * displace the Earth by twenty arcseconds of its own orbital motion, which is not a place the
     * Earth has ever been. [sunApparentLongitudeDeg] adds both back for callers who want a
     * direction, so the two can never disagree about the underlying solve.
     */
    private fun sunGeometric(t: Double): SolarState {
        val l0 = norm360(280.46646 + 36000.76983 * t + 0.0003032 * t * t)
        val m = norm360(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mRad = m * DEG
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mRad) +
            (0.019993 - 0.000101 * t) * sin(2 * mRad) +
            0.000289 * sin(3 * mRad)
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val v = m + c
        val r = (1.000001018 * (1 - e * e)) / (1 + e * cos(v * DEG))
        return SolarState(norm360(l0 + c + solarPerturbationDeg(t)), r)
    }

    /**
     * The pull of Venus, Jupiter and the Moon on the Earth's own orbit, in degrees of solar
     * longitude — Meeus's five-term correction to the low-accuracy solar position.
     *
     * ⚠️ **Without it the SUN is the least accurate body in this file, and that was not the
     * expectation.** Measured against DE421 at the eighteen eclipse epochs of 2025 through 2028
     * with only the equation of centre: the Moon is out by a mean of 2.9 arcseconds and the Sun by
     * 11.2, worst case 24.5 — which is the 0.01 degrees Meeus quotes for that formula, and which
     * dominates every Sun-to-Moon separation this file computes. An eclipse is a separation, so
     * the Sun's error was setting the whole feature's accuracy while the attention was on the Moon.
     *
     * ⚠️ **The epoch shift is the trap and it is written out rather than folded in.** These
     * coefficients come from Meeus's earlier book, where T counts Julian centuries from 1900.0, not
     * from J2000.0 as everywhere else here — and the two differ by exactly one century. Reducing
     * them to J2000 by hand means five modulo-360 subtractions of five-digit numbers, each of which
     * is silently wrong if slipped. `t + 1.0` cannot be got wrong.
     */
    private fun solarPerturbationDeg(t: Double): Double {
        val t19 = t + 1.0
        val a = (153.23 + 22518.7541 * t19) * DEG
        val b = (216.57 + 45037.5082 * t19) * DEG
        val c = (312.69 + 32964.3577 * t19) * DEG
        val d = (350.74 + 445267.1142 * t19 - 0.00144 * t19 * t19) * DEG
        val e = (231.19 + 20.20 * t19) * DEG
        return 0.00134 * cos(a) + 0.00154 * cos(b) + 0.00200 * cos(c) +
            0.00179 * sin(d) + 0.00178 * sin(e)
    }

    /**
     * Nutation in longitude, to first order in the Moon's ascending node — Meeus ch. 22's own
     * shortcut, worth about half an arcsecond against the full 63-term series.
     *
     * ⚠️ **This exists as a function because it was written twice and applied once, and that cost
     * 38 seconds on every eclipse in the catalogue.** The Sun's apparent longitude carried it; the
     * Moon's did not, and Meeus ch. 47 says in as many words that the longitude his tables give is
     * geometric and needs the nutation added to become apparent. So the two bodies sat in frames
     * seventeen arcseconds apart, and every separation measured between them — which is what an
     * eclipse IS — inherited the whole of that as a systematic bias.
     *
     * Measured before and after against JPL DE421 over the eighteen eclipses of 2025 through 2028:
     * a mean timing error of +38 s became +20 s and the worst case 76 s became 53 s. ⚠️ The
     * improvement is unmistakably this and not luck, because it scales the way nutation does — the
     * node sweeps from about +1° to −75° across that window, so the correction those eclipses
     * receive grows from roughly 1 second in early 2025 to 30 in 2028, event by event.
     *
     * ⚠️ **What remains is the theory, not another frame mistake.** Twenty seconds is ten
     * arcseconds of relative motion, which is the accuracy Meeus himself quotes for the truncated
     * ch. 47 tables. Closing it means the full ELP-2000/82 — thousands of terms — for a file that
     * already declines to report contact times. Not proportionate, and said here rather than left
     * for somebody to rediscover.
     */
    private fun nutationLongitudeDeg(t: Double): Double =
        -0.00478 * sin((125.04 - 1934.136 * t) * DEG)

    /** Nutation in obliquity, the same shortcut and for the same reason. */
    private fun nutationObliquityDeg(t: Double): Double =
        0.00256 * cos((125.04 - 1934.136 * t) * DEG)

    /** The true obliquity of the ecliptic — mean, plus the nutation in it. */
    private fun trueObliquityDeg(t: Double): Double = obliquityDeg(t) + nutationObliquityDeg(t)

    /**
     * The true obliquity for an instant — the tilt of the Earth's axis, which is the angle every
     * conversion between ecliptic and equatorial coordinates turns on.
     *
     * ⚠️ Public because [Astrology] needs it and `private` inside this object is invisible even to
     * the same module. It returns the TRUE obliquity rather than the mean one, so a caller gets the
     * same value the Sun and Moon are computed with and the three cannot drift apart — which is the
     * mistake that had those two bodies in different frames until it was measured.
     */
    fun trueObliquityDeg(epochMs: Long): Double = trueObliquityDeg(centuries(julianDateTT(epochMs)))

    /** Geocentric equatorial position of the Sun (Meeus ch. 25, apparent). */
    fun sunEquatorial(epochMs: Long): Equatorial {
        val t = centuries(julianDateTT(epochMs))
        val sun = sunGeometric(t)
        val lambdaRad = norm360(sun.longitudeDeg - 0.00569 + nutationLongitudeDeg(t)) * DEG
        val epsRad = trueObliquityDeg(t) * DEG
        val ra = atan2(cos(epsRad) * sin(lambdaRad), cos(lambdaRad))
        val dec = asin(sin(epsRad) * sin(lambdaRad))
        return Equatorial(norm360(ra / DEG), dec / DEG, sun.radiusAu * AU_KM)
    }

    /** One astronomical unit, in kilometres — the IAU 2012 definition. */
    const val AU_KM = 149_597_870.7

    /** The Sun's altitude and azimuth for an observer. */
    fun sunPosition(latDeg: Double, lonDeg: Double, epochMs: Long): Horizontal =
        toHorizontal(sunEquatorial(epochMs), latDeg, lonDeg, epochMs)

    // ---- the Moon --------------------------------------------------------------------------

    /** Geocentric equatorial position of the Moon (truncated Meeus ch. 47). */
    fun moonEquatorial(epochMs: Long): Equatorial {
        val jd = julianDateTT(epochMs)
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

        // ⚠️ The additive terms, which the truncated version omitted entirely. They are not part of
        // the periodic tables above because they are not lunar at all: A1 and A2 carry the pull of
        // Venus and Jupiter, and A3 the Earth's own flattening. Small — tens of arcseconds — but
        // they are systematic rather than noise, so leaving them out biases the answer rather than
        // scattering it.
        val a1 = norm360(119.75 + 131.849 * t) * DEG
        val a2 = norm360(53.09 + 479264.290 * t) * DEG
        val a3 = norm360(313.45 + 481266.484 * t) * DEG
        val lpRad = lp * DEG
        val fRad = f * DEG
        val mpRad = mp * DEG
        sumL += 3958.0 * sin(a1) + 1962.0 * sin(lpRad - fRad) + 318.0 * sin(a2)
        sumB += -2235.0 * sin(lpRad) + 382.0 * sin(a3) +
            175.0 * sin(a1 - fRad) + 175.0 * sin(a1 + fRad) +
            127.0 * sin(lpRad - mpRad) - 115.0 * sin(lpRad + mpRad)

        // ⚠️ The nutation is what turns Meeus ch. 47's GEOMETRIC longitude into an apparent one, and
        // omitting it here while the Sun carried it put the two bodies seventeen arcseconds apart in
        // frame — see [nutationLongitudeDeg] for what that cost.
        val lambda = norm360(lp + sumL / 1_000_000.0 + nutationLongitudeDeg(t))
        val beta = sumB / 1_000_000.0
        val distance = 385_000.56 + sumR / 1000.0

        val eps = trueObliquityDeg(t) * DEG
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

    /**
     * A body's ecliptic longitude — where it sits along the Sun's own path, which is the coordinate
     * the sky's calendar and the zodiac are both kept in.
     *
     * ⚠️ **The inverse of the rotation [toHorizontal]'s callers have already been through**, using
     * the same true obliquity, so a longitude converted out and back returns exactly where it
     * started. That is what [Astrology] needs and what nothing here could produce: every body's
     * longitude is computed on the way to its right ascension and then discarded — the Moon's as
     * `lambda` in [moonEquatorial], each planet's as an intermediate in the planetary theory.
     * Deriving it back out costs four trigonometric calls and touches none of that working code,
     * where extracting it would have meant restructuring the one function whose accuracy this
     * project has spent a session measuring.
     */
    fun eclipticLongitudeOf(eq: Equatorial, epochMs: Long): Double {
        val eps = trueObliquityDeg(epochMs) * DEG
        val ra = eq.rightAscensionDeg * DEG
        val dec = eq.declinationDeg * DEG
        return norm360(atan2(sin(ra) * cos(eps) + tan(dec) * sin(eps), cos(ra)) / DEG)
    }

    /**
     * A catalogue position at J2000 moved to where it actually is now.
     *
     * ⚠️ **Twenty-two arcminutes by 2026, which is most of the Moon's radius.** The star catalogue
     * this app bundles says, correctly, that ignoring precession is invisible on a chart — a chart
     * draws a star as a dot several arcminutes wide and nobody can point a phone that accurately.
     * That reasoning does not survive contact with an occultation, where the whole question is
     * whether a 0.26-degree disc covers the star, and being a third of a Moon-radius out changes
     * the answer.
     *
     * ⚠️ **Precession AND nutation, because the frame has to match.** Meeus ch. 21 rigorous gives
     * the MEAN equinox of date; the Sun and Moon in this file are in the TRUE equinox, having had
     * the nutation in longitude added. Stopping at the mean equinox would leave a star fifteen
     * arcseconds out of the frame the Moon is in — small against the Moon's disc, and a systematic
     * bias on every contact time, which is exactly the mistake that had the Sun and the Moon
     * themselves in different frames until somebody measured it. So [nutationLongitudeDeg] and
     * [nutationObliquityDeg] are applied here too, through the standard first-order correction to
     * right ascension and declination (Meeus ch. 23).
     *
     * Checked against Skyfield with JPL DE421 for five stars at five epochs from 2000 to 2044:
     * **worst 1.6 arcseconds**, against 15 with precession alone. Proper motion is deliberately
     * absent — for the brightest stars it is under a fifth of an arcsecond a year, so a century of
     * it is smaller than this residual, and the catalogue does not carry it anyway.
     */
    fun precessFromJ2000(raJ2000Deg: Double, decJ2000Deg: Double, epochMs: Long): Equatorial {
        val t = centuries(julianDateTT(epochMs))
        val mean = precessRotate(raJ2000Deg, decJ2000Deg, t, toDate = true)
        val meanRa = mean[0]
        val meanDec = mean[1]

        // Mean equinox to true, so this sits in the same frame as sunEquatorial and moonEquatorial.
        val dPsi = nutationLongitudeDeg(t)
        val dEps = nutationObliquityDeg(t)
        val eps = obliquityDeg(t) * DEG
        val ra = meanRa * DEG
        val dec = meanDec * DEG
        val dRa = (cos(eps) + sin(eps) * sin(ra) * tan(dec)) * dPsi - (cos(ra) * tan(dec)) * dEps
        val dDec = (sin(eps) * cos(ra)) * dPsi + sin(ra) * dEps
        return Equatorial(norm360(meanRa + dRa), meanDec + dDec, distanceKm = 0.0)
    }

    /**
     * The Meeus ch. 21 rigorous precession rotation, both ways, and nothing else.
     *
     * ⚠️ **Rotation only — no nutation.** [precessFromJ2000] adds that on top because a star has to
     * land in the same true-equinox frame the Sun and Moon are in. The reverse direction is used to
     * carry the Earth's own position back to J2000, where it starts from a MEAN-equinox solve and
     * must stay mean, so folding nutation in here would apply it to one caller that wants it and
     * one that does not.
     *
     * ⚠️ The inverse is the transpose of an orthogonal matrix, which is why it is the same three
     * angles with `zeta` and `z` exchanged and the sign of `theta`'s off-diagonal terms flipped,
     * rather than a second set of coefficients that could drift from the first.
     *
     * @return `[rightAscensionDeg, declinationDeg]`.
     */
    private fun precessRotate(raDeg: Double, decDeg: Double, t: Double, toDate: Boolean): DoubleArray {
        val arcsec = 1.0 / 3600.0
        val zetaDeg = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * arcsec
        val zDeg = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * arcsec
        val theta = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * arcsec * DEG
        val d0 = decDeg * DEG
        return if (toDate) {
            val a0 = (raDeg + zetaDeg) * DEG
            val a = cos(d0) * sin(a0)
            val b = cos(theta) * cos(d0) * cos(a0) - sin(theta) * sin(d0)
            val c = sin(theta) * cos(d0) * cos(a0) + cos(theta) * sin(d0)
            doubleArrayOf(norm360(atan2(a, b) / DEG + zDeg), asin(c.coerceIn(-1.0, 1.0)) / DEG)
        } else {
            val a0 = (raDeg - zDeg) * DEG
            val a = cos(d0) * sin(a0)
            val b = cos(theta) * cos(d0) * cos(a0) + sin(theta) * sin(d0)
            val c = -sin(theta) * cos(d0) * cos(a0) + cos(theta) * sin(d0)
            doubleArrayOf(norm360(atan2(a, b) / DEG - zetaDeg), asin(c.coerceIn(-1.0, 1.0)) / DEG)
        }
    }

    /**
     * Where the Earth is, relative to the Sun, in the J2000 mean ecliptic — rectangular, in AU.
     *
     * This is the other half of every minor-body position: an orbit solved from J2000 elements gives
     * the body's place relative to the Sun, and what an observer needs is its place relative to
     * them. Subtracting requires both vectors in ONE frame, which is what this exists to supply.
     *
     * ⚠️ **J2000 rather than of-date, and the difference is not small.** General precession is about
     * fifty arcseconds a year, so by 2026 the two frames are 0.36 degrees apart. Applied to a vector
     * one astronomical unit long that is 0.0063 AU of displacement — seen from a comet five AU away,
     * **four arcminutes**. Treating an of-date solar position as though it were J2000 is therefore
     * not a rounding decision, and this function exists so nobody has to make it.
     *
     * ⚠️ The Sun's geocentric ecliptic latitude is taken as zero. It is really up to about an
     * arcsecond, from the Moon and the planets pulling the Earth out of the plane; at one AU that is
     * five parts in a million of position, and at five AU it is a fifth of an arcsecond — below the
     * accuracy of the orbital elements it will be combined with.
     */
    fun earthHeliocentricJ2000Au(epochMs: Long): DoubleArray {
        val t = centuries(julianDateTT(epochMs))
        val sun = sunGeometric(t)
        // The Earth seen from the Sun is the Sun seen from the Earth, turned around.
        val lon = (sun.longitudeDeg + 180.0) * DEG
        val r = sun.radiusAu
        // Mean ecliptic of date -> mean equatorial of date, so the precession rotation applies.
        val epsDate = obliquityDeg(t) * DEG
        val ra = norm360(atan2(sin(lon) * cos(epsDate), cos(lon)) / DEG)
        val dec = asin((sin(lon) * sin(epsDate)).coerceIn(-1.0, 1.0)) / DEG
        val j2000 = precessRotate(ra, dec, t, toDate = false)
        val ra0 = j2000[0] * DEG
        val dec0 = j2000[1] * DEG
        val xq = r * cos(dec0) * cos(ra0)
        val yq = r * cos(dec0) * sin(ra0)
        val zq = r * sin(dec0)
        // J2000 equatorial -> J2000 ecliptic, the frame the elements are published in.
        val eps0 = obliquityDeg(0.0) * DEG
        return doubleArrayOf(xq, yq * cos(eps0) + zq * sin(eps0), -yq * sin(eps0) + zq * cos(eps0))
    }

    /**
     * The J2000 mean obliquity — the tilt that turns the J2000 ecliptic into the J2000 equator.
     *
     * ⚠️ Derived from [obliquityDeg] rather than written as 23.4392911 so it cannot drift from the
     * value the rest of this file uses; the duplicated-constant mistake this project has corrected
     * repeatedly, most recently across five copies of a colour palette.
     */
    val obliquityJ2000Deg: Double get() = obliquityDeg(0.0)

    /**
     * The same body seen from a point on the surface rather than from the centre of the Earth.
     *
     * ⚠️ **A real vector subtraction, on a flattened Earth.** The Earth is 0.34% shorter through
     * the poles, which moves the observer by up to 21 km — about eleven arcseconds of lunar
     * parallax, comparable to the whole error budget of this file, so it is cheaper to do properly
     * than to argue about.
     *
     * ⚠️ **Whether a body SHOULD go through this is the caller's decision, not this function's.**
     * At 150 million kilometres an Earth radius is nine arcseconds, so the Sun barely moves; the
     * Moon moves by up to a degree, four times its own diameter. [Eclipses] deliberately puts only
     * the Moon through it, because including the Sun would stop the shadow geometry matching the
     * convention every published eclipse magnitude uses. A star is at infinity and a
     * [distanceKm] of zero is returned unchanged, so passing one through costs nothing and is
     * safe.
     */
    fun topocentric(eq: Equatorial, latDeg: Double, lonDeg: Double, epochMs: Long): Equatorial {
        if (eq.distanceKm <= 0.0) return eq
        val lat = latDeg * DEG
        // Geodetic latitude to the two Earth-radii components (Meeus ch. 11), sea level.
        val u = kotlin.math.atan(0.99664719 * tan(lat))
        val rhoSin = 0.99664719 * sin(u)
        val rhoCos = cos(u)

        val lst = (gmstDeg(julianDate(epochMs)) + lonDeg) * DEG
        // Observer, in Earth radii, in the same equatorial frame the body is in.
        val ox = rhoCos * cos(lst)
        val oy = rhoCos * sin(lst)
        val oz = rhoSin

        val r = eq.distanceKm / EARTH_RADIUS_KM
        val ra = eq.rightAscensionDeg * DEG
        val dec = eq.declinationDeg * DEG
        val dx = r * cos(dec) * cos(ra) - ox
        val dy = r * cos(dec) * sin(ra) - oy
        val dz = r * sin(dec) - oz
        val d = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        return Equatorial(
            rightAscensionDeg = norm360(atan2(dy, dx) / DEG),
            declinationDeg = asin((dz / d).coerceIn(-1.0, 1.0)) / DEG,
            distanceKm = d * EARTH_RADIUS_KM,
        )
    }

    /** Equatorial radius, the unit the topocentric vectors are carried in. */
    private const val EARTH_RADIUS_KM = 6378.14

    /**
     * Great-circle angle between two equatorial positions, degrees.
     *
     * ⚠️ **Its resolution floor is about three milliarcseconds, and for two IDENTICAL positions it
     * can return that rather than zero.** This is the standard cosine formula, so for a small angle
     * it takes `acos` of a value within one ulp of 1, where `cos` has thrown the information away:
     * `sin²d + cos²d` lands a bit either side of one and `acos` of that is up to 1.5e-8 radians.
     * Everything in this project that reads a separation — eclipses, occultations, conjunctions —
     * works in arcseconds, four orders of magnitude above the floor, so it is left alone rather
     * than churned. A caller that ever wants sub-arcsecond separations needs the haversine form.
     * Found the hard way: a test comparing a coordinate with itself through this reported three
     * milliarcseconds of drift, and passed or failed depending on the last bit of the declination.
     */
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

    /**
     * Meeus table 47.A in full — all sixty terms, longitude in 1e-6 degrees and distance in
     * 1e-3 km.
     *
     * ⚠️ **This was truncated at twenty-five, and truncation is what the Moon's position error
     * WAS.** Measured against JPL DE421 over a spread of dates, the twenty-five-term series was out
     * by up to 167 arcseconds — nearly a tenth of the Moon's own diameter, and five minutes of
     * timing on anything that depends on where the Moon is relative to something else. The full
     * table costs thirty-five more multiply-adds per call.
     */
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
        Term(2, 0, 0, -2, 15327.0, 10321.0),
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
        Term(4, 0, 0, 0, 3861.0, -11650.0),
        Term(2, 0, -3, 0, 3665.0, 14403.0),
        Term(0, 1, -2, 0, -2689.0, -7003.0),
        Term(2, 0, -1, 2, -2602.0, 0.0),
        Term(2, -1, -2, 0, 2390.0, 10056.0),
        Term(1, 0, 1, 0, -2348.0, 6322.0),
        Term(2, -2, 0, 0, 2236.0, -9884.0),
        Term(0, 1, 2, 0, -2120.0, 5751.0),
        Term(0, 2, 0, 0, -2069.0, 0.0),
        Term(2, -2, -1, 0, 2048.0, -4950.0),
        Term(2, 0, 1, -2, -1773.0, 4130.0),
        Term(2, 0, 0, 2, -1595.0, 0.0),
        Term(4, -1, -1, 0, 1215.0, -3958.0),
        Term(0, 0, 2, 2, -1110.0, 0.0),
        Term(3, 0, -1, 0, -892.0, 3258.0),
        Term(2, 1, 1, 0, -810.0, 2616.0),
        Term(4, -1, -2, 0, 759.0, -1897.0),
        Term(0, 2, -1, 0, -713.0, -2117.0),
        Term(2, 2, -1, 0, -700.0, 2354.0),
        Term(2, 1, -2, 0, 691.0, 0.0),
        Term(2, -1, 0, -2, 596.0, 0.0),
        Term(4, 0, 1, 0, 549.0, -1423.0),
        Term(0, 0, 4, 0, 537.0, -1117.0),
        Term(4, -1, 0, 0, 520.0, -1571.0),
        Term(1, 0, -2, 0, -487.0, -1739.0),
        Term(2, 1, 0, -2, -399.0, 0.0),
        Term(0, 0, 2, -2, -381.0, -4421.0),
        Term(1, 1, 1, 0, 351.0, 0.0),
        Term(3, 0, -2, 0, -340.0, 0.0),
        Term(4, 0, -3, 0, 330.0, 0.0),
        Term(2, -1, 2, 0, 327.0, 0.0),
        Term(0, 2, 1, 0, -323.0, 1165.0),
        Term(1, 1, -1, 0, 299.0, 0.0),
        Term(2, 0, 3, 0, 294.0, 0.0),
        Term(2, 0, -1, -2, 0.0, 8752.0),
    )

    /** Meeus table 47.B in full — all sixty latitude terms, in 1e-6 degrees. */
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
        Term(2, -1, 0, 1, 2211.0),
        Term(2, -1, -1, -1, 2065.0),
        Term(0, 1, -1, -1, -1870.0),
        Term(4, 0, -1, -1, 1828.0),
        Term(0, 1, 0, 1, -1794.0),
        Term(0, 0, 0, 3, -1749.0),
        Term(0, 1, -1, 1, -1565.0),
        Term(1, 0, 0, 1, -1491.0),
        Term(0, 1, 1, 1, -1475.0),
        Term(0, 1, 1, -1, -1410.0),
        Term(0, 1, 0, -1, -1344.0),
        Term(1, 0, 0, -1, -1335.0),
        Term(0, 0, 3, 1, 1107.0),
        Term(4, 0, 0, -1, 1021.0),
        Term(4, 0, -1, 1, 833.0),
        Term(0, 0, 1, -3, 777.0),
        Term(4, 0, -2, 1, 671.0),
        Term(2, 0, 0, -3, 607.0),
        Term(2, 0, 2, -1, 596.0),
        Term(2, -1, 1, -1, 491.0),
        Term(2, 0, -2, 1, -451.0),
        Term(0, 0, 3, -1, 439.0),
        Term(2, 0, 2, 1, 422.0),
        Term(2, 0, -3, -1, 421.0),
        Term(2, 1, -1, 1, -366.0),
        Term(2, 1, 0, 1, -351.0),
        Term(4, 0, 0, 1, 331.0),
        Term(2, -1, 1, 1, 315.0),
        Term(2, -2, 0, -1, 302.0),
        Term(0, 0, 1, 3, -283.0),
        Term(2, 1, 1, -1, -229.0),
        Term(1, 1, 0, -1, 223.0),
        Term(1, 1, 0, 1, 223.0),
        Term(0, 1, -2, -1, -220.0),
        Term(2, 1, -1, -1, -220.0),
        Term(1, 0, 1, 1, -185.0),
        Term(2, -1, -2, -1, 181.0),
        Term(0, 1, 2, 1, -177.0),
        Term(4, 0, -2, -1, 176.0),
        Term(4, -1, -1, -1, 166.0),
        Term(1, 0, 1, -1, -164.0),
        Term(4, 0, 1, -1, 132.0),
        Term(1, 0, -1, -1, -119.0),
        Term(4, -1, 0, -1, 115.0),
        Term(2, -2, 0, 1, 107.0),
    )
}
