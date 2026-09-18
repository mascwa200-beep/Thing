package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.cos

/**
 * Carrying a catalogued star to where it actually is now.
 *
 * Precession turns the whole sky as one rigid body and lives in `SkyFrame`; this is the other thing
 * that moves a star, and it is the only one that moves each star differently. Nothing else does —
 * which is why a star map can hold equatorial positions for a whole session and rebuild two vectors
 * a frame.
 *
 * ## ⚠️ One definition, because there are two catalogues
 *
 * The map draws a deep Gaia set at epoch **J2016.0** and a bright Bright-Star-Catalogue set at
 * **J2000.0**, both whole, with their overlap simply allowed — `StarLayer` explains why a magnitude
 * seam between them cannot work. So the same arithmetic runs over two catalogues from two epochs,
 * and a second copy of it would be the duplicated-definition mistake this project has corrected
 * repeatedly.
 *
 * ⚠️ **Applying this to ONE of the two would be worse than applying it to neither, and that is
 * measured rather than reasoned.** Walking every record of the real 3,087,821-star bundle: 12,602
 * of them are magnitude 6.5 or brighter, which is the bright catalogue's own limit, so they are
 * drawn twice. Carry the deep copy forward and leave the bright one at J2000 and **1,339 of those
 * pairs end up more than four pixels apart** at the quarter-degree field — the worst at 7,050
 * mas/yr, which is 188 arcseconds, or 226 pixels. Two dots where there is one star. So both
 * catalogues carry proper motion or neither does.
 *
 * ## The convention, taken from both sources rather than assumed
 *
 * Both catalogues state the right-ascension component as the **projected** motion — Gaia calls it
 * `pmra` and documents it as mu-alpha-star; the Bright Star Catalogue's own ReadMe says outright
 * *"the proper motion in RA is the projected motion (cos(DE).d(RA)/dt)"*. That agreement is what
 * lets one function serve both, and getting it wrong is invisible at the equator and grows without
 * limit toward the poles, which is exactly where it would be hardest to notice.
 */
object ProperMotion {

    /** Milliarcseconds in a degree, which is the unit both catalogues are stored in. */
    const val MAS_PER_DEGREE = 3_600_000.0

    /**
     * ⚠️ A floor on `cos(dec)` so a star at the pole cannot divide by zero.
     *
     * At a declination this close to 90 the right ascension is nearly meaningless anyway — a
     * millionth of a degree of cosine is a star four thousandths of an arcsecond from the pole —
     * so the clamp bounds the arithmetic without moving anything a chart could draw.
     */
    private const val MIN_COS = 1e-6

    /**
     * Move `[raDeg], [decDeg]` by [years] of its own motion, writing `[ra, dec]` into [out].
     *
     * ⚠️ In-place safe and allocation-free, because the deep path runs this over tens of thousands
     * of stars on every reload.
     *
     * @param pmRaMasPerYear the PROJECTED right-ascension motion, `cos(dec) * d(ra)/dt`.
     * @param years how long to carry it, from the catalogue's own epoch to the date being drawn.
     *   Negative runs it backwards, which is what a cross-match against an older catalogue wants.
     */
    fun carry(
        raDeg: Double,
        decDeg: Double,
        pmRaMasPerYear: Double,
        pmDecMasPerYear: Double,
        years: Double,
        out: DoubleArray,
    ) {
        if (years == 0.0 || (pmRaMasPerYear == 0.0 && pmDecMasPerYear == 0.0)) {
            out[0] = raDeg
            out[1] = decDeg
            return
        }
        val shrink = cos(Math.toRadians(decDeg)).let { if (abs(it) < MIN_COS) MIN_COS else it }
        val ra = raDeg + pmRaMasPerYear * years / (MAS_PER_DEGREE * shrink)
        val dec = decDeg + pmDecMasPerYear * years / MAS_PER_DEGREE
        out[0] = ((ra % 360.0) + 360.0) % 360.0
        out[1] = dec.coerceIn(-90.0, 90.0)
    }

    /**
     * How far to carry a catalogue whose positions are referred to [catalogueEpochYear].
     *
     * ⚠️ **Julian years, which is what a catalogue epoch means.** J2016.0 and J2000.0 are defined on
     * the 365.25-day Julian year, not on a calendar year, so counting calendar years would drift by
     * a day every four. [Ephemeris.julianYear] is the one place that conversion lives.
     *
     * ⚠️ UT rather than Terrestrial Time, deliberately: this is a DURATION in years, and the 69
     * seconds between the two scales is two millionths of a year — on the fastest star in the sky
     * that is two hundred-thousandths of an arcsecond.
     */
    fun yearsSince(catalogueEpochYear: Double, epochMs: Long): Double =
        Ephemeris.julianYear(epochMs) - catalogueEpochYear
}
