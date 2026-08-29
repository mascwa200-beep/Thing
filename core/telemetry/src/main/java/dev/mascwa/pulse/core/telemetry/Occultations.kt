package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.asin

/**
 * The Moon passing in front of something, and whether it does so over your head.
 *
 * ## Why this is not just "are they close together"
 *
 * ⚠️ **The Moon's parallax is about a degree, which is FOUR TIMES its own diameter.** Where it sits
 * against the stars depends on where on Earth you are standing to a far greater extent than its own
 * size. So a conjunction that misses by half a degree seen from the centre of the Earth can be a
 * clean occultation from one hemisphere, and one that looks like a direct hit can miss you entirely.
 * This is the same story as the gamma of a solar eclipse, in miniature and much more often — and it
 * is why every occultation prediction in this file comes in two halves. [upcoming] answers "is this
 * possible for somebody", from the Earth's centre and cheaply. [local] answers "does it happen
 * here", by putting the Moon through [Ephemeris.topocentric] and asking again.
 *
 * ## What it can honestly tell you
 *
 * The relative motion is the Moon's own, about 0.55 arcseconds a second against the background. The
 * shipped [Ephemeris] puts the Moon within 7.4 arcseconds and a precessed star within 1.6, so a
 * contact time carries roughly **fifteen seconds** of uncertainty. That is a very different position
 * from [Eclipses], which refuses to give contact times at all: totality lasts minutes and its error
 * is a large fraction of it, where an occultation lasts up to an hour and fifteen seconds is not.
 *
 * ⚠️ **A GRAZE is not decidable here and is not claimed.** Near the northern or southern limit of an
 * occultation the star winks in and out behind lunar mountains, and predicting that needs a limb
 * profile — the real Moon is not the smooth circle this file models, and its edge departs from one by
 * a couple of kilometres either way. So [Local.grazing] is a refusal rather than a prediction: it
 * says the answer is inside the uncertainty, in the same spirit as [Eclipses.Local.borderline].
 *
 * ⚠️ **A planet is a disc, not a point.** Jupiter is about 40 arcseconds across, so its
 * disappearance takes a couple of minutes from first bite to gone. The times here are for the
 * planet's CENTRE, and [describe] says so rather than implying a clean instant.
 *
 * ## What it will not do
 *
 * ⚠️ Aberration is not applied to anything. Annual aberration displaces every body by up to 20.5
 * arcseconds in the SAME direction — it is a function of the Earth's velocity, not of the target —
 * so in a separation between two bodies it very largely cancels. The residual is at the level of the
 * ephemeris error the file already carries, and pretending otherwise would be false precision on top
 * of a Moon good to seven arcseconds.
 */
object Occultations {

    /** What kind of thing the Moon is covering, since it changes what you would see. */
    enum class Kind { PLANET, STAR }

    /**
     * Something the Moon might pass in front of.
     *
     * ⚠️ **The position is a function, not a coordinate**, so a planet can move and a star cannot,
     * and neither has to be hard-coded here. That is deliberate: this module is pure and cannot
     * reach the planetary theory in `:core:feeds` or the bundled star catalogue in the app, and it
     * should not — a star's coordinates belong in the one catalogue the chart also draws from, so
     * the two can never disagree about where a star is.
     *
     * A null return means the position is unavailable at that instant, and the target is skipped
     * rather than guessed at.
     */
    class Target(
        val name: String,
        val kind: Kind,
        /** Visual magnitude, for deciding whether it is worth going outside. Lower is brighter. */
        val magnitude: Double,
        /**
         * ⚠️ **How well the caller knows where this is, in degrees, and it is not a formality.** A
         * star precessed from the bundled catalogue is good to about two arcseconds; a planet from
         * the app's low-precision planetary theory is good to about three arcMINUTES, which is a
         * factor of ninety and a fifth of the Moon's radius. One number for both would either
         * pretend a planet is as well known as a star or refuse to answer for stars it knows
         * perfectly well. So it travels with the target, and [Local.grazing] adds it to the Moon's
         * own error.
         */
        val positionUncertaintyDeg: Double,
        val positionAt: (Long) -> Ephemeris.Equatorial?,
    )

    /** A conjunction close enough that somebody on Earth sees an occultation. */
    class Event(
        val target: Target,
        /** Least separation seen from the centre of the Earth. */
        val greatestEpochMs: Long,
        /** That separation, in degrees. */
        val separationDeg: Double,
        /** The Moon's apparent radius then, geocentric. */
        val moonSemiDeg: Double,
        /** How much of the Moon is lit — a bright Moon drowns a faint star long before it hides it. */
        val moonIlluminatedFraction: Double,
    ) {
        /** True when the Earth's centre itself sees the disc cover the target. */
        val geocentric: Boolean get() = separationDeg < moonSemiDeg
    }

    /** What one place on Earth actually gets. */
    class Local(
        /** True when the Moon's disc covers the target from here at some point. */
        val occulted: Boolean,
        /** The closest approach as seen from here, which is not the geocentric moment. */
        val bestEpochMs: Long,
        /** How close it gets, from here, in degrees. */
        val minSeparationDeg: Double,
        /** The Moon's apparent radius from here — bigger overhead, by up to 1.7%. */
        val moonSemiDeg: Double,
        /** When it goes behind the Moon. Null when it never does, or when it is already behind. */
        val disappearsEpochMs: Long?,
        /** When it comes back out. */
        val reappearsEpochMs: Long?,
        /** The Moon's altitude at closest approach. Negative means none of this is above the horizon. */
        val moonAltitudeDeg: Double,
        /** The Sun's altitude then. Above about -6 and only a planet stands a chance. */
        val sunAltitudeDeg: Double,
        /**
         * True when the closest approach is within the ephemeris's own uncertainty of the Moon's
         * edge, so [occulted] cannot be trusted either way.
         *
         * ⚠️ A false is an answer; a true is a refusal to give one. Somebody deciding whether to
         * drive north for a graze needs to know which they have been handed — and near the limit the
         * real answer depends on lunar mountains this file does not model at all.
         */
        val grazing: Boolean,
    ) {
        /** Above the horizon at the moment worth watching. */
        val visible: Boolean get() = moonAltitudeDeg > 0.0
    }

    private const val DEG = Math.PI / 180.0
    // ⚠️ `internal` for the same reason Eclipses' is — see PlanetDiscTest.
    internal const val MOON_RADIUS_KM = 1737.4

    /**
     * Below this geocentric separation an occultation is possible from SOMEWHERE.
     *
     * The Moon's horizontal parallax reaches 1.02 degrees at perigee and its apparent radius 0.28,
     * so 1.30 is the true bound and this is that plus a margin. Being generous here costs a few
     * extra candidates that [local] then rejects; being tight would silently drop real events.
     */
    private const val CANDIDATE_LIMIT_DEG = 1.5

    /**
     * The relative motion is about half an arcsecond a second, so the Moon crosses this whole
     * window in under three degrees of its own travel and a monthly minimum cannot hide inside one
     * step. The same six hours [Eclipses] scans with, and for the same reason.
     */
    private const val SCAN_STEP_MS = 6L * 3_600_000L

    /** Refine a minimum to the second. Beyond that the ephemeris, not the search, is the limit. */
    private const val REFINE_TOLERANCE_MS = 1000L

    /**
     * How far either side of the geocentric moment the local one can sit.
     *
     * Parallax can displace the Moon by a degree along its own track, and it covers a degree in
     * under two hours, so three is a comfortable bound rather than a tight one.
     */
    private const val LOCAL_WINDOW_MS = 3L * 3_600_000L


    /**
     * How well this app knows where the Moon is: 7.4 arcseconds against DE421, measured. Rounded up
     * to eight, and added to whatever the target's own uncertainty is to give the band inside which
     * an occultation cannot be called either way.
     */
    private const val MOON_UNCERTAINTY_DEG = 8.0 / 3600.0

    // ---- what to point this at, and how far ahead ----------------------------------------------
    //
    // ⚠️ These five values were in the phone's view model and are here now because BOTH consoles run
    // this search. Five star names, two measured uncertainties and a window, restated in two places,
    // is the duplicated-definition drift this project has corrected six times — and the uncertainty
    // pair is the worst possible thing to let drift, since it is what decides whether an occultation
    // is called or refused. The reasoning for the ninety-fold gap is already written on
    // [Target.positionUncertaintyDeg]; the numbers belong beside it.

    /**
     * The four stars bright enough and near enough the ecliptic for the Moon to hide visibly, plus
     * Alcyone in the Pleiades — an occultation of the cluster is the most striking of the lot.
     *
     * These are the only first-magnitude stars the Moon can reach at all: its path is confined to
     * about five degrees either side of the ecliptic, and nothing else that bright lies inside that
     * band.
     *
     * ⚠️ Named rather than given coordinates, deliberately. The positions come from the bundled
     * catalogue the sky chart also draws from, so the chart and this list can never disagree about
     * where a star is — and `StarCatalogTargetsTest` walks the real asset and fails the build if any
     * of these names stops resolving.
     */
    val OCCULTABLE_STARS: List<String> = listOf("Aldebaran", "Regulus", "Spica", "Antares", "Alcyone")

    /**
     * The planets the Moon can pass in front of — all of them, since every planet stays near the
     * ecliptic and the Moon's path crosses it twice a month.
     *
     * Uranus and Neptune are left out: both are occulted regularly and neither is visible without
     * optics, so a card telling somebody to go outside for one would be telling them to go outside
     * for nothing.
     */
    val OCCULTABLE_PLANETS: List<String> = listOf("Mercury", "Venus", "Mars", "Jupiter", "Saturn")

    /**
     * ⚠️ How well each kind of position is known, in degrees, and the two differ by ninety.
     *
     * A star precessed out of the bundled catalogue is within 2 arcseconds of DE421, measured. A
     * planet from the low-precision planetary theory is within 3 arcMINUTES, also measured, across
     * fifty years — a fifth of the Moon's radius, so near the limb a planetary occultation genuinely
     * cannot be called and [Local.grazing] says so.
     */
    const val STAR_UNCERTAINTY_DEG = 2.0 / 3600.0
    const val PLANET_UNCERTAINTY_DEG = 3.0 / 60.0

    /**
     * Six months, deliberately shorter than the two-year eclipse window.
     *
     * ⚠️ **The binding reason is the LENGTH OF THE LIST, not the cost — measured, because the
     * obvious answer was the wrong one.** Timed over ten targets on a desktop JVM: 9/28/57/118 ms of
     * scan for one, three, six and twelve months, returning 5/11/23/38 candidates. So two years
     * would be a few hundred milliseconds, which is affordable; what is not affordable is handing
     * somebody eighty events. The Moon occults something bright every few weeks, so six months is
     * already twenty-odd candidates and [local] then rejects most of them for any one place — three
     * of those twenty-three were actually occulted from London, which is what a parallax four times
     * the Moon's own diameter does.
     *
     * The per-target cost is real and worth knowing: [upcoming] walks the whole window in six-hour
     * steps computing a Moon position at each, so the scan scales with both the window and the
     * number of targets.
     */
    const val HORIZON_MS = 182L * 86_400_000L

    /**
     * Every occultation possible somewhere on Earth between two instants.
     *
     * ⚠️ These are candidates, not sightings. Roughly half of what comes back will miss any given
     * place entirely, because that is what a parallax four times the Moon's own diameter does. Feed
     * each one to [local] before telling anybody to go outside.
     */
    fun upcoming(fromEpochMs: Long, throughEpochMs: Long, targets: List<Target>): List<Event> {
        val out = ArrayList<Event>()
        for (target in targets) {
            val f = { ms: Long -> separationDeg(ms, target) }
            for (t in minima(fromEpochMs, throughEpochMs, f)) {
                val sep = f(t)
                if (!sep.isFinite() || sep > CANDIDATE_LIMIT_DEG) continue
                val moon = Ephemeris.moonEquatorial(t)
                out += Event(
                    target = target,
                    greatestEpochMs = t,
                    separationDeg = sep,
                    moonSemiDeg = semiDiameterDeg(moon.distanceKm),
                    moonIlluminatedFraction = Ephemeris.moonPhase(t).illuminatedFraction,
                )
            }
        }
        return out.sortedBy { it.greatestEpochMs }
    }

    /**
     * What this place sees of it.
     *
     * ⚠️ **Only the Moon is put through the parallax**, exactly as in [Eclipses]. A planet at its
     * nearest is 55 million kilometres away, where an Earth radius subtends a twentieth of an
     * arcsecond; a star has no distance at all and [Ephemeris.topocentric] returns it untouched. So
     * one code path serves both and there is no branch to get wrong.
     */
    fun local(event: Event, latDeg: Double, lonDeg: Double): Local {
        val f = { ms: Long -> topocentricSeparationDeg(ms, event.target, latDeg, lonDeg) }
        val lo = event.greatestEpochMs - LOCAL_WINDOW_MS
        val hi = event.greatestEpochMs + LOCAL_WINDOW_MS

        var best = event.greatestEpochMs
        var bestSep = Double.MAX_VALUE
        var t = lo
        val step = 60_000L
        while (t <= hi) {
            val s = f(t)
            if (s.isFinite() && s < bestSep) { bestSep = s; best = t }
            t += step
        }
        if (bestSep == Double.MAX_VALUE) {
            return Local(
                occulted = false, bestEpochMs = event.greatestEpochMs, minSeparationDeg = Double.NaN,
                moonSemiDeg = event.moonSemiDeg, disappearsEpochMs = null, reappearsEpochMs = null,
                moonAltitudeDeg = -90.0, sunAltitudeDeg = -90.0, grazing = false,
            )
        }
        best = refine((best - step).coerceAtLeast(lo), (best + step).coerceAtMost(hi), f)
        bestSep = f(best)

        val moonHere = Ephemeris.topocentric(Ephemeris.moonEquatorial(best), latDeg, lonDeg, best)
        val semi = semiDiameterDeg(moonHere.distanceKm)
        val covered = bestSep < semi

        // Contacts: where the separation crosses the Moon's edge, either side of closest approach.
        var disappears: Long? = null
        var reappears: Long? = null
        if (covered) {
            val edge = { ms: Long -> f(ms) - semiDiameterDeg(
                Ephemeris.topocentric(Ephemeris.moonEquatorial(ms), latDeg, lonDeg, ms).distanceKm,
            ) }
            if (edge(lo) > 0) disappears = bisect(lo, best, edge)
            if (edge(hi) > 0) reappears = bisect(hi, best, edge)
        }

        val moonAlt = Ephemeris.toHorizontal(moonHere, latDeg, lonDeg, best).altitudeDeg
        val sunAlt = Ephemeris.sunPosition(latDeg, lonDeg, best).altitudeDeg
        return Local(
            occulted = covered,
            bestEpochMs = best,
            minSeparationDeg = bestSep,
            moonSemiDeg = semi,
            disappearsEpochMs = disappears,
            reappearsEpochMs = reappears,
            moonAltitudeDeg = moonAlt,
            sunAltitudeDeg = sunAlt,
            grazing = abs(bestSep - semi) <
                MOON_UNCERTAINTY_DEG + event.target.positionUncertaintyDeg,
        )
    }

    /** Geocentric angle from the Moon's centre to the target, degrees. Infinite if unavailable. */
    private fun separationDeg(ms: Long, target: Target): Double {
        val t = target.positionAt(ms) ?: return Double.POSITIVE_INFINITY
        val m = Ephemeris.moonEquatorial(ms)
        return Ephemeris.angularSeparationDeg(
            m.rightAscensionDeg, m.declinationDeg, t.rightAscensionDeg, t.declinationDeg,
        )
    }

    private fun topocentricSeparationDeg(
        ms: Long,
        target: Target,
        latDeg: Double,
        lonDeg: Double,
    ): Double {
        val raw = target.positionAt(ms) ?: return Double.POSITIVE_INFINITY
        val t = Ephemeris.topocentric(raw, latDeg, lonDeg, ms)
        val m = Ephemeris.topocentric(Ephemeris.moonEquatorial(ms), latDeg, lonDeg, ms)
        return Ephemeris.angularSeparationDeg(
            m.rightAscensionDeg, m.declinationDeg, t.rightAscensionDeg, t.declinationDeg,
        )
    }

    /** The Moon's apparent radius at a given distance, degrees. */
    private fun semiDiameterDeg(distanceKm: Double): Double =
        if (distanceKm <= MOON_RADIUS_KM) 90.0 else asin(MOON_RADIUS_KM / distanceKm) / DEG

    /**
     * Every local minimum of [f], bracketed on BOTH sides.
     *
     * ⚠️ A sample lower than the one before it is not a minimum — the function may still be
     * falling. Requiring the next sample to be higher too is what makes each answer a real turning
     * point, and it is why the ends of the window are never reported: neither has a neighbour on
     * one side, so neither can be bracketed.
     */
    private fun minima(fromMs: Long, toMs: Long, f: (Long) -> Double): List<Long> {
        val out = ArrayList<Long>()
        var prev = f(fromMs)
        var here = f(fromMs + SCAN_STEP_MS)
        var t = fromMs + SCAN_STEP_MS
        while (t + SCAN_STEP_MS <= toMs) {
            val next = f(t + SCAN_STEP_MS)
            if (here.isFinite() && here <= prev && here <= next) {
                out += refine(t - SCAN_STEP_MS, t + SCAN_STEP_MS, f)
            }
            prev = here
            here = next
            t += SCAN_STEP_MS
        }
        return out
    }

    /** Golden-section search for the minimum of a smooth unimodal [f] on a bracket. */
    private fun refine(lowMs: Long, highMs: Long, f: (Long) -> Double): Long {
        var a = lowMs
        var b = highMs
        val phi = 0.6180339887498949
        var c = b - ((b - a) * phi).toLong()
        var d = a + ((b - a) * phi).toLong()
        var fc = f(c)
        var fd = f(d)
        while (b - a > REFINE_TOLERANCE_MS) {
            if (fc < fd) {
                b = d; d = c; fd = fc
                c = b - ((b - a) * phi).toLong()
                fc = f(c)
            } else {
                a = c; c = d; fc = fd
                d = a + ((b - a) * phi).toLong()
                fd = f(d)
            }
        }
        return (a + b) / 2
    }

    /** Bisect for the instant where [f] crosses zero between an outside point and the inside. */
    private fun bisect(outsideMs: Long, insideMs: Long, f: (Long) -> Double): Long {
        var out = outsideMs
        var inside = insideMs
        repeat(60) {
            if (abs(out - inside) <= 1000L) return@repeat
            val mid = out + (inside - out) / 2
            if (f(mid) > 0) out = mid else inside = mid
        }
        return out + (inside - out) / 2
    }

    /** "The Moon covers Aldebaran", and so on. */
    fun describe(event: Event): String {
        val what = when (event.target.kind) {
            Kind.STAR -> "the star ${event.target.name}"
            Kind.PLANET -> event.target.name
        }
        return "The Moon passes in front of $what"
    }

    /**
     * What to do about it, in the terms somebody standing outside would use.
     *
     * ⚠️ Every branch that cannot be stood behind says so. A refusal is a result.
     */
    fun advice(event: Event, local: Local): String = when {
        !local.minSeparationDeg.isFinite() ->
            "Nothing can be worked out for this place."
        !local.occulted && !local.grazing -> {
            val miss = ((local.minSeparationDeg - local.moonSemiDeg) * 60).toInt()
            "Misses from here — the Moon's edge passes about $miss arcminutes away."
        }
        local.grazing ->
            "A graze from here, and that is genuinely undecided: at the limit the answer depends " +
                "on lunar mountains this cannot see. Worth watching for exactly that reason."
        !local.visible ->
            "It happens below your horizon — the Moon has not risen, or has already set."
        local.sunAltitudeDeg > -6.0 && event.target.kind == Kind.STAR ->
            "Occulted from here, but in daylight, so the star will not be visible."
        local.sunAltitudeDeg > 0.0 ->
            "Occulted from here in broad daylight. A telescope might catch it; the eye will not."
        event.target.kind == Kind.PLANET ->
            "Occulted from here. The disc takes a minute or two to slide behind the edge, so the " +
                "times are for the planet's centre."
        event.moonIlluminatedFraction > 0.9 ->
            "Occulted from here, but the Moon is nearly full and will be drowning the star in glare."
        else ->
            "Occulted from here. Watch the Moon's edge — the star vanishes in an instant, with no " +
                "atmosphere to fade it."
    }
}
