package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What you ate against what you burned, day by day, over an interval you choose.
 *
 * This is the view that makes the rest of the system legible: [Expenditure] answers "how much do you
 * burn" with one number and a give-or-take, [MacroTargets] turns that into a target, and neither of
 * them shows you the thing you actually did. A person who has been logging for two months has sixty
 * real data points about their own metabolism and no way to look at them.
 *
 * ## ⚠️ The circularity, which is the whole design problem here
 *
 * [Expenditure.measure] derives expenditure *from* the weight change over its window:
 * `kcal = meanIntake − KCAL_PER_KG × Δweight / span`. So if this chart drew a single expenditure
 * figure measured over the interval and then said "your balance implies you lost 1.4 kg, and you
 * lost 1.4 kg", that would not be a validation. It would be one equation rearranged and printed
 * twice, with the appearance of agreement built in. A chart that can only ever agree with itself is
 * worse than no chart, because it teaches somebody to trust a number that was never checked.
 *
 * So the expenditure line here is **causal**: the reading carried by each day is the one the
 * measurement would have produced *from data available before that day began*, and nothing later.
 * Two consequences fall out of that and both are deliberate.
 *
 * ⚠️ **The trend cannot be sliced — it has to be re-estimated.** [BodyTrend.estimate] runs an RTS
 * smoother backwards over the whole series, so `points[i].trendKg` already knows about every later
 * weigh-in. Taking `trend.points.filter { it.atMs < d }` looks causal, compiles, produces a
 * plausible line and is quietly wrong: every point in it was informed by the future. This function
 * therefore takes the raw [BodyTrend.Weighin] list and re-runs the estimator on the prefix.
 *
 * ⚠️ **And the check is a CONSISTENCY check, not an independent prediction.** Say so wherever it is
 * shown. A reading at day `d` looks back [Expenditure.DEFAULT_WINDOW_DAYS], and for days near the
 * end of the interval that window overlaps the interval itself — so the implied change and the
 * observed change are not fully independent. They are not the same number either, which is the point:
 * they genuinely can and do disagree, and a large disagreement is real evidence of unlogged food or a
 * weigh-in typed wrong. Small agreement is weak evidence and nothing more.
 *
 * ## The step
 *
 * Expenditure is re-measured every [STEP_DAYS] days rather than every day, which is both what the app
 * actually does — see [CheckIn], where targets are published on a cadence — and what makes the line
 * describe something a person can act on. A figure that moved every day would be the drifting target
 * that [CheckIn] exists to remove, drawn as a chart.
 *
 * Pure, clock-free and zone-free: the day grid is passed in as a list, the same arrangement
 * [IntakeWeek] uses and for the same reason — the days this scores and the days a screen draws are
 * then one list rather than two expressions that can drift.
 */
object EnergyBalance {

    /**
     * How often the expenditure reading is re-measured across the interval.
     *
     * ⚠️ Matches [CheckIn.INTERVAL_DAYS] in value and in meaning, and is declared separately on
     * purpose: this one is a drawing decision about a chart of the past, that one is a promise about
     * what somebody is asked to eat. They should be able to move apart without one silently dragging
     * the other.
     */
    const val STEP_DAYS: Int = 7

    /** Below this many days carrying both sides, the totals are not worth stating. */
    const val MIN_PAIRED_DAYS: Int = 7

    /**
     * The intervals a person can ask for, so both applications offer the same ones under the same
     * words rather than each inventing a set.
     *
     * ⚠️ The shortest is a fortnight and not a week, and the reason is [MIN_PAIRED_DAYS] rather than
     * anything to do with [Expenditure]'s own minimum span — that one governs the WINDOW behind each
     * reading, which is [Expenditure.DEFAULT_WINDOW_DAYS] whatever interval is being drawn, so a
     * fortnight measures perfectly well. What a seven-day option would need is every single one of its
     * days paired to clear the floor, so one missed meal turns it into a button that answers "not
     * yet". A fortnight has room to lose a few days and still say something.
     */
    enum class Span(val days: Int, val label: String) {
        FORTNIGHT(14, "2 WEEKS"),
        MONTH(30, "30 DAYS"),
        QUARTER(90, "90 DAYS"),
    }

    /**
     * How far the trend is allowed to be extrapolated to reach an interval boundary, in days.
     *
     * The observed change is read off the smoothed trend at each end of the paired span. A boundary
     * further than this from any weigh-in has no observation behind it, and the honest answer there is
     * that the scale cannot say — not an interpolation across a gap nobody stood on.
     */
    const val BOUNDARY_TOLERANCE_DAYS: Double = 4.0

    /** A day of the interval, with whichever sides of it are known. */
    data class Day(
        val dayStartMs: Long,
        /** What was eaten, or null on a day with no record. A logged fast is 0.0, not null. */
        val intakeKcal: Double?,
        /** The causal expenditure reading in force that day, or null where the measurement could not run. */
        val expenditureKcal: Double?,
        /**
         * The give-or-take on [expenditureKcal].
         *
         * ⚠️ Carried per day rather than recomputed, because it is what sets the bar for remarking on
         * the interval at all — see [reconciliation]. Dropping it would leave that bar a hard-coded
         * number pretending to be a measurement.
         */
        val expenditureSdKcal: Double? = null,
    ) {
        /**
         * Intake minus expenditure, or null unless BOTH sides are known.
         *
         * ⚠️ The load-bearing rule of this file. A day with intake and no expenditure is not a day
         * with a huge surplus, and a day with expenditure and no intake is not a day of total
         * starvation — both are days we cannot speak about. Treating either as a zero on the missing
         * side would put a four-figure fiction into the interval total, and it would look exactly
         * like a real number.
         */
        val balanceKcal: Double? =
            if (intakeKcal != null && expenditureKcal != null) intakeKcal - expenditureKcal else null

        val paired: Boolean get() = balanceKcal != null
    }

    sealed interface Reading {
        /** Every day of the interval, in the order handed in, whether or not it carries anything. */
        val days: List<Day>

        /** How many of them carry both sides. */
        val pairedDays: Int

        data class Ready(
            override val days: List<Day>,
            override val pairedDays: Int,
            /** Eaten across the paired days only. */
            val intakeKcal: Double,
            /** Burned across the paired days only. */
            val expenditureKcal: Double,
            /** [intakeKcal] − [expenditureKcal]. Negative is a deficit. */
            val balanceKcal: Double,
            /** The same, per paired day. */
            val perDayKcal: Double,
            /** What that balance implies the body did, at [Expenditure.KCAL_PER_KG]. */
            val impliedChangeKg: Double,
            /** What the scale says it did across the same span, or null when no weigh-in is near an end. */
            val observedChangeKg: Double?,
            /** Observed minus implied, so positive means the scale moved up more than the food explains. */
            val gapKg: Double?,
            /**
             * The same gap as calories a day, which is the form somebody can act on.
             *
             * A kilogram over two months and a kilogram over a fortnight are very different findings,
             * and dividing by the days is what tells them apart.
             */
            val gapPerDayKcal: Double?,
            /**
             * The mean give-or-take on the expenditure readings in force across the paired days.
             *
             * ⚠️ **This is the bar for remarking on the gap at all**, and it is measured rather than
             * chosen. See [reconciliation].
             */
            val sdKcal: Double,
        ) : Reading

        data class NotYet(
            override val days: List<Day>,
            override val pairedDays: Int,
            val why: String,
        ) : Reading
    }

    /**
     * Build the interval.
     *
     * [days] is the ordered list of local day starts to draw, produced by a calendar. [weighins] and
     * [intake] should reach back at least [windowDays] before the first of them: a causal reading at
     * the start of the interval needs the window that precedes it, and handing in only the interval's
     * own data makes the first weeks of the line empty for no reason.
     */
    fun build(
        days: List<Long>,
        weighins: List<BodyTrend.Weighin>,
        intake: List<Expenditure.IntakeDay>,
        windowDays: Int = Expenditure.DEFAULT_WINDOW_DAYS,
        stepDays: Int = STEP_DAYS,
    ): Reading {
        if (days.isEmpty()) {
            return Reading.NotYet(emptyList(), 0, "No interval to show.")
        }
        val grid = days.sorted()
        val eaten = intake.filter { it.counted }.associateBy { it.dayStartMs }
        val step = stepDays.coerceAtLeast(1)

        // The causal readings, one per step boundary. Each is measured at the START of its day, so it
        // sees only what was known before that day began — which is also the figure somebody eating
        // to a plan would have been eating to that day.
        val readings = HashMap<Long, Expenditure.Estimate.Known?>()
        var i = 0
        while (i < grid.size) {
            readings[grid[i]] = causalExpenditure(weighins, intake, grid[i], windowDays)
            i += step
        }

        var carried: Expenditure.Estimate.Known? = null
        val out = ArrayList<Day>(grid.size)
        for (d in grid) {
            if (readings.containsKey(d)) carried = readings[d]
            out += Day(
                dayStartMs = d,
                intakeKcal = eaten[d]?.kcal,
                expenditureKcal = carried?.kcal,
                expenditureSdKcal = carried?.sdKcal,
            )
        }

        val paired = out.filter { it.paired }
        if (paired.size < MIN_PAIRED_DAYS) {
            return Reading.NotYet(
                days = out,
                pairedDays = paired.size,
                why = whyNotYet(paired.size, out),
            )
        }

        val intakeTotal = paired.sumOf { it.intakeKcal!! }
        val burnTotal = paired.sumOf { it.expenditureKcal!! }
        val balance = intakeTotal - burnTotal
        val implied = balance / Expenditure.KCAL_PER_KG
        val observed = observedChangeKg(weighins, paired.first().dayStartMs, paired.last().dayStartMs)
        val gap = observed?.let { it - implied }

        return Reading.Ready(
            days = out,
            pairedDays = paired.size,
            intakeKcal = intakeTotal,
            expenditureKcal = burnTotal,
            balanceKcal = balance,
            perDayKcal = balance / paired.size,
            impliedChangeKg = implied,
            observedChangeKg = observed,
            gapKg = gap,
            gapPerDayKcal = gap?.let { it * Expenditure.KCAL_PER_KG / paired.size },
            sdKcal = paired.sumOf { it.expenditureSdKcal ?: 0.0 } / paired.size,
        )
    }

    // ------------------------------------------------------------------------------ for drawing

    /**
     * The logged days, split into unbroken runs wherever the record has a hole.
     *
     * ⚠️ **A correctness rule rather than a styling one, which is why it is here and not in either
     * screen.** A chart that joins across an unlogged stretch draws a smooth line through a fortnight
     * of no data, and it reads exactly like a fortnight of steady eating. Both applications plot this
     * series, so both have to break it in the same places — the alternative is two copies of the rule
     * and, eventually, one of them not breaking.
     *
     * ⚠️ Runs of a single day are kept. Whether a lone point between two gaps can be DRAWN is the
     * chart's business — the LCARS kit refuses a series with fewer than two points, which is the right
     * answer for a line — but deciding that here would hide a real logged day from a caller that draws
     * bars, where one day is perfectly plottable.
     */
    fun intakeRuns(days: List<Day>): List<List<Pair<Long, Double>>> {
        val out = mutableListOf<List<Pair<Long, Double>>>()
        var run = mutableListOf<Pair<Long, Double>>()
        for (d in days) {
            val v = d.intakeKcal
            if (v == null) {
                if (run.isNotEmpty()) {
                    out += run
                    run = mutableListOf()
                }
            } else {
                run += d.dayStartMs to v
            }
        }
        if (run.isNotEmpty()) out += run
        return out
    }

    /** The expenditure line, which never has holes inside a run of drawn days once it has started. */
    fun burnSeries(days: List<Day>): List<Pair<Long, Double>> =
        days.mapNotNull { d -> d.expenditureKcal?.let { d.dayStartMs to it } }

    /**
     * The expenditure the measurement would have produced at the start of [atMs], from prior data only.
     *
     * ⚠️ `< atMs` and never `<=` on both sides. A weigh-in taken at the exact instant a day begins is
     * information from that day, and letting it in would make the first reading of every interval
     * quietly non-causal in a way no test of the interval as a whole would notice.
     *
     * Returns null for anything other than a usable figure — [Expenditure.Estimate.Doubtful] included.
     * A doubtful reading is the arithmetic reporting that the intake and the scale disagree with
     * physics, and plotting it would put the exact number the estimator refused to stand behind onto a
     * chart, where it would read as a measurement.
     */
    fun causalExpenditure(
        weighins: List<BodyTrend.Weighin>,
        intake: List<Expenditure.IntakeDay>,
        atMs: Long,
        windowDays: Int = Expenditure.DEFAULT_WINDOW_DAYS,
    ): Expenditure.Estimate.Known? {
        val before = weighins.filter { it.atMs < atMs }
        if (before.size < BodyTrend.MIN_FOR_RATE) return null
        val trend = BodyTrend.estimate(before)
        // ⚠️ **Unreachable, and kept anyway — measured, not assumed.** [Expenditure.measure] already
        // bounds intake to the span between the oldest and newest trend points in its window, and both
        // of those are strictly before `atMs` because of the filter above, so a later intake day can
        // never reach the arithmetic however this list is built. A perturbation removing this line
        // fails nothing, which is exactly what the probe showed. It stays because the causality of
        // this function should be visible in this function, rather than resting on an interior detail
        // of another one that has no reason to hold it forever.
        val priorIntake = intake.filter { it.dayStartMs < atMs }
        val e = Expenditure.measure(trend, priorIntake, atMs, windowDays)
        return (e as? Expenditure.Estimate.Known)?.takeIf { it.kcal.isFinite() && it.sdKcal.isFinite() }
    }

    /**
     * What the scale says the body did between two day starts.
     *
     * Read off the smoothed trend, which is the right tool HERE and the wrong one for the expenditure
     * line above: this is a retrospective measurement of the past, so a smoother that has seen the
     * whole series is simply a better estimate of it. Causality only matters where a figure is being
     * presented as something that could have been known at the time.
     */
    fun observedChangeKg(
        weighins: List<BodyTrend.Weighin>,
        fromMs: Long,
        toMs: Long,
    ): Double? {
        val trend = BodyTrend.estimate(weighins) as? BodyTrend.Trend.Estimated ?: return null
        val a = BodyTrend.nearest(trend, fromMs, BOUNDARY_TOLERANCE_DAYS) ?: return null
        val b = BodyTrend.nearest(trend, toMs, BOUNDARY_TOLERANCE_DAYS) ?: return null
        if (a.atMs == b.atMs) return null
        return b.trendKg - a.trendKg
    }

    // --------------------------------------------------------------------------------- the words

    /**
     * The interval in one sentence — what the balance was, and what it implies.
     *
     * ⚠️ Deliberately says "worked out to" rather than "you burned", because the expenditure side is
     * an estimate with a real interval on it and the intake side is what somebody remembered to log.
     */
    fun summary(r: Reading.Ready, unit: BodyTrend.MassUnit): String {
        val perDay = r.perDayKcal.roundToInt()
        val direction = if (r.balanceKcal < 0) "under" else "over"
        val size = abs(perDay)
        val change = abs(r.impliedChangeKg * unit.perKg)
        val way = if (r.impliedChangeKg < 0) "down" else "up"
        if (size < NEUTRAL_KCAL) {
            return "Across ${r.pairedDays} logged days you ate about what you burned, " +
                "which is what holding steady looks like."
        }
        return "Across ${r.pairedDays} logged days you ate $size kcal a day $direction what you " +
            "burned — enough to move you $way about ${fmt1(change)} ${unit.label}."
    }

    /**
     * Whether the gap between the scale and the food is worth remarking on at all.
     *
     * ⚠️ **The bar is the estimate's own give-or-take, not a fixed number of kilograms**, and that
     * came out of measuring rather than reasoning. A first draft used a flat 0.7 kg, which is wrong in
     * both directions: over a fortnight it fires on ordinary scale noise, and over a quarter it stays
     * silent through a real problem. [Reading.Ready.sdKcal] already carries what the estimator itself
     * thinks a day's expenditure figure is worth, so the same gap is measured against a bar that
     * widens when the data is thin and tightens when somebody weighs and logs consistently.
     *
     * Simulated across 60 days of a 500 kcal deficit, `sdKcal` is about 91 kcal for a fully-logged
     * 28-day window, and the gap that results is:
     *
     *     honest, +-1.2 kg of scale noise            66 kcal/day   silent
     *     under-logging 200 kcal/day for a month     74 kcal/day   silent
     *     a scale reading 1 kg heavy from halfway    99 kcal/day   remarks
     *     under-logging 400 kcal/day for a month    139 kcal/day   remarks
     */
    const val REMARK_SDS: Double = 1.0

    /**
     * The gap said in the two forms that are worth saying, or a plain statement that there is not one.
     *
     * ⚠️ Never phrased as a validation of the estimate — see this file's own header for why the two
     * sides are not independent. When they agree, the honest statement is that nothing is contradicting
     * anything.
     *
     * ⚠️ **And the causes it names were measured, not guessed.** An earlier draft blamed "a weigh-in
     * typed wrong"; simulating one shows [BodyTrend]'s outlier gate absorbs a single bad reading
     * completely — a 10 kg typo moves the gap by 0.04 kg, which is nothing. What does show is a
     * *sustained* change in the weighing, and food that goes unlogged. Those are the two named, because
     * naming a cause the system provably cannot detect sends somebody hunting for a fault that is not
     * there.
     */
    fun reconciliation(r: Reading.Ready, unit: BodyTrend.MassUnit): String? {
        val gap = r.gapKg ?: return null
        val perDay = r.gapPerDayKcal ?: return null
        if (abs(perDay) <= REMARK_SDS * r.sdKcal) {
            return "The scale moved about as much as the food says it should. That does not prove the " +
                "estimate right — the two are worked out from overlapping data — but nothing is " +
                "arguing with it."
        }
        val more = if (gap > 0) "more" else "less"
        val size = abs(gap * unit.perKg)
        return "The scale moved ${fmt1(size)} ${unit.label} $more than the food accounts for, which is " +
            "about ${abs(perDay).roundToInt()} kcal a day. Over an interval this long that is usually " +
            "food that went unlogged, or a change in how you weigh — a new scale, or a different time " +
            "of day. A single mistyped weigh-in would not do it; the trend filter absorbs those."
    }

    /**
     * A balance smaller than this per day is not worth calling a surplus or a deficit.
     *
     * ⚠️ Set at the size of the expenditure estimate's own everyday uncertainty rather than at zero.
     * A chart that announced a 30 kcal deficit would be reporting the width of its own error bar as a
     * finding.
     */
    const val NEUTRAL_KCAL: Int = 75

    private fun whyNotYet(paired: Int, days: List<Day>): String {
        val anyIntake = days.any { it.intakeKcal != null }
        val anyBurn = days.any { it.expenditureKcal != null }
        return when {
            !anyIntake && !anyBurn ->
                "Nothing logged over this interval yet, so there is nothing to balance."
            !anyBurn ->
                "Expenditure could not be measured over this interval. It needs a fortnight of " +
                    "weigh-ins behind each reading, so the line starts once there is one."
            !anyIntake ->
                "No food logged over this interval. The balance needs both sides."
            else ->
                "Only $paired days have both a food log and a measured expenditure. " +
                    "$MIN_PAIRED_DAYS is the least worth adding up."
        }
    }

    /**
     * One decimal, locale-independent, so a comma-decimal phone reads the same as a point-decimal one.
     *
     * ⚠️ The sign is carried separately rather than left to integer division, which truncates toward
     * zero: `-4 / 10` is `0`, so anything between −1 and 0 would print as a positive tenth. Both call
     * sites here happen to pass an absolute value, which is exactly the situation in which a latent
     * defect survives until somebody adds a third.
     */
    private fun fmt1(v: Double): String {
        val r = abs((v * 10.0).roundToInt())
        val sign = if (v < 0 && r != 0) "-" else ""
        return "$sign${r / 10}.${r % 10}"
    }
}
