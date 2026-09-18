package dev.mascwa.pulse.core.telemetry

/**
 * When your mobile data allowance last reset.
 *
 * A data cap is measured over a billing cycle, not over a rolling month, so "4.2 GB used" means
 * nothing without knowing when the counting started. The cycle begins on the same day each month —
 * whichever day the account was opened — and that day is a setting, because nothing on the phone
 * knows it.
 *
 * ## ⚠️ A day-of-month is not a day that every month has
 *
 * A cycle starting on the 31st has to start SOMEWHERE in April, and on the 30th is the only
 * defensible answer: the alternative is a month with no cycle at all, or one that silently rolls
 * into May and reports six weeks of usage as a month's. The same applies to the 29th, 30th and 31st
 * in February, and to the 29th in three years out of four. [effectiveDay] is where that is decided,
 * once, so no caller has to.
 *
 * ## Why the clock is not in here
 *
 * ⚠️ Every date core in this module — [Stardate], [EconomyVintage] — takes its calendar components
 * as arguments rather than reading a clock, and this follows them for the same two reasons. A cycle
 * boundary is a LOCAL date: a phone in Auckland whose cycle starts on the 1st resets thirteen hours
 * before one in London, and a UTC calculation would report the wrong month for a third of the day.
 * Only the caller knows the zone. And a function with no clock in it is testable at every boundary
 * that matters rather than only on the day the test happens to run.
 *
 * `java.time` is deliberately absent, as it is from the whole module: these are a few lines of
 * arithmetic, and this module is depended on by an application whose floor is API 23.
 */
object BillingCycle {

    /** The day most accounts reset on, and the only sane default when nothing has been chosen. */
    const val DEFAULT_DAY = 1

    /** A local date, as this module speaks them: no zone, no clock, no instant. */
    data class Date(val year: Int, val month: Int, val day: Int) {
        /** `yyyy-MM-dd`, locale-free — the shape a caller hands to its own calendar. */
        override fun toString(): String =
            "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    /** Days in [month] of [year], Gregorian. */
    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeap(year)) 29 else 28
        else -> 0
    }

    /** The Gregorian leap rule, whole: every four years, except centuries, except every four hundred. */
    fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    /**
     * Which day of [month] a cycle nominally starting on [day] actually starts on.
     *
     * Clamped to the month's own length, so a 31st cycle starts on the 30th in April and on the
     * 28th in an ordinary February. [day] outside 1..31 is coerced first, because a stored setting
     * is only as good as whatever last wrote it.
     */
    fun effectiveDay(year: Int, month: Int, day: Int): Int =
        day.coerceIn(1, 31).coerceAtMost(daysInMonth(year, month))

    /**
     * The start of the cycle that [year]/[month]/[dayOfMonth] falls inside.
     *
     * ⚠️ Compared against THIS month's effective day rather than the raw setting. On 29 April with a
     * cycle day of 31, the raw comparison (29 >= 31 is false) and the effective one (29 >= 30 is
     * false) agree; on 30 April they do not, and only the effective one gives the right answer —
     * that IS the day the cycle rolled over.
     */
    fun startOf(year: Int, month: Int, dayOfMonth: Int, cycleDay: Int): Date {
        val thisMonth = effectiveDay(year, month, cycleDay)
        if (dayOfMonth >= thisMonth) return Date(year, month, thisMonth)
        val py = if (month == 1) year - 1 else year
        val pm = if (month == 1) 12 else month - 1
        return Date(py, pm, effectiveDay(py, pm, cycleDay))
    }

    /**
     * How many days the current cycle has run, counting the day it started as day 1.
     *
     * "Day 1 of 30" on the morning it resets reads correctly; day 0 reads like a fault.
     */
    fun daysInto(year: Int, month: Int, dayOfMonth: Int, cycleDay: Int): Int {
        val start = startOf(year, month, dayOfMonth, cycleDay)
        return if (start.month == month && start.year == year) {
            dayOfMonth - start.day + 1
        } else {
            daysInMonth(start.year, start.month) - start.day + 1 + dayOfMonth
        }
    }

    /**
     * How long this cycle will run, in days.
     *
     * ⚠️ Not "the length of the month". A cycle starting on the 31st of March ends on the 30th of
     * April, which is 30 days; one starting on the 28th of February ends on the 27th of March,
     * which is 28. The length is the distance to the NEXT start, so it is derived from both.
     */
    fun lengthDays(year: Int, month: Int, dayOfMonth: Int, cycleDay: Int): Int {
        val start = startOf(year, month, dayOfMonth, cycleDay)
        val ny = if (start.month == 12) start.year + 1 else start.year
        val nm = if (start.month == 12) 1 else start.month + 1
        val next = effectiveDay(ny, nm, cycleDay)
        return daysInMonth(start.year, start.month) - start.day + next
    }
}
