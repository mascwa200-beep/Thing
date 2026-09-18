package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What changed between two dates, and how the record reads a week or a month at a time.
 *
 * The body page records weigh-ins and tape measurements and offers no way to ask the one question
 * anybody actually has about them — *"what is different since the start of the year?"* — and the food
 * log reads day by day with no way to stand back from it. Both are the same shape of question asked
 * of two different series, so both are answered here.
 *
 * Pure, clock-free and zone-free. ⚠️ **The calendar is passed in**, as a day grid and as a function
 * from a day to the start of the bucket it belongs to — the same arrangement [Habits] uses for
 * `dayBefore` and for the same reason. A week is 7 × 24 h only until a clock change, and this repo
 * has already shipped that defect twice: a streak that split at every transition, and a chart that
 * lost four of seven bars for a week afterwards. Nothing here divides by a day.
 */
object PeriodCompare {

    /** One reading of one thing at one moment. */
    data class Point(val atMs: Long, val value: Double)

    /**
     * How far either end of a comparison may reach for a reading, in days.
     *
     * A date with no reading anywhere near it has nothing to compare, and saying so is better than
     * quoting whatever happens to be nearest — a "since January" comparison that silently used a
     * reading from March is a wrong answer wearing a right label.
     */
    const val REACH_DAYS: Double = 10.0

    private const val DAY_MS = 86_400_000.0

    /** What a series did between two instants, or why it cannot say. */
    data class Change(
        val label: String,
        val unit: String,
        val from: Double?,
        val fromAtMs: Long?,
        val to: Double?,
        val toAtMs: Long?,
        /** [to] − [from], so negative is down. Null unless both ends have a reading. */
        val delta: Double?,
        /**
         * The same as a fraction of [from], or null when a fraction would not mean anything.
         *
         * ⚠️ **Withheld for a base at or below zero, which is not fussiness — it was measured.** A
         * series that goes negative is a real one here (a daily energy balance is exactly that), and
         * a relative change against a negative base prints numbers nobody can read: −600 to −200 came
         * out as "Up 400 kcal (67%)" and −200 to 300 as "(250%)". The absolute change is right in
         * both; only the percentage is meaningless, so only the percentage goes. A quantity has to
         * have a meaningful zero and hold its sign before "a fifth bigger" says anything.
         */
        val fraction: Double?,
        /** Present exactly when [delta] is null, and says which end is missing. */
        val why: String?,
    ) {
        val known: Boolean get() = delta != null
    }

    /**
     * What [points] did between [fromMs] and [toMs].
     *
     * ⚠️ **What you feed this matters more than anything it does.** For a tape measurement the raw
     * readings are all there is. For WEIGHT they are the wrong input: a single weigh-in carries about
     * [BodyTrend.SCALE_NOISE_KG] of noise, so comparing two of them can report a gain in the middle
     * of a real loss — pass `BodyTrend.Trend.Estimated.points` mapped to their `trendKg` instead. The
     * decision belongs to the caller because only the caller knows which series it holds.
     */
    fun compare(
        label: String,
        unit: String,
        points: List<Point>,
        fromMs: Long,
        toMs: Long,
        reachDays: Double = REACH_DAYS,
    ): Change {
        val usable = points.filter { it.value.isFinite() }
        val a = nearest(usable, fromMs, reachDays)
        val b = nearest(usable, toMs, reachDays)
        val why = when {
            usable.isEmpty() -> "Nothing recorded for $label yet."
            a == null && b == null -> "No $label reading near either date."
            a == null -> "No $label reading near the earlier date."
            b == null -> "No $label reading near the later date."
            // ⚠️ One reading cannot be both ends. Without this the comparison quietly reports a
            // change of exactly zero, which reads as "you held steady" rather than "there is only
            // one reading here" — and those are very different things to tell somebody.
            a.atMs == b.atMs -> "Only one $label reading covers that stretch."
            else -> null
        }
        if (why != null) {
            return Change(label, unit, a?.value, a?.atMs, b?.value, b?.atMs, null, null, why)
        }
        a!!
        b!!
        val delta = b.value - a.value
        return Change(
            label = label,
            unit = unit,
            from = a.value,
            fromAtMs = a.atMs,
            to = b.value,
            toAtMs = b.atMs,
            delta = delta,
            fraction = if (a.value > FRACTION_FLOOR) delta / a.value else null,
            why = null,
        )
    }

    /** The reading closest to [atMs], or null when the nearest is further than [reachDays] away. */
    fun nearest(points: List<Point>, atMs: Long, reachDays: Double = REACH_DAYS): Point? {
        val limit = (reachDays * DAY_MS).toLong()
        return points
            .filter { abs(it.atMs - atMs) <= limit }
            .minByOrNull { abs(it.atMs - atMs) }
    }

    /**
     * A change in one sentence.
     *
     * ⚠️ States the two readings and their dates' distance apart rather than only the difference,
     * because "down 2.4 kg" over three weeks and over three months are different findings and the
     * bare number cannot tell them apart.
     */
    fun sentence(c: Change, decimals: Int = 1): String {
        val why = c.why
        if (why != null) return why
        val d = c.delta!!
        val days = ((c.toAtMs!! - c.fromAtMs!!) / DAY_MS).roundToInt().coerceAtLeast(1)
        val over = when {
            days == 1 -> "over a day"
            days < 14 -> "over $days days"
            days < 60 -> "over ${(days / 7.0).roundToInt()} weeks"
            else -> "over ${(days / 30.0).roundToInt()} months"
        }
        if (abs(d) < STEADY_EPSILON) {
            return "${c.label} is where it was, $over."
        }
        val way = if (d < 0) "Down" else "Up"
        val pct = c.fraction?.takeIf { abs(it) >= 0.005 }?.let { " (${(abs(it) * 100).roundToInt()}%)" } ?: ""
        return "$way ${fmt(abs(d), decimals)} ${c.unit}$pct $over — " +
            "${fmt(c.from!!, decimals)} to ${fmt(c.to!!, decimals)} ${c.unit}."
    }

    /** Below this the two readings are the same reading twice, whatever the unit. */
    const val STEADY_EPSILON: Double = 0.05

    /** A base at or below this has no meaningful percentage — see [Change.fraction]. */
    const val FRACTION_FLOOR: Double = 1e-6

    // -------------------------------------------------------------------------- reading it back

    /** How coarsely to read the record. */
    enum class Grain(val label: String) {
        DAY("Daily"),
        WEEK("Weekly"),
        MONTH("Monthly"),
    }

    /**
     * One bucket of the record.
     *
     * ⚠️ [days] and [loggedDays] are both here and both are load-bearing. A [total] over a part-week
     * is genuinely smaller than one over a whole week and comparing them side by side is misleading,
     * so the denominator has to be on screen beside the number. [mean] is over the days that were
     * LOGGED, never over the days that existed — an unlogged day is absent, not a zero, which is the
     * same rule [IntakeWeek] and [Expenditure] both hold and for the same reason: averaging zeros in
     * reports a starving person for anybody who skipped a weekend.
     */
    data class Bucket(
        val startMs: Long,
        val days: Int,
        val loggedDays: Int,
        val total: Double,
        val mean: Double?,
    ) {
        /** How complete the record is across this bucket, 0..1. */
        val completeness: Double get() = if (days <= 0) 0.0 else loggedDays.toDouble() / days
    }

    /**
     * Group [grid] into buckets and add up whatever [values] holds for each day.
     *
     * [bucketOf] maps a day start to the start of the bucket it belongs to, and there is **no
     * default** on purpose: a default of `it - it % (7 * DAY)` would be the day-arithmetic defect
     * this file's header describes, silently, for anybody who forgot to pass one. The caller has a
     * calendar; this does not.
     *
     * Buckets come back oldest first, and a bucket with no logged day at all is still returned — the
     * gap is part of the picture, and dropping it would make a fortnight off look like it never
     * happened.
     */
    fun bucket(
        grid: List<Long>,
        values: Map<Long, Double>,
        bucketOf: (Long) -> Long,
    ): List<Bucket> {
        if (grid.isEmpty()) return emptyList()
        val byBucket = HashMap<Long, MutableList<Long>>()
        for (d in grid) {
            byBucket.getOrPut(bucketOf(d)) { mutableListOf() } += d
        }
        // ⚠️ The ONE place the order is decided. A first version also sorted the grid on the way in,
        // which a perturbation proved does nothing: the counts and sums inside a bucket do not care
        // what order they arrived in, and this line re-orders the buckets regardless. Two statements
        // of one rule, one of them inert — so the inert one is gone rather than left to read as
        // load-bearing.
        return byBucket.entries
            .sortedBy { it.key }
            .map { (start, days) ->
                val logged = days.mapNotNull { values[it] }.filter { it.isFinite() }
                Bucket(
                    startMs = start,
                    days = days.size,
                    loggedDays = logged.size,
                    total = logged.sum(),
                    mean = if (logged.isEmpty()) null else logged.sum() / logged.size,
                )
            }
    }

    /**
     * How each bucket's mean compares with the one before it.
     *
     * ⚠️ Means and never totals, because consecutive buckets are not the same size — the first and
     * last of any grid are usually part-weeks — and a total-to-total comparison would report a
     * collapse in intake every time somebody opened the page mid-week. Null where either side has no
     * logged day, which is a gap rather than a fall to zero.
     */
    fun steps(buckets: List<Bucket>): List<Double?> =
        buckets.mapIndexed { i, b ->
            if (i == 0) null else {
                val prev = buckets[i - 1].mean
                val here = b.mean
                if (prev == null || here == null) null else here - prev
            }
        }

    private fun fmt(v: Double, decimals: Int): String {
        if (decimals <= 0) return v.roundToInt().toString()
        var scale = 1.0
        repeat(decimals) { scale *= 10.0 }
        val r = abs((v * scale).roundToInt())
        val sign = if (v < 0 && r != 0L.toInt()) "-" else ""
        val whole = r / scale.toInt()
        val frac = (r % scale.toInt()).toString().padStart(decimals, '0')
        return "$sign$whole.$frac"
    }
}
