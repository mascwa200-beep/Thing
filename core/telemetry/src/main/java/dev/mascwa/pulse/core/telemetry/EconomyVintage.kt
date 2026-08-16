package dev.mascwa.pulse.core.telemetry

/**
 * How old an economic figure is, said out loud.
 *
 * The World Bank publishes annually and revises late, so the newest number for a country is
 * routinely a year or two behind — an inflation figure read in 2026 may well be the 2024 one. A
 * percentage shown without its year reads as current, and for a statistic that changes every year
 * that is not a small imprecision: it is the difference between "prices rose 3%" and "prices rose 3%
 * two years ago, and nobody here knows what they did since."
 *
 * Pure and deterministic — the clock is passed in — so CI can hold it.
 */
object EconomyVintage {

    /** How much weight a figure of this age can carry. */
    enum class Vintage {
        /** Published for the year we are in, or the one just gone. */
        CURRENT,

        /** A year behind — normal for annual statistics, worth stating but not worth flagging. */
        RECENT,

        /** Two years behind. The world has had time to move. */
        DATED,

        /** Three years or more. Historical context, not a current reading. */
        OLD,
    }

    /**
     * Months of slack before an annual figure counts as merely RECENT rather than CURRENT.
     *
     * Fifteen rather than twelve because annual data for a year does not appear on the first of
     * January: the 2025 figure lands somewhere in 2026, and calling it stale the moment the calendar
     * turns would flag every series in the app for months on end, which trains the reader to ignore
     * the flag.
     */
    const val CURRENT_MONTHS = 15
    const val RECENT_MONTHS = 27
    const val DATED_MONTHS = 39

    /**
     * Age in whole months, measured from the END of the data year.
     *
     * The end, not the start, because an annual figure describes the whole year: treating the 2024
     * number as though it were stamped 1 January 2024 would age it by twelve months it has not
     * lived. Never negative — a figure published for the current year is not "minus six months old".
     */
    fun ageMonths(dataYear: Int, nowMs: Long): Int {
        val nowYear = yearOf(nowMs)
        val nowMonth = monthOf(nowMs)
        // Months from the end of dataYear (i.e. its December) to the current month.
        val months = (nowYear - dataYear) * 12 + (nowMonth - 12)
        return if (months < 0) 0 else months
    }

    /** Which band [dataYear] falls in as of [nowMs]. */
    fun band(dataYear: Int, nowMs: Long): Vintage = when (ageMonths(dataYear, nowMs)) {
        in 0 until CURRENT_MONTHS -> Vintage.CURRENT
        in CURRENT_MONTHS until RECENT_MONTHS -> Vintage.RECENT
        in RECENT_MONTHS until DATED_MONTHS -> Vintage.DATED
        else -> Vintage.OLD
    }

    /**
     * The line the card puts under the number.
     *
     * A fresh figure gets its year and nothing more — "2025 data" — because an age on a current
     * number is noise. Anything older says how far back it is in the unit a reader actually feels:
     * months up to a couple of years, then years.
     */
    fun describe(dataYear: Int, nowMs: Long): String {
        val months = ageMonths(dataYear, nowMs)
        if (months < CURRENT_MONTHS) return "$dataYear data"
        if (months < RECENT_MONTHS) return "$dataYear · $months months old"
        // Rounded, not truncated. Forty-four months is three years and eight, and calling that
        // "3 years old" understates it by most of a year — in the wrong direction, since the whole
        // point of the line is to stop a figure looking fresher than it is.
        val years = (months + 6) / 12
        return "$dataYear · $years years old"
    }

    /**
     * A plain-English caution, or null when none is warranted.
     *
     * Null for CURRENT and RECENT on purpose. A warning attached to every figure is a warning
     * attached to none, and an annual statistic being a year behind is how annual statistics work
     * rather than something to apologise for.
     */
    fun caution(dataYear: Int, nowMs: Long): String? = when (band(dataYear, nowMs)) {
        Vintage.CURRENT, Vintage.RECENT -> null
        Vintage.DATED ->
            "This is the most recent figure published, but it describes $dataYear — a lot can have " +
                "changed since."
        Vintage.OLD ->
            "The latest available figure is from $dataYear, so read it as history rather than as a " +
                "current reading. Either the country reports this one late, or it has stopped " +
                "reporting it."
    }

    // ---- calendar ----------------------------------------------------------------------------

    /**
     * Year and month from an epoch millisecond, in UTC, by proleptic Gregorian arithmetic.
     *
     * Hand-rolled rather than `java.util.Calendar` so this stays a pure Kotlin core with no platform
     * types, matching the rest of `core:telemetry`. UTC rather than the device zone because the
     * comparison is against a calendar YEAR — a figure does not become a month older because the
     * reader is in Auckland.
     */
    internal fun yearOf(ms: Long): Int = civilFromDays(Math.floorDiv(ms, 86_400_000L)).first

    internal fun monthOf(ms: Long): Int = civilFromDays(Math.floorDiv(ms, 86_400_000L)).second

    /**
     * Days-since-epoch to (year, month), Howard Hinnant's civil-from-days.
     *
     * Chosen because it is exact for all inputs including pre-epoch dates and leap years, and short
     * enough to verify by eye — the alternative was a leap-year loop, which is the kind of thing
     * that works until it silently does not.
     */
    private fun civilFromDays(days: Long): Pair<Int, Int> {
        val z = days + 719_468L
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097L                                   // [0, 146096]
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365  // [0, 399]
        val y = yoe + era * 400L
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)              // [0, 365]
        val mp = (5 * doy + 2) / 153                                   // [0, 11]
        val m = if (mp < 10) mp + 3 else mp - 9                        // [1, 12]
        return Pair((if (m <= 2) y + 1 else y).toInt(), m.toInt())
    }
}
