package dev.mascwa.pulse.core.telemetry

/**
 * The date, said the way a ship's computer would say it.
 *
 * **This is an app convention, not canon, and the distinction is worth stating plainly.** On screen
 * the numbers run from 41000 (the first season of *The Next Generation*, 2364) to about 58000, at a
 * documented rate of a thousand units per year — but no canon mapping exists for the twenty-first
 * century, and the published formulas all put the present day at a large negative number. So this
 * anchors on the app's own era instead: the year contributes `(year - 2000) × 1000`, the day of the
 * year contributes its fraction of that thousand, and the hour contributes a tenth.
 *
 * That is the same scale the boot sequence has been showing since it shipped, promoted here so one
 * definition serves every surface. Two defects came with it and are fixed:
 *
 *  - it divided by a fixed 365, so every leap year drifted by roughly three units by December;
 *  - it formatted through the default locale, which is the trap this codebase keeps meeting — a
 *    stardate is a number a person reads back, and it has no business changing shape by region.
 *
 * Pure, calendar-free and deterministic: the caller decomposes the date, which keeps this testable
 * without a clock and matches how [TemporalReasoner] is built.
 */
object Stardate {

    /** A thousand units to the year — the one rate the show is actually consistent about. */
    const val UNITS_PER_YEAR = 1000.0

    /**
     * The year contributing zero.
     *
     * 2000 rather than the arithmetically identical `year % 100`, which is what shipped: the two
     * agree on every value this century and then diverge, because the modulo silently rolls back to
     * zero in 2100 and this does not. Nobody will be running this build then; a counter that counts
     * is still the better of two free choices.
     */
    const val EPOCH_YEAR = 2000

    /**
     * The stardate for a decomposed local date and time.
     *
     * [dayOfYear] is 1-based and [daysInYear] is 365 or 366, so the fraction stays true through a
     * leap year. [hourOfDay] contributes the tenth. Returns a Double so a caller can compare or sort
     * stardates; use [format] to render one.
     */
    fun of(year: Int, dayOfYear: Int, daysInYear: Int, hourOfDay: Int = 0): Double {
        val days = if (daysInYear > 0) daysInYear else 365
        val dayFraction = ((dayOfYear - 1).coerceAtLeast(0).toDouble() / days) * UNITS_PER_YEAR
        val whole = (year - EPOCH_YEAR) * UNITS_PER_YEAR + dayFraction
        val tenths = (hourOfDay.coerceIn(0, 23) * 10) / 24
        // Truncated, not rounded: a stardate ticks forward as the day advances and must never read
        // as a moment that has not happened yet.
        return kotlin.math.floor(whole) + tenths / 10.0
    }

    /**
     * The stardate for an instant, as read where the reader is standing.
     *
     * [of] takes a decomposed date on purpose, which keeps it clock-free and testable — but it left
     * the decomposition to whoever held the clock, and the app now shows a stardate on the boot
     * reveal, every screen header, the Home masthead, the one notification board, the Computer's
     * context and the desktop's rail. This does that arithmetic once, so each platform supplies only
     * the instant and its own offset.
     *
     * ⚠️ **[utcOffsetSeconds] is load-bearing, not a convenience.** The obvious implementation
     * floor-divides epoch milliseconds into days, which is UTC, and this codebase has already
     * shipped that same bug twice: the observatory computed "tonight's geometry" from UTC midnight,
     * so "today's sunset" was the wrong day for anyone far from Greenwich, and the day-ahead core
     * wrote UTC clock times into four separate lines of prose — an hour out in Berlin and eleven in
     * Auckland. A stardate is a date said aloud; one that rolls its tenth at UTC midnight is simply
     * wrong for most of the planet. The caller supplies its own platform's offset in one line and
     * the arithmetic stays here, shared and tested.
     *
     * ⚠️ **The civil conversion is deliberately hand-rolled, and deliberately a second copy.**
     * `java.time` would do it, but this core has no platform dependency and [EconomyVintage] states
     * the same reason for its own `civilFromDays`. That one yields year and month for a UTC instant;
     * this needs day-of-year, length-of-year and a local hour, so neither can be expressed in terms
     * of the other without changing a working, shipped core for no functional gain. The honest
     * safeguard against two implementations drifting is a test that compares them —
     * `StardateCivilDriftTest` sweeps both over thousands of days and fails the build if they ever
     * disagree. It is a separate file from `StardateTest` because that one is mirrored to the
     * desktop, which has no economy screen and so no [EconomyVintage] to compare against.
     */
    fun at(epochMs: Long, utcOffsetSeconds: Int): Double {
        val localMs = epochMs + utcOffsetSeconds * 1000L
        // floorDiv, not `/`: before 1970 and west of Greenwich the millisecond count goes negative,
        // and truncating division would round toward zero, i.e. into the following day.
        val days = Math.floorDiv(localMs, MS_PER_DAY)
        val msIntoDay = Math.floorMod(localMs, MS_PER_DAY)
        val (year, dayOfYear) = civilDayOfYear(days)
        return of(
            year = year,
            dayOfYear = dayOfYear,
            daysInYear = if (isLeap(year)) 366 else 365,
            hourOfDay = (msIntoDay / 3_600_000L).toInt(),
        )
    }

    /** Proleptic Gregorian, the rule the calendar actually uses rather than the every-fourth-year one. */
    private fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    /**
     * Days since 1970-01-01 to (year, 1-based day of year).
     *
     * Howard Hinnant's civil-from-days, shifted to an era beginning on 1 March so that the leap day
     * falls at the end of the era's year and needs no special case mid-calculation.
     */
    private fun civilDayOfYear(days: Long): Pair<Int, Int> {
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097                                   // 0..146096
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)             // 0..365, from 1 March
        val mp = (5 * doy + 2) / 153                                  // 0..11, March = 0
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9                       // 1..12
        val year = (if (m <= 2) y + 1 else y).toInt()
        // Day-of-year from the calendar month, which is what `of` expects.
        val leap = isLeap(year)
        val before = CUMULATIVE_DAYS[m.toInt() - 1] + if (leap && m > 2) 1 else 0
        return year to (before + d.toInt())
    }

    /** Days elapsed before the start of each month in a common year. */
    private val CUMULATIVE_DAYS = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

    private const val MS_PER_DAY = 86_400_000L

    /**
     * "26621.5" — one decimal, no grouping, no locale.
     *
     * Built by hand rather than through a formatter because every locale-sensitive `format` call in
     * this codebase has eventually turned out to be a bug, and a stardate is a bare number.
     */
    fun format(stardate: Double): String {
        if (!stardate.isFinite()) return "—"
        val scaled = kotlin.math.floor(kotlin.math.abs(stardate) * 10.0 + 0.5).toLong()
        val sign = if (stardate < 0) "-" else ""
        return "$sign${scaled / 10}.${scaled % 10}"
    }

    /** "STARDATE 26621.5" — the full stamp, for a header or a boot line. */
    fun stamp(stardate: Double): String = "STARDATE ${format(stardate)}"
}
