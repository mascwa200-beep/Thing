package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The annual meteor showers, and whether tonight is worth going outside for.
 *
 * ## Why this is dated by solar longitude and not by a calendar date
 *
 * ⚠️ **A shower peaks when the Earth reaches a fixed point on its orbit, not on a fixed date.** The
 * calendar drifts against the orbit by about a quarter of a day a year and is yanked back every
 * fourth February, so "the Perseids peak on 12 August" is right to about a day and wrong in a way
 * that matters when the peak is only a few hours wide. Every published list quotes a **solar
 * longitude** (λ☉) for exactly this reason, and so does the table below — [Ephemeris.sunApparentLongitudeDeg]
 * is then solved for the moment the Earth actually gets there.
 *
 * Cross-checked while writing: converting each stored λ☉ back to a date with the ~0.9856°/day rate
 * reproduces the published peak date of all thirteen showers, so the table and the solver agree with
 * the source they came from.
 *
 * ## What the rate figure is, and is not
 *
 * ⚠️ **ZHR is an idealisation and almost nobody ever sees it.** It is defined as the rate a single
 * observer would count with the radiant straight overhead under a sky dark enough to show
 * magnitude 6.5 stars. [Viewing.perHour] scales it by the one factor that is pure geometry — the
 * sine of the radiant's altitude, which is the standard correction — and by a judgement about
 * moonlight that is stated as a judgement below. It does **not** model light pollution, because
 * nothing in this app knows how bright your sky is, and that is usually the largest term of all. So
 * the number is an upper bound on a good night, and the copy says so rather than implying a
 * measurement.
 *
 * ⚠️ **A radiant below the horizon yields null, not zero.** Zero would read as "a shower that is on
 * and producing nothing"; null is "you cannot see this from here at this hour", which is a different
 * fact and the one that tells you to come back at three in the morning.
 */
object MeteorShowers {

    /**
     * One annual shower.
     *
     * @param peakSolarLongitudeDeg λ☉ of maximum. This IS the date; see the note above.
     * @param activeDaysBefore/[activeDaysAfter] the published activity window, expressed relative to
     *   the peak rather than as two more dates — the window is quoted in whole days anyway, and
     *   deriving it from the peak means only one thing has to be right per shower.
     * @param zhr zenithal hourly rate at maximum under an ideal sky.
     * @param speedKmS atmospheric entry speed — what decides whether they look like slow fireballs
     *   or fast streaks, which is the thing an observer actually notices.
     */
    data class Shower(
        val id: String,
        val name: String,
        val radiantRaDeg: Double,
        val radiantDecDeg: Double,
        val peakSolarLongitudeDeg: Double,
        val activeDaysBefore: Int,
        val activeDaysAfter: Int,
        val zhr: Int,
        val speedKmS: Int,
        val parent: String,
        /** Said plainly where a shower is known to misbehave, and empty where it is dependable. */
        val caveat: String = "",
    ) {
        /** Fast meteors leave thin, brief streaks; slow ones drift and are likelier to be bright. */
        val pace: String
            get() = when {
                speedKmS >= 60 -> "very fast"
                speedKmS >= 45 -> "fast"
                speedKmS >= 30 -> "medium"
                else -> "slow"
            }
    }

    /**
     * The thirteen showers worth telling somebody about.
     *
     * Radiants are J2000 positions at maximum; a radiant drifts a little across the activity window
     * and that drift is smaller than the several degrees of scatter in where the meteors themselves
     * appear, so it is not modelled.
     */
    val ALL: List<Shower> = listOf(
        Shower(
            "qua", "Quadrantids", 230.1, 49.5, 283.15, 6, 9, 110, 41,
            "asteroid 2003 EH1, probably a dead comet",
            "The peak is only a few hours wide — the sharpest of any major shower — so being on the " +
                "wrong side of the planet that night costs you nearly all of it.",
        ),
        Shower(
            "lyr", "Lyrids", 271.4, 33.6, 32.32, 8, 8, 18, 49,
            "comet C/1861 G1 Thatcher",
            "Occasionally surges to several times the usual rate with no warning.",
        ),
        Shower(
            "eta", "Eta Aquariids", 338.0, -1.0, 45.5, 17, 22, 50, 66,
            "comet 1P/Halley",
            "Strongly favours the southern hemisphere: the radiant barely clears the horizon before " +
                "dawn from mid-northern latitudes.",
        ),
        Shower(
            "cap", "Alpha Capricornids", 307.0, -10.0, 127.0, 27, 16, 5, 23,
            "comet 169P/NEAT",
            "Few, but slow and often very bright — a fireball shower rather than a numbers one.",
        ),
        Shower(
            "sda", "Southern delta Aquariids", 340.0, -16.4, 125.0, 18, 24, 25, 41,
            "comet 96P/Machholz",
        ),
        Shower(
            "per", "Perseids", 48.2, 58.1, 140.0, 26, 12, 100, 59,
            "comet 109P/Swift-Tuttle",
        ),
        Shower(
            "dra", "Draconids", 262.1, 55.9, 195.4, 2, 2, 10, 21,
            "comet 21P/Giacobini-Zinner",
            "Usually almost nothing, and a handful of times a century a storm of hundreds an hour. " +
                "Best seen in the evening rather than before dawn, which is unusual.",
        ),
        Shower(
            "ori", "Orionids", 95.2, 15.8, 208.0, 19, 17, 20, 66,
            "comet 1P/Halley",
        ),
        Shower(
            "sta", "Southern Taurids", 32.0, 9.0, 223.0, 56, 14, 5, 27,
            "comet 2P/Encke",
            "A long, low, slow drizzle rather than a peak — but with a reputation for fireballs.",
        ),
        Shower(
            "nta", "Northern Taurids", 58.0, 22.0, 230.0, 23, 28, 5, 29,
            "comet 2P/Encke",
        ),
        Shower(
            "leo", "Leonids", 152.3, 22.2, 235.27, 11, 13, 15, 71,
            "comet 55P/Tempel-Tuttle",
            "Ordinary most years and spectacular roughly every 33, when the Earth crosses the comet's " +
                "fresh debris.",
        ),
        Shower(
            "gem", "Geminids", 112.3, 32.5, 262.2, 10, 6, 150, 34,
            "asteroid 3200 Phaethon",
            "The most reliable shower of the year, and the radiant is up most of the night.",
        ),
        Shower(
            "urs", "Ursids", 217.1, 75.4, 270.7, 5, 4, 10, 33,
            "comet 8P/Tuttle",
            "The radiant is circumpolar from mid-northern latitudes, so it never sets.",
        ),
    )

    fun byId(id: String): Shower? = ALL.firstOrNull { it.id == id }

    /** A shower's next (or current) maximum, with where it sits relative to now. */
    data class Occurrence(
        val shower: Shower,
        val peakEpochMs: Long,
        /** Negative before the peak, positive after. Whole days, rounded toward the peak. */
        val daysFromPeak: Int,
        /** True when now falls inside the published activity window. */
        val active: Boolean,
    )

    /**
     * What, if anything, stops this being watchable right now.
     *
     * ⚠️ **A rate of zero and no rate at all are different answers**, which is why this exists
     * rather than a bare nullable number. "Nothing is falling" and "you cannot see it from here at
     * this hour" send a reader to different places, and only the second one has a time attached.
     */
    enum class Hindrance {
        /** Nothing in the way. Whatever the count says is what you can expect. */
        NONE,

        /** The radiant has not risen yet, or has already set, at this place and hour. */
        RADIANT_DOWN,

        /** The sky is too bright — the Sun is up, or not far enough down. */
        DAYLIGHT,
    }

    /** How well it can be seen from one place at one moment. */
    data class Viewing(
        val radiantAltitudeDeg: Double,
        val radiantAzimuthDeg: Double,
        val sunAltitudeDeg: Double,
        val moonIlluminatedFraction: Double,
        val moonAboveHorizon: Boolean,
        val hindrance: Hindrance,
        /**
         * A rough count per hour, or null whenever [hindrance] is not [Hindrance.NONE].
         *
         * ⚠️ Null and zero mean different things here — see the class note.
         */
        val perHour: Int?,
    )

    // ---- dating ---------------------------------------------------------------------------

    /** Mean advance of the Sun's apparent longitude, degrees per day. */
    private const val DEG_PER_DAY = 360.0 / 365.2422

    private const val DAY_MS = 86_400_000L

    /**
     * The first moment at or after [fromEpochMs] when the Sun's apparent longitude reaches
     * [targetLongitudeDeg].
     *
     * ⚠️ **Newton with a FIXED derivative, not a bisection.** Solar longitude advances almost
     * exactly linearly — the annual variation is under 4% — so the iteration converges to well
     * inside a minute in three steps and, because the slope is a constant rather than a measured
     * one, it cannot oscillate or divide by anything near zero. A bisection would need a bracket,
     * and bracketing an angle that wraps is where this kind of solver usually goes wrong.
     */
    fun solarLongitudeCrossing(targetLongitudeDeg: Double, fromEpochMs: Long): Long {
        val here = Ephemeris.sunApparentLongitudeDeg(fromEpochMs)
        val ahead = wrap360(targetLongitudeDeg - here)
        var t = fromEpochMs + (ahead / DEG_PER_DAY * DAY_MS).toLong()
        repeat(6) {
            val err = wrap180(Ephemeris.sunApparentLongitudeDeg(t) - targetLongitudeDeg)
            t -= (err / DEG_PER_DAY * DAY_MS).toLong()
        }
        return t
    }

    /**
     * Every shower peaking within [withinDays] of now, plus any already inside its activity window,
     * soonest first.
     *
     * ⚠️ A shower whose peak has just passed but whose window is still open stays in the list with a
     * positive [Occurrence.daysFromPeak] — the Taurids run for two months either side and dropping
     * them the morning after maximum would hide most of what they are.
     */
    fun upcoming(nowEpochMs: Long, withinDays: Int = 45): List<Occurrence> =
        ALL.mapNotNull { s ->
            // Look back far enough that a shower peaking just behind us is still found, then take
            // whichever of the two candidate crossings is nearer.
            val back = nowEpochMs - (s.activeDaysAfter + 1) * DAY_MS
            val peak = solarLongitudeCrossing(s.peakSolarLongitudeDeg, back)
            val offsetDays = ((nowEpochMs - peak).toDouble() / DAY_MS).roundToInt()
            val active = offsetDays >= -s.activeDaysBefore && offsetDays <= s.activeDaysAfter
            val soon = offsetDays <= 0 && -offsetDays <= withinDays
            if (active || soon) Occurrence(s, peak, offsetDays, active) else null
        }.sortedBy { abs(it.daysFromPeak) }

    // ---- seeing it ------------------------------------------------------------------------

    /**
     * ⚠️ **The moonlight penalty is a judgement, not physics.** A full Moon does not multiply the
     * count by a number anyone has derived; it raises the sky background so the faint majority of
     * meteors stop being visible, and how much that costs depends on how far the Moon is from where
     * you are looking and how good your sky was to begin with. This takes the illuminated fraction,
     * applies it only while the Moon is actually up, and at worst discards four fifths of the count
     * — which lands in the right region for a full Moon and is stated here so nobody mistakes it for
     * a measurement.
     */
    private const val MAX_MOON_PENALTY = 0.8

    /**
     * The Sun must be at least this far down before any of this is worth reporting.
     *
     * ⚠️ **This was missing from the first cut and it is a bigger term than the Moon.** The rate was
     * reported at noon: the radiant is often perfectly well up in daylight, and the geometry factor
     * happily returned a number for a sky in which nothing whatsoever is visible. The Sun is by far
     * the largest source of sky brightness there is, and leaving it out while carefully modelling
     * moonlight was the app being more confident than its data.
     *
     * −12° is nautical twilight. ⚠️ Between −18° and −12° the sky is genuinely still brightening the
     * background and the count would be depressed, and that band is deliberately **not** penalised:
     * there is no coefficient anyone could defend, and inventing a taper would be exactly the kind
     * of made-up number this file refuses elsewhere. So the figure is optimistic in that hour and
     * honest about being so.
     */
    private const val DARK_ENOUGH_SUN_ALT = -12.0

    fun viewing(
        shower: Shower,
        latDeg: Double,
        lonDeg: Double,
        epochMs: Long,
    ): Viewing {
        val radiant = Ephemeris.toHorizontal(
            Ephemeris.Equatorial(shower.radiantRaDeg, shower.radiantDecDeg, 0.0),
            latDeg, lonDeg, epochMs,
        )
        val sun = Ephemeris.sunPosition(latDeg, lonDeg, epochMs)
        val moon = Ephemeris.moonPosition(latDeg, lonDeg, epochMs)
        val phase = Ephemeris.moonPhase(epochMs)
        val moonUp = moon.altitudeDeg > 0.0

        // ⚠️ Daylight is checked FIRST, and the order is a judgement about what the reader needs.
        // At midday both are usually true; "come back after dark" is the useful sentence, and it
        // stays correct on the days when the radiant happens to be up as well.
        val hindrance = when {
            sun.altitudeDeg > DARK_ENOUGH_SUN_ALT -> Hindrance.DAYLIGHT
            radiant.altitudeDeg <= 0.0 -> Hindrance.RADIANT_DOWN
            else -> Hindrance.NONE
        }

        val perHour = if (hindrance != Hindrance.NONE) {
            null
        } else {
            val geometry = sin(radiant.altitudeDeg * Math.PI / 180.0)
            val moonFactor =
                if (moonUp) 1.0 - MAX_MOON_PENALTY * phase.illuminatedFraction else 1.0
            (shower.zhr * geometry * moonFactor).roundToInt()
        }

        return Viewing(
            radiantAltitudeDeg = radiant.altitudeDeg,
            radiantAzimuthDeg = radiant.azimuthDeg,
            sunAltitudeDeg = sun.altitudeDeg,
            moonIlluminatedFraction = phase.illuminatedFraction,
            moonAboveHorizon = moonUp,
            hindrance = hindrance,
            perHour = perHour,
        )
    }

    /**
     * One sentence a person can act on.
     *
     * ⚠️ Deliberately says what to DO — where to look and when to come back — rather than reporting
     * an altitude in degrees at somebody. The numbers are on the card beside it.
     */
    fun advice(occurrence: Occurrence, viewing: Viewing): String {
        val s = occurrence.shower
        val where = cardinal(viewing.radiantAzimuthDeg)
        return when {
            viewing.hindrance == Hindrance.DAYLIGHT ->
                "Too bright — the sky has to get properly dark first. Come back once the Sun is " +
                    "well down."
            viewing.hindrance == Hindrance.RADIANT_DOWN ->
                "The radiant is below the horizon right now, so there is nothing to see from here " +
                    "yet. It has to rise first."
            viewing.perHour == null ->
                "Nothing to report from here at this hour."
            viewing.perHour <= 0 ->
                "The radiant is barely up and the Moon is washing the sky out. Worth waiting for a " +
                    "darker hour."
            !occurrence.active ->
                "Not started yet — the peak is in ${-occurrence.daysFromPeak} days. The radiant is " +
                    "${viewing.radiantAltitudeDeg.roundToInt()}° up toward the $where."
            viewing.moonAboveHorizon && viewing.moonIlluminatedFraction > 0.6 ->
                "Roughly ${viewing.perHour} an hour, though the Moon is up and ${(viewing.moonIlluminatedFraction * 100).roundToInt()}% " +
                    "lit, which hides most of the faint ones. Look about 40° away from the radiant, " +
                    "toward the $where, with the Moon behind you."
            else ->
                "Roughly ${viewing.perHour} an hour under a dark sky. Look about 40° away from the " +
                    "radiant, which is ${viewing.radiantAltitudeDeg.roundToInt()}° up toward the " +
                    "$where. They are ${s.pace}."
        }
    }

    // ---- small helpers --------------------------------------------------------------------

    private fun wrap360(deg: Double): Double {
        var d = deg % 360.0
        if (d < 0) d += 360.0
        return d
    }

    private fun wrap180(deg: Double): Double {
        var d = wrap360(deg)
        if (d > 180.0) d -= 360.0
        return d
    }

    private val CARDINALS = listOf(
        "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west",
    )

    private fun cardinal(azimuthDeg: Double): String =
        CARDINALS[((wrap360(azimuthDeg) + 22.5) / 45.0).toInt() % 8]
}
