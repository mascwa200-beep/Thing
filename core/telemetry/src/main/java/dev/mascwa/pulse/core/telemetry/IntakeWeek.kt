package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How a run of days went, rather than how today is going.
 *
 * Everything else in this tab answers "can I eat this?". This answers the question a person actually
 * has after a fortnight — whether the plan is being followed at all — and it is the one number that
 * makes the calorie target trustworthy or not. An expenditure measured from a log with four days
 * missing is measured from a fiction.
 *
 * The source is [FoodLogStore]'s per-day nutrient map, which the store has published on every index
 * rebuild since it was written and nothing has ever read.
 *
 * ⚠️ **Two rules carry the honesty of the whole file, and both are about what a missing day means.**
 *
 *  1. **An unlogged day is not a zero-calorie day.** The store's own note says so where it builds the
 *     intake series, and averaging a zero in would report a starving person for anybody who skipped a
 *     weekend. Every figure here is computed over the days that WERE logged, and the count of those
 *     days is reported beside it — because "on target four times" means one thing at four days out of
 *     four and something else entirely at four out of ten.
 *  2. **Today is partial.** Its calories are incomplete until the day ends, so counting it toward
 *     adherence would mark every single day under target until dinner. Today appears in the series
 *     for the chart, flagged, and is excluded from the verdict.
 */
object IntakeWeek {

    const val DAY_MS = 86_400_000L

    /** A week, because that is the unit people plan food in. */
    const val DEFAULT_WINDOW_DAYS = 7

    /**
     * Within a tenth of the target counts as on it.
     *
     * ⚠️ A band, not an exact figure, and a fairly generous one. Nobody hits a calorie number, food
     * labels are rounded before anyone weighs anything, and a measure that reports failure on a day
     * 40 kcal out is a measure people stop reading. At 2,000 kcal this is ±200.
     */
    const val ON_TARGET_BAND = 0.10

    /**
     * Below this, no verdict.
     *
     * ⚠️ Two logged days is not a pattern, and "you were on target 100% of the time" off a single
     * Tuesday is the kind of confident nonsense this whole feature is built to avoid.
     */
    const val MIN_LOGGED_DAYS = 3

    /** One day, judged. [partial] is today, which is not finished and so does not count. */
    data class DayScore(
        val dayStartMs: Long,
        val kcal: Double,
        val partial: Boolean,
    ) {
        fun standing(targetKcal: Int): Standing = when {
            targetKcal <= 0 -> Standing.UNKNOWN
            abs(kcal - targetKcal) <= targetKcal * ON_TARGET_BAND -> Standing.ON_TARGET
            kcal > targetKcal -> Standing.OVER
            else -> Standing.UNDER
        }
    }

    enum class Standing { ON_TARGET, OVER, UNDER, UNKNOWN }

    /**
     * The window's shape.
     *
     * [days] is oldest-first and holds only the days with something logged — a chart of it therefore
     * has gaps, which is the truthful picture. [loggedDays] against [windowDays] is the completeness
     * that prices every other figure here.
     */
    data class Week(
        /**
         * The first day the window covers.
         *
         * ⚠️ Carried rather than left to the caller. A chart drawing one bar per day has to know
         * where the row starts, and the obvious way to guess — the oldest logged day, or the newest
         * minus the window — is wrong the moment either end of the window has nothing logged: the
         * whole row shifts and the gaps land on the wrong days. This is the only correct answer and
         * `score` already has it.
         */
        val windowStartMs: Long,
        val windowDays: Int,
        val loggedDays: Int,
        val onTargetDays: Int,
        val overDays: Int,
        val underDays: Int,
        val meanKcal: Double,
        val meanProteinG: Double,
        val meanFatG: Double,
        val meanCarbG: Double,
        val days: List<DayScore>,
    ) {
        /** How much of the window has anything in it, 0..1. */
        val completeness: Double get() = if (windowDays <= 0) 0.0 else loggedDays.toDouble() / windowDays

        /** Whether there is enough here to say anything at all. */
        val judgeable: Boolean get() = judgedDays >= MIN_LOGGED_DAYS

        /** Logged days excluding today, which is the population every verdict is computed over. */
        val judgedDays: Int get() = onTargetDays + overDays + underDays
    }

    /**
     * Score a window ending today.
     *
     * @param byDay the store's per-day totals, keyed by local day start.
     * @param targetKcal the plan's calorie target, or null when there is no plan.
     *
     * Returns null with no target: "on target" needs a target, and there is no sensible default —
     * substituting one would report adherence to a plan nobody made.
     */
    fun score(
        byDay: Map<Long, NutritionDay.Nutrients>,
        targetKcal: Int?,
        todayStartMs: Long,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): Week? {
        val target = targetKcal?.takeIf { it > 0 } ?: return null
        val window = windowDays.coerceAtLeast(1)
        val from = todayStartMs - (window - 1) * DAY_MS

        // ⚠️ `kcal > 0` is what "logged" means, matching the store's own intake series exactly. A day
        // present in the map with nothing in it is a day somebody opened and did not use.
        val scored = byDay.entries
            .filter { it.key in from..todayStartMs && it.value.kcal > 0.0 }
            .sortedBy { it.key }
            .map { (day, n) -> DayScore(day, n.kcal, partial = day == todayStartMs) to n }

        val judged = scored.filterNot { it.first.partial }
        val standings = judged.map { it.first.standing(target) }

        return Week(
            windowStartMs = from,
            windowDays = window,
            loggedDays = scored.size,
            onTargetDays = standings.count { it == Standing.ON_TARGET },
            overDays = standings.count { it == Standing.OVER },
            underDays = standings.count { it == Standing.UNDER },
            // ⚠️ Means over the JUDGED days, not all logged ones. Today's half-finished total would
            // drag every average down and make the week look like a deficit nobody ran.
            meanKcal = judged.map { it.second.kcal }.mean(),
            meanProteinG = judged.map { it.second.proteinG }.mean(),
            meanFatG = judged.map { it.second.fatG }.mean(),
            meanCarbG = judged.map { it.second.carbG }.mean(),
            days = scored.map { it.first },
        )
    }

    private fun List<Double>.mean(): Double = if (isEmpty()) 0.0 else sum() / size

    /**
     * What the window says, or null when it is too thin to say anything.
     *
     * ⚠️ Completeness leads whenever the window is patchy, because it is the more important fact. A
     * person logging three days in ten does not need to be told their average — they need to know
     * that the average, and the calorie target derived from it, rest on three days.
     */
    fun verdict(w: Week): String? {
        if (!w.judgeable) {
            return null
        }
        val head = "On target ${w.onTargetDays} of ${w.judgedDays} finished days"
        val drift = when {
            w.overDays > w.underDays -> " — the rest mostly over"
            w.underDays > w.overDays -> " — the rest mostly under"
            w.overDays == 0 && w.underDays == 0 -> ""
            else -> " — the rest split either way"
        }
        return head + drift + ", averaging ${w.meanKcal.roundToInt()} kcal."
    }

    /**
     * How much of the window is actually filled in, said plainly, or null when it is essentially full.
     *
     * ⚠️ Silent above the threshold on purpose. A completeness line on a week with one day missing is
     * pedantry, and a caveat that appears every single time stops being read — which would cost it its
     * force on the week that genuinely is three days in ten.
     */
    fun completenessNote(w: Week): String? {
        if (w.loggedDays >= w.windowDays - 1) return null
        if (w.loggedDays == 0) {
            return "Nothing logged in the last ${w.windowDays} days, so there is nothing to measure yet."
        }
        return "${w.loggedDays} of the last ${w.windowDays} days have anything logged. " +
            "Everything above is measured from those, and so is the calorie target."
    }
}
