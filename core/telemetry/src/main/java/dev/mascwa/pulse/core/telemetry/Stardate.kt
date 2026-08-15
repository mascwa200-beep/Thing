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
