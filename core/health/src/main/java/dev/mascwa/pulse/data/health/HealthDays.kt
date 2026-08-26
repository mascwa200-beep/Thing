package dev.mascwa.pulse.data.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * What a day is, for the whole HEALTH tab. One definition.
 *
 * ⚠️ **A local day is not 86,400,000 milliseconds.** It is 23 hours the morning the clocks go forward
 * and 25 the morning they go back, and every rule in this feature that asks "is this the same day?",
 * "what is the day after this one?" or "how many days ago was that?" gets a different answer from a
 * calendar than from arithmetic. Measured against real zone data for Europe/London 2026:
 *
 *  - 29 March is 23 hours long, so `dayStart + 24h` reaches 01:00 on the 30th. A same-day window built
 *    that way covers an hour of the *next* day — and [BodyStore.record] uses its window to DELETE, so
 *    correcting one morning's weigh-in would take the next morning's with it.
 *  - 25 October is 25 hours long, so the same window stops at 23:00 and the last hour of the day falls
 *    outside its own day. A second weigh-in there is kept rather than replacing the first, which
 *    double-weights that day in the trend.
 *
 * The cores in `:core:telemetry` are deliberately clock-free and zone-free, so this decision belongs
 * on the Android side, and it belongs in exactly one place: three copies of it had already drifted into
 * this feature, one of them wrong. Anything here that needs a day boundary calls this.
 *
 * The zone is a parameter with the device's own as its default, so the rules can be tested against a
 * named zone rather than against wherever the test happens to run.
 */
object HealthDays {

    /** The start of today, where the reader actually is. */
    fun todayStart(zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The start of the day [epochMs] falls in. */
    fun startOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        dateOf(epochMs, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * The day [days] before or after the one starting at [dayStartMs].
     *
     * `plus(d, 1)` is where that day genuinely ends, which is the exclusive upper bound any "same day"
     * window wants — never `dayStartMs + 86_400_000`.
     */
    fun plus(dayStartMs: Long, days: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        dateOf(dayStartMs, zone).plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * How many calendar days back [epochMs] is from [nowMs] — 0 for today, 1 for yesterday.
     *
     * ⚠️ **Calendar days, not elapsed time**, and the difference is visible every single day rather
     * than twice a year. A reading taken at 20:00 and read at 09:00 the next morning is thirteen hours
     * old, which divided by a day is zero — so an elapsed-time rule calls yesterday evening "Today"
     * right through until eight in the evening. Never negative: a clock that has moved backwards, or a
     * reading stamped a moment into the future, is today rather than tomorrow.
     */
    fun daysAgo(epochMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): Int =
        ChronoUnit.DAYS.between(dateOf(epochMs, zone), dateOf(nowMs, zone))
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    /**
     * The [days] day-starts ending at [lastDayStartMs], oldest first.
     *
     * This is what a windowed chart must iterate. Building the same list by adding a fixed day to a
     * start point puts every slot after a transition an hour away from the real day it is meant to
     * name, and a lookup keyed on it then misses — measured on real zone data, four of a seven-day
     * window's bars vanish for a week after either transition, each reading as a day nobody logged.
     */
    fun grid(lastDayStartMs: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val n = days.coerceAtLeast(1)
        val last = dateOf(lastDayStartMs, zone)
        return (n - 1 downTo 0).map { back ->
            last.minusDays(back.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }

    /**
     * Which day of the week an instant falls on, **Monday = 0**.
     *
     * ⚠️ The convention is not arbitrary and not free to change: it is what the week's heavy-day
     * toggles are indexed by and what `WeeklyPlan.Day.index` means, and the labels beside them read
     * MON…SUN. `java.time` numbers Monday as 1, so the subtraction is the whole of the conversion —
     * and doing it here rather than at a call site is the point, since a second copy that forgot it
     * would move somebody's calories onto the wrong day and look perfectly plausible.
     */
    fun weekdayIndex(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        dateOf(epochMs, zone).dayOfWeek.value - 1

    private fun dateOf(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
