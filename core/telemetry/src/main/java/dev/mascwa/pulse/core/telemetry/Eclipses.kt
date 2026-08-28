package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * When the Sun and the Moon get in each other's way, and what you would actually see of it.
 *
 * ## How an eclipse is found
 *
 * ⚠️ **By minimising a separation, not by finding a new or full Moon.** "Greatest eclipse" IS the
 * moment of least separation — between the Moon and the Sun for a solar eclipse, between the Moon
 * and the point opposite the Sun for a lunar one — so searching for that directly answers the
 * question being asked and needs no phase-finder, no ecliptic-longitude convention and no special
 * handling of the wrap from 359 degrees to 1. A coarse scan brackets each monthly minimum and a
 * golden-section search refines it; the separation curve is smooth and has exactly one minimum a
 * month, so the bracket cannot be ambiguous.
 *
 * ## What this can honestly tell you, and what it cannot
 *
 * ⚠️ **The limit is measured against JPL DE421 rather than guessed at, and it is the SUN.** That
 * was not the expectation: the shipped [Ephemeris] puts the Moon within 7.4 arcseconds and the Sun
 * within 11.2, so the body everybody worries about is the better of the two here. An eclipse is a
 * separation between them, so the greatest moment lands within 30 seconds of DE421 across the
 * eighteen eclipses of 2025 through 2028, and the magnitude within 0.004. So:
 *
 *  - **Whether an eclipse happens, and of what kind — reliable.** The Earth's umbra is 5000
 *    arcseconds across; ten is a rounding error against it. All eighteen match DE421 exactly. The
 *    only doubt is within a hair of a boundary, which is where the real answer is "grazing" anyway.
 *  - **How much is covered — reliable to about half a per cent.** Measured, not asserted: across
 *    ten sites on three eclipses the magnitude agrees with DE421 to ±0.004, which is 7.6
 *    arcseconds of separation expressed along the Sun's diameter.
 *  - ⚠️ **Whether you are INSIDE the path of totality — not reliable near its edge**, and that same
 *    ±0.004 is why. Checked against DE421 on 2026-08-12: Reykjavik sits inside the real path by
 *    three arcseconds and this file puts it a hair outside. So [Local.borderline] exists and
 *    [advice] says so rather than announcing a totality it cannot stand behind.
 *  - ⚠️ **Contact times — NOT reliable, and this file will not pretend otherwise.** Totality lasts
 *    two to seven minutes and 30 seconds of error is a large fraction of that. [Local] therefore
 *    reports greatest local eclipse and the obscuration there, and no first or fourth contact.
 *
 * ## The one place a shortcut would have been wrong
 *
 * ⚠️ [Ephemeris.moonPosition] corrects the Moon's ALTITUDE for parallax and leaves its azimuth
 * alone, which is a fine approximation for "where do I look" and useless here — the whole solar
 * eclipse is a parallax effect roughly one degree across, and an approximation of that size cannot
 * measure it. [topocentric] does the real thing: build the observer's own position vector on a
 * flattened Earth, subtract it from the body's, and read the direction back out.
 */
object Eclipses {

    /** What kind of eclipse it is, at its greatest. */
    enum class Kind {
        /** The Moon passes only through the Earth's outer shadow — a subtle shading, easily missed. */
        PENUMBRAL_LUNAR,

        /** Part of the Moon enters the true shadow. */
        PARTIAL_LUNAR,

        /** The whole Moon enters the true shadow and usually turns red. */
        TOTAL_LUNAR,

        /** The Moon covers part of the Sun somewhere on Earth. */
        PARTIAL_SOLAR,

        /** The Moon is too far away to cover the Sun, leaving a ring of light. */
        ANNULAR_SOLAR,

        /** The Moon covers the Sun completely somewhere on Earth. */
        TOTAL_SOLAR,
    }

    /**
     * One eclipse, as seen from the Earth's centre.
     *
     * @param magnitude the fraction of the eclipsed body's DIAMETER covered at greatest eclipse —
     *   for a lunar eclipse, of the Moon by the umbra; for a solar one, of the Sun by the Moon as
     *   seen from the centre of the Earth. Above 1 means total. This is the astronomers' magnitude
     *   and is NOT the fraction of area covered, which is smaller.
     * @param penumbralMagnitude the same measure against the outer shadow. Lunar eclipses only.
     */
    data class Eclipse(
        val kind: Kind,
        val greatestEpochMs: Long,
        val magnitude: Double,
        val penumbralMagnitude: Double? = null,
    ) {
        val isSolar: Boolean get() = kind == Kind.PARTIAL_SOLAR ||
            kind == Kind.ANNULAR_SOLAR || kind == Kind.TOTAL_SOLAR

        val isTotal: Boolean get() = kind == Kind.TOTAL_LUNAR || kind == Kind.TOTAL_SOLAR
    }

    /** What a particular place gets to see of it. */
    data class Local(
        /** True when any part of the event is above the horizon from here. */
        val visible: Boolean,
        /** The best moment from HERE, which for a solar eclipse is not the geocentric greatest. */
        val bestEpochMs: Long,
        /** Fraction of the eclipsed body's diameter covered at that moment, from here. 0 if none. */
        val magnitude: Double,
        /** Fraction of the Sun's AREA hidden — the number people mean by "80% eclipse". Solar only. */
        val obscuration: Double,
        /** Altitude of the eclipsed body at that moment. Negative means it has not risen. */
        val altitudeDeg: Double,
        /** True when the Moon is far enough away to leave a ring rather than cover the Sun. */
        val annularHere: Boolean = false,
        /** True when the Sun is wholly covered from here. */
        val totalHere: Boolean = false,
        /**
         * True when this place is so near the edge of the central path that [totalHere] and
         * [annularHere] are inside the ephemeris's own error and cannot be trusted either way.
         *
         * ⚠️ A false here is a real answer; a true is a refusal to give one. Somebody deciding
         * whether to drive two hundred miles needs to know which they have been handed.
         */
        val borderline: Boolean = false,
    )

    private const val DEG = Math.PI / 180.0

    /** Kilometres. The values [Ephemeris] already uses for parallax, kept consistent with it. */
    private const val EARTH_RADIUS_KM = 6378.14
    private const val MOON_RADIUS_KM = 1737.4
    private const val SUN_RADIUS_KM = 696_000.0

    /**
     * ⚠️ **The Earth's shadow is enlarged by 2%, and that is not a fudge.** The geometric shadow of
     * a bare sphere is too small: the atmosphere refracts and absorbs light around the limb, so the
     * shadow that actually falls on the Moon is bigger than trigonometry alone gives. Two per cent
     * is the classical Chauvenet allowance and is what every published lunar-eclipse magnitude is
     * computed with. Leaving it out would make every eclipse read slightly shallower than it is,
     * and would put borderline events on the wrong side of the line.
     */
    private const val SHADOW_ENLARGEMENT = 1.02

    /**
     * Gamma below this and the shadow's axis strikes the Earth, so the eclipse is central — total
     * or annular — for somebody. It is slightly under 1 rather than exactly 1 because the Earth is
     * flattened and the axis can pass over a pole without touching down.
     */
    private const val CENTRAL_LIMIT = 0.9972

    /**
     * Beyond this gamma even the penumbra misses. Between the two the eclipse exists and is partial
     * everywhere, which is the commonest kind and the one the first version of this file could not
     * express.
     */
    private const val ECLIPSE_LIMIT = 1.5433

    /** Coarse scan step. The Moon moves ~3.3 degrees in six hours; a monthly minimum cannot hide. */
    private const val SCAN_STEP_MS = 6L * 3_600_000L

    /** Refine to a second. Finer is meaningless against an ephemeris worth 25 seconds. */
    private const val REFINE_TOLERANCE_MS = 1000L

    /** How far either side of geocentric greatest to look for the best local view. */
    private const val LOCAL_WINDOW_MS = 4L * 3_600_000L

    /**
     * How wrong the Sun-to-Moon separation can be, in degrees — twelve arcseconds.
     *
     * ⚠️ **Three numbers went into this and they disagree, so the choice is written down rather
     * than left to look obvious.** Measured against JPL DE421: the separation error across ten
     * sites on three eclipses is at most **7.6** arcseconds; the two bodies' own worst position
     * errors are 11.2 (Sun) and 7.4 (Moon), which combine to **13.4** in quadrature and **18.6** in
     * the pathological case where they point the same way. Ten site-cases are too few to bound a
     * periodic error, so the sample is not enough on its own; the pathological sum would flag half
     * of Spain.
     *
     * Twelve sits between them, and it errs the safe way on purpose: a false "I cannot tell" costs
     * somebody a glance at a published map, where a false "totality from here" sends them to the
     * wrong side of a line they drove for.
     *
     * It is used for one thing: deciding when the total/annular answer is inside the noise. The
     * central path is a couple of hundred kilometres wide, so this band is its outer few tens of
     * kilometres — small, and exactly where somebody is most likely to be checking.
     */
    private const val SEPARATION_UNCERTAINTY_DEG = 12.0 / 3600.0

    // ---- finding them ---------------------------------------------------------------------------

    /**
     * Every eclipse with its greatest moment inside the window, in time order.
     *
     * Both kinds are searched, so a caller gets the sky's whole calendar rather than one half of it.
     * A year costs about 1500 evaluations of the two theories, which is a few milliseconds.
     */
    fun upcoming(fromEpochMs: Long, throughEpochMs: Long): List<Eclipse> {
        if (throughEpochMs <= fromEpochMs) return emptyList()
        val out = ArrayList<Eclipse>()
        out += minima(fromEpochMs, throughEpochMs) { solarSeparationDeg(it) }.mapNotNull { solarAt(it) }
        out += minima(fromEpochMs, throughEpochMs) { lunarSeparationDeg(it) }.mapNotNull { lunarAt(it) }
        return out.sortedBy { it.greatestEpochMs }
    }

    /**
     * The instants where [f] is at a local minimum inside the window.
     *
     * ⚠️ A minimum is only accepted when it is bracketed on BOTH sides by a larger value. Without
     * that, the first and last samples of the window are reported as minima whenever the function
     * happens to be descending or ascending there, which would invent an eclipse at each end of
     * every range anybody asked for.
     */
    private fun minima(fromMs: Long, toMs: Long, f: (Long) -> Double): List<Long> {
        val out = ArrayList<Long>()
        var a = fromMs
        var fa = f(a)
        var b = a + SCAN_STEP_MS
        var fb = f(b)
        while (b < toMs) {
            val c = b + SCAN_STEP_MS
            val fc = f(c)
            if (fb < fa && fb < fc) {
                val t = refine(a, c, f)
                if (t in fromMs..toMs) out += t
            }
            a = b; fa = fb
            b = c; fb = fc
        }
        return out
    }

    /**
     * Golden-section search for the minimum of [f] inside a bracket.
     *
     * ⚠️ Golden section rather than a derivative method because [f] is evaluated, not
     * differentiated, and a numerical derivative of an angular separation near its minimum is
     * exactly where a finite difference is worst behaved.
     */
    private fun refine(lowMs: Long, highMs: Long, f: (Long) -> Double): Long {
        val phi = 0.6180339887498949
        var lo = lowMs
        var hi = highMs
        var c = hi - ((hi - lo) * phi).toLong()
        var d = lo + ((hi - lo) * phi).toLong()
        var fc = f(c)
        var fd = f(d)
        while (hi - lo > REFINE_TOLERANCE_MS) {
            if (fc < fd) {
                hi = d; d = c; fd = fc
                c = hi - ((hi - lo) * phi).toLong()
                fc = f(c)
            } else {
                lo = c; c = d; fc = fd
                d = lo + ((hi - lo) * phi).toLong()
                fd = f(d)
            }
        }
        return (lo + hi) / 2
    }

    private fun solarSeparationDeg(ms: Long): Double {
        val s = Ephemeris.sunEquatorial(ms)
        val m = Ephemeris.moonEquatorial(ms)
        return Ephemeris.angularSeparationDeg(
            m.rightAscensionDeg, m.declinationDeg, s.rightAscensionDeg, s.declinationDeg,
        )
    }

    private fun lunarSeparationDeg(ms: Long): Double {
        val s = Ephemeris.sunEquatorial(ms)
        val m = Ephemeris.moonEquatorial(ms)
        // The shadow's axis runs from the Sun through the Earth, so it points at the antisolar point.
        return Ephemeris.angularSeparationDeg(
            m.rightAscensionDeg, m.declinationDeg,
            (s.rightAscensionDeg + 180.0) % 360.0, -s.declinationDeg,
        )
    }

    // ---- classifying them -----------------------------------------------------------------------

    /**
     * Is there a lunar eclipse at this instant of least separation, and how deep?
     *
     * The geometry is the standard one: the Earth's shadow at the Moon's distance has an angular
     * radius set by the two bodies' parallaxes and the Sun's apparent size, and the Moon is a disc
     * of its own that can be wholly inside, partly inside, or outside it.
     */
    private fun lunarAt(ms: Long): Eclipse? {
        val s = Ephemeris.sunEquatorial(ms)
        val m = Ephemeris.moonEquatorial(ms)
        val separation = lunarSeparationDeg(ms)

        val moonParallax = asin(EARTH_RADIUS_KM / m.distanceKm) / DEG
        val sunParallax = asin(EARTH_RADIUS_KM / s.distanceKm) / DEG
        val sunSemi = asin(SUN_RADIUS_KM / s.distanceKm) / DEG
        val moonSemi = asin(MOON_RADIUS_KM / m.distanceKm) / DEG

        val umbra = SHADOW_ENLARGEMENT * (moonParallax + sunParallax - sunSemi)
        val penumbra = SHADOW_ENLARGEMENT * (moonParallax + sunParallax + sunSemi)

        val umbral = (umbra + moonSemi - separation) / (2 * moonSemi)
        val penumbral = (penumbra + moonSemi - separation) / (2 * moonSemi)
        if (penumbral <= 0.0) return null

        val kind = when {
            umbral >= 1.0 -> Kind.TOTAL_LUNAR
            umbral > 0.0 -> Kind.PARTIAL_LUNAR
            else -> Kind.PENUMBRAL_LUNAR
        }
        return Eclipse(kind, ms, maxOf(umbral, 0.0), penumbral)
    }

    /**
     * Is there a solar eclipse somewhere on Earth at this instant, and of what kind?
     *
     * ⚠️ **The type is decided by GAMMA, and asking "is the Moon big enough" instead is wrong.**
     * That was the first version of this and running it over four real years caught it: it called
     * 2025-03-29 a total solar eclipse, and that event was partial everywhere on Earth. The Moon
     * was easily large enough; the shadow cone simply missed, passing north of the planet so only
     * the penumbra ever touched it. Apparent size decides total-versus-annular and says nothing
     * about whether anyone stands in the umbra at all.
     *
     * [gammaEarthRadii] is the real quantity: the least distance of the shadow's axis from the
     * Earth's centre. Under 0.9972 the axis strikes the Earth and somebody somewhere is in the
     * cone; beyond that and out to about 1.55 the penumbra still catches a sliver of the planet and
     * the eclipse is partial for everyone; past that nothing sees anything. Those two numbers are
     * the standard central-eclipse and eclipse limits.
     */
    private fun solarAt(ms: Long): Eclipse? {
        val s = Ephemeris.sunEquatorial(ms)
        val m = Ephemeris.moonEquatorial(ms)

        val gamma = gammaEarthRadii(s, m)
        if (gamma > ECLIPSE_LIMIT) return null

        val sunSemi = asin(SUN_RADIUS_KM / s.distanceKm) / DEG
        val moonSemi = asin(MOON_RADIUS_KM / m.distanceKm) / DEG
        val separation = solarSeparationDeg(ms)

        val kind = if (gamma < CENTRAL_LIMIT) {
            // The axis reaches the ground. Whether that is a black Sun or a ring of light is a
            // question of apparent size, judged where the Moon is nearest — beneath it.
            val moonSemiClose = asin(MOON_RADIUS_KM / (m.distanceKm - EARTH_RADIUS_KM)) / DEG
            if (moonSemiClose >= sunSemi) Kind.TOTAL_SOLAR else Kind.ANNULAR_SOLAR
        } else {
            Kind.PARTIAL_SOLAR
        }

        // ⚠️ Geocentric magnitude, which for a solar eclipse routinely reads ZERO for a real and
        // even a total eclipse — the centre of the Earth is not where the eclipse is. It is kept
        // because it is the published convention, and [local] is where a number somebody can act on
        // comes from.
        val magnitude = ((sunSemi + moonSemi - separation) / (2 * sunSemi)).coerceAtLeast(0.0)
        return Eclipse(kind, ms, magnitude)
    }

    /**
     * The least distance of the Moon's shadow axis from the Earth's centre, in equatorial Earth
     * radii — the quantity eclipse catalogues call gamma.
     *
     * The axis is the line from the Sun's centre through the Moon's centre, so this is the ordinary
     * distance from a point to a line in space. Sign is dropped: which side of the Earth the axis
     * passes decides which hemisphere sees it, and nothing here asks that.
     */
    private fun gammaEarthRadii(sun: Ephemeris.Equatorial, moon: Ephemeris.Equatorial): Double {
        val sx = vector(sun)
        val mx = vector(moon)
        val ax = mx[0] - sx[0]
        val ay = mx[1] - sx[1]
        val az = mx[2] - sx[2]
        val len = sqrt(ax * ax + ay * ay + az * az)
        val ux = ax / len
        val uy = ay / len
        val uz = az / len
        // |M x u| is the perpendicular distance from the origin to the line through M along u.
        val cx = mx[1] * uz - mx[2] * uy
        val cy = mx[2] * ux - mx[0] * uz
        val cz = mx[0] * uy - mx[1] * ux
        return sqrt(cx * cx + cy * cy + cz * cz)
    }

    /** Equatorial position as a vector in Earth radii. */
    private fun vector(eq: Ephemeris.Equatorial): DoubleArray {
        val r = eq.distanceKm / EARTH_RADIUS_KM
        val ra = eq.rightAscensionDeg * DEG
        val dec = eq.declinationDeg * DEG
        return doubleArrayOf(r * cos(dec) * cos(ra), r * cos(dec) * sin(ra), r * sin(dec))
    }

    // ---- what one place sees --------------------------------------------------------------------

    /**
     * What [latDeg], [lonDeg] gets to see of [eclipse].
     *
     * A lunar eclipse is the same event for everybody — the Moon really is in shadow — so the only
     * question is whether the Moon is above the horizon. A solar eclipse is different for every
     * observer, so this searches the topocentric separation for the best local moment.
     */
    fun local(eclipse: Eclipse, latDeg: Double, lonDeg: Double): Local {
        if (!eclipse.isSolar) {
            val alt = Ephemeris.moonPosition(latDeg, lonDeg, eclipse.greatestEpochMs).altitudeDeg
            return Local(
                visible = alt > 0.0,
                bestEpochMs = eclipse.greatestEpochMs,
                magnitude = eclipse.magnitude,
                obscuration = 0.0,
                altitudeDeg = alt,
            )
        }

        val best = refine(
            eclipse.greatestEpochMs - LOCAL_WINDOW_MS,
            eclipse.greatestEpochMs + LOCAL_WINDOW_MS,
        ) { topocentricSolarSeparationDeg(it, latDeg, lonDeg) }

        val s = Ephemeris.sunEquatorial(best)
        val moonTopo = topocentric(Ephemeris.moonEquatorial(best), latDeg, lonDeg, best)
        val separation = Ephemeris.angularSeparationDeg(
            moonTopo.rightAscensionDeg, moonTopo.declinationDeg,
            s.rightAscensionDeg, s.declinationDeg,
        )
        val sunSemi = asin(SUN_RADIUS_KM / s.distanceKm) / DEG
        val moonSemi = asin(MOON_RADIUS_KM / moonTopo.distanceKm) / DEG
        val alt = Ephemeris.toHorizontal(s, latDeg, lonDeg, best).altitudeDeg

        val magnitude = ((sunSemi + moonSemi - separation) / (2 * sunSemi)).coerceAtLeast(0.0)
        val touching = separation < sunSemi + moonSemi

        // ⚠️ The central boundary is where the two discs are internally tangent, which is a
        // SEPARATION of |rs - rm| and NOT a magnitude of 1. For a total eclipse those coincide;
        // for an annular one the boundary sits at magnitude rm/rs, which for a real event is
        // around 0.93 — so a band expressed in magnitude would silently never fire for the annular
        // half of this feature. Stating it in separation covers both with one expression.
        val internalContact = abs(sunSemi - moonSemi)
        return Local(
            visible = touching && alt > 0.0,
            bestEpochMs = best,
            magnitude = if (touching) magnitude else 0.0,
            obscuration = if (touching) overlapFraction(separation, sunSemi, moonSemi) else 0.0,
            altitudeDeg = alt,
            annularHere = touching && separation <= sunSemi - moonSemi,
            totalHere = touching && separation <= moonSemi - sunSemi,
            borderline = touching &&
                abs(separation - internalContact) < SEPARATION_UNCERTAINTY_DEG,
        )
    }

    private fun topocentricSolarSeparationDeg(ms: Long, latDeg: Double, lonDeg: Double): Double {
        val s = Ephemeris.sunEquatorial(ms)
        val m = topocentric(Ephemeris.moonEquatorial(ms), latDeg, lonDeg, ms)
        return Ephemeris.angularSeparationDeg(
            m.rightAscensionDeg, m.declinationDeg, s.rightAscensionDeg, s.declinationDeg,
        )
    }

    /**
     * The same body seen from a point on the surface rather than from the centre.
     *
     * ⚠️ **Only the Moon is put through this, and that is deliberate.** At 150 million kilometres
     * an Earth radius is nine arcseconds, so the Sun barely moves — and including it would mean the
     * shadow geometry no longer matched the convention every published eclipse magnitude uses.
     *
     * The transform itself lives in [Ephemeris.topocentric], because an occultation search needs
     * exactly the same one and a second copy of a coordinate rotation is how two features come to
     * disagree about where the Moon is. This wrapper carries the eclipse-specific reasoning above.
     */
    private fun topocentric(
        eq: Ephemeris.Equatorial,
        latDeg: Double,
        lonDeg: Double,
        epochMs: Long,
    ): Ephemeris.Equatorial = Ephemeris.topocentric(eq, latDeg, lonDeg, epochMs)

    /**
     * The fraction of the Sun's disc hidden by the Moon's — the number people mean by "80%".
     *
     * ⚠️ **Not the same as the magnitude, and the difference is large enough to matter.** Magnitude
     * measures along a diameter; obscuration measures area, and because the covered part of a disc
     * near the edge is a thin lens rather than a band, half the diameter covered is only about 39%
     * of the light gone. Reporting one as the other would overstate every partial eclipse.
     *
     * This is the standard area of intersection of two circles, by radii [rs] and [rm] separated
     * by [d], divided by the Sun's own area.
     */
    internal fun overlapFraction(d: Double, rs: Double, rm: Double): Double {
        if (d >= rs + rm) return 0.0
        if (d <= abs(rs - rm)) {
            // One disc is wholly inside the other: total if the Moon covers, annular if it does not.
            return if (rm >= rs) 1.0 else (rm * rm) / (rs * rs)
        }
        val d2 = d * d
        val rs2 = rs * rs
        val rm2 = rm * rm
        val a1 = acos(((d2 + rs2 - rm2) / (2 * d * rs)).coerceIn(-1.0, 1.0))
        val a2 = acos(((d2 + rm2 - rs2) / (2 * d * rm)).coerceIn(-1.0, 1.0))
        val area = rs2 * (a1 - sin(2 * a1) / 2) + rm2 * (a2 - sin(2 * a2) / 2)
        return (area / (Math.PI * rs2)).coerceIn(0.0, 1.0)
    }

    // ---- saying it ------------------------------------------------------------------------------

    /** "Total lunar eclipse", and so on. */
    fun describe(eclipse: Eclipse): String = when (eclipse.kind) {
        Kind.PENUMBRAL_LUNAR -> "Penumbral lunar eclipse"
        Kind.PARTIAL_LUNAR -> "Partial lunar eclipse"
        Kind.TOTAL_LUNAR -> "Total lunar eclipse"
        Kind.PARTIAL_SOLAR -> "Partial solar eclipse"
        Kind.ANNULAR_SOLAR -> "Annular solar eclipse"
        Kind.TOTAL_SOLAR -> "Total solar eclipse"
    }

    /**
     * What it will look like from here, in a sentence.
     *
     * ⚠️ Says what is not visible as plainly as what is. An eclipse happening on the far side of the
     * world is the commonest case by a wide margin, and a screen that simply omits it leaves
     * somebody standing outside at three in the morning.
     */
    fun advice(eclipse: Eclipse, local: Local): String = when {
        !eclipse.isSolar && !local.visible ->
            "Below the horizon from here — this one belongs to the other side of the world."
        !eclipse.isSolar && eclipse.kind == Kind.PENUMBRAL_LUNAR ->
            "The Moon is up, but only the outer shadow reaches it — a faint shading rather than a bite."
        !eclipse.isSolar && eclipse.kind == Kind.TOTAL_LUNAR ->
            "The whole Moon enters the true shadow and should turn coppery. No equipment needed."
        !eclipse.isSolar ->
            "${(eclipse.magnitude * 100).toInt()}% of the Moon's width enters the true shadow. " +
                "Look for the curved edge of the Earth's shadow crossing it."
        // ⚠️ Before the two confident answers, not after. Somebody within a few kilometres of the
        // edge is the one person for whom this matters, and telling them "totality from here" on a
        // figure that is inside its own error is how you send them to the wrong side of a line.
        local.borderline && local.visible && eclipse.kind == Kind.TOTAL_SOLAR ->
            "You are within a few kilometres of the edge of the path of totality, and that is closer " +
                "than this can measure — it could go either way from here. Check a published path map " +
                "before travelling for it."
        local.borderline && local.visible && eclipse.kind == Kind.ANNULAR_SOLAR ->
            "You are on the edge of the path, close enough that whether the ring closes is inside the " +
                "margin of error here. Check a published path map. ⚠️ Filters throughout either way."
        local.totalHere ->
            "Totality from here. ⚠️ Filters until the very last of the Sun goes, then off, then back " +
                "on the instant it returns."
        local.annularHere ->
            "A ring of Sun stays visible from here, so ⚠️ it is never safe to look without a filter."
        local.visible ->
            "${(local.obscuration * 100).toInt()}% of the Sun is hidden from here at best. " +
                "⚠️ Never look without a proper solar filter — a partial eclipse is as damaging as an " +
                "ordinary Sun and far more tempting to stare at."
        local.altitudeDeg <= 0.0 ->
            "The Sun is below the horizon here while it happens."
        else ->
            "Not visible from here — the Moon's shadow falls elsewhere on the Earth."
    }
}
