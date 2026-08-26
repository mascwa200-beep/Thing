package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How many calories does this person actually burn in a day?
 *
 * Every calculator answers this from a formula — height, weight, age, and a multiplier picked off a
 * list of five vague activity descriptions. Those formulas are population averages and they are wrong
 * for an individual by up to several hundred calories in either direction, which is the whole reason
 * "I ate 1,800 calories and lost nothing" is such a common complaint. The formula was never about *you*.
 *
 * It can be measured instead. Energy balance says that over any window,
 *
 * ```
 * intake − expenditure = change in stored energy ≈ KCAL_PER_KG × change in weight
 * ```
 *
 * Everything on the left except expenditure is observable: intake is logged and the weight change comes
 * from [BodyTrend]. Rearranged, expenditure is a **measurement** of that particular body over that
 * particular fortnight, adaptation and all — no formula, no activity multiplier, no guessing.
 *
 * Pure, clock-free (now is a parameter) and I/O-free.
 *
 * ## The four things that make this honest rather than a party trick
 *
 * **1 · It refuses to answer early, and says what it is waiting for.** Two weeks of data on a
 * half-kilogram-a-week loss is a 1 kg signal under roughly 0.7 kg of scale noise. Printing a confident
 * "2,340 calories" on day three would be a number with a several-hundred-calorie error and no warning
 * attached, and a person would set their diet by it. [Estimate.NotYet] carries what is still missing.
 *
 * **2 · It carries an interval, and the interval is the point.** The answer is a difference of two
 * noisy quantities divided by a number of days; its uncertainty is large at first and shrinks slowly.
 * A caller that shows the number without the give-or-take is making the same overclaim [Novelty] refuses
 * to make about a surprisal beyond its sample's ceiling.
 *
 * **3 · Unlogged days are a bias, not an omission.** People stop logging on the days they eat most, so
 * the missing days are not missing at random. That cannot be corrected from here — but it can be
 * *priced*, which is what [UNLOGGED_BIAS_KCAL] does, and refused below [MIN_COMPLETENESS].
 *
 * **4 · ⚠️ Consistent mis-logging cancels, and this is the subtle part worth understanding before
 * anybody "fixes" it.** Almost everybody under-reports what they eat, often by hundreds of calories.
 * That does *not* break this. If somebody genuinely eats 2,400 and logs 2,100, the measured expenditure
 * comes out 300 low; the target derived from it is 300 low; and they hit that target by logging 300
 * short of what they eat — which is the right amount of food. The bias cancels end to end so long as it
 * is *consistent*, which is why no term for it appears in the variance below. What does not cancel is a
 * bias that changes, and nothing here can detect that.
 *
 * ## What this is not
 *
 * It is not a metabolic cart, it is not a diagnosis, and it cannot see a thyroid. It is a bookkeeping
 * identity applied to two noisy measurements.
 */
object Expenditure {

    private const val MS_PER_DAY: Double = 86_400_000.0

    // ------------------------------------------------------------------------------------ tuning

    /**
     * Energy per kilogram of body-mass change.
     *
     * ⚠️ An approximation, and knowingly so. 7,700 kcal/kg is the classic figure for adipose tissue (the
     * same number as "3,500 calories a pound"). Real weight change is not pure fat: the first week of
     * any deficit sheds glycogen and its bound water, which costs almost nothing, and a long deficit
     * also costs some lean mass. So the effective constant is *lower* than this early on, which makes a
     * short-window estimate over-state expenditure. That is one more reason the minimum window below is
     * a fortnight rather than a few days.
     */
    const val KCAL_PER_KG: Double = 7700.0

    /** The default look-back. Long enough for the weight signal to clear the noise, short enough to track a real change in pace. */
    const val DEFAULT_WINDOW_DAYS: Int = 28

    /**
     * Below this span of weigh-ins there is no measurement, only noise.
     *
     * ⚠️ Three weeks rather than the two the arithmetic technically allows, and the difference was
     * measured rather than argued. The uncertainty scales as `KCAL_PER_KG / span`, so it falls off
     * smoothly with no cliff to find — but the size of it at the short end decides whether an answer is
     * worth printing. Over forty synthetic runs with a known 2,600 kcal expenditure:
     *
     * | span | spread of the answer | worst of forty |
     * |---|---|---|
     * | 21 days | ±212 kcal | 581 kcal out |
     * | 28 days | ±137 | 300 |
     * | 56 days | ±86 | 171 |
     * | 90 days | ±66 | 170 |
     *
     * A fortnight would be ±320 or so. Three weeks is where an answer starts being worth more than the
     * formula it replaces, and it keeps getting better through the first two months.
     */
    const val MIN_SPAN_DAYS: Double = 21.0

    /**
     * How much the smoother's own uncertainty about the weight change is widened before it is reported.
     *
     * ⚠️ **A measured correction, not a derivation, and it is here rather than absent because shipping
     * an interval known to be narrow is the overclaim this whole core exists to avoid.** Over 500
     * synthetic runs per span, the real spread of the measured weight change against the standard
     * deviation [BodyTrend]'s smoother reports for it:
     *
     * | span | real spread | the smoother's own account | ratio |
     * |---|---|---|---|
     * | 22 days | 0.475 kg | 0.405 | 1.17 |
     * | 29 | 0.424 | 0.367 | 1.15 |
     * | 43 | 0.379 | 0.336 | 1.13 |
     * | 57 | 0.335 | 0.331 | 1.01 |
     * | 90 | 0.276 | 0.331 | 0.83 |
     *
     * The smoother is honest from about two months on and roughly a sixth narrow before that, because
     * its covariance describes its own model rather than the mismatch between that model and a real
     * body. 1.2 covers the short end; past two months it makes the interval slightly *wider* than it
     * needs to be, which is the safe direction.
     *
     * With it applied, the interval this core reports against the real spread of its own answer comes to
     * 0.98 of it at three weeks, 0.96 at four, 0.94 at six and 0.70 at three months — honest where the
     * answer is loosest and conservative once it is tight.
     *
     * ⚠️ There is **no meaningful bias** to correct alongside it: measured at −11, −12, −4, −2 and −4
     * kcal across those five spans. An earlier version of this note claimed a +25 kcal residual, which
     * was an artefact of a harness that seeded `java.util.Random` with consecutive small integers —
     * those produce correlated first draws, which put a spurious half-kilogram on the first weigh-in of
     * every run in the sample. One shared stream, and it disappears.
     */
    const val DELTA_SD_INFLATION: Double = 1.2

    /** Below this many logged days the intake side is a guess. */
    const val MIN_LOGGED_DAYS: Int = 10

    /** The fraction of days in the window that must be logged. */
    const val MIN_COMPLETENESS: Double = 0.6

    /**
     * How far an unlogged day is assumed to be able to differ from a logged one, in calories.
     *
     * ⚠️ Not a measurement — nobody can measure what was not logged. It is a deliberate price on the
     * missingness, applied in proportion to how much is missing, so that a half-logged fortnight comes
     * back with a visibly wider interval instead of a confident wrong number. Five hundred calories is
     * about the size of a meal that goes unrecorded.
     */
    const val UNLOGGED_BIAS_KCAL: Double = 500.0

    /** How stale the newest weigh-in may be before the window no longer describes now. */
    const val STALE_WEIGHIN_DAYS: Double = 7.0

    /** The band outside which a computed expenditure is a data problem rather than a person. */
    const val PLAUSIBLE_MIN_KCAL: Double = 800.0
    const val PLAUSIBLE_MAX_KCAL: Double = 8000.0

    /**
     * The spread of a formula estimate across individuals, as a fraction.
     *
     * ⚠️ This is why the formula loses to the measurement as soon as the measurement exists. Mifflin–St
     * Jeor predicts resting metabolic rate to roughly ±10% for most people and worse at the extremes,
     * and the activity multiplier on top is coarser still; 15% of a 2,400 kcal expenditure is ±360
     * calories. [blend] weights by the inverse of the variance, so the changeover happens on its own
     * when the measurement gets tighter than that, with no threshold to pick.
     */
    const val FORMULA_SD_FRACTION: Double = 0.15

    // ------------------------------------------------------------------------------------- types

    /**
     * A day's logged intake.
     *
     * [dayStartMs] is the start of that day **in the person's own zone**, decided by the caller — this
     * core has no timezone, and a day boundary taken in UTC is a day out for most of the world.
     */
    /**
     * One day's intake.
     *
     * ⚠️ [fasted] exists because zero calories and no record are different facts and were being
     * treated as one. The predicate here used to be `kcal > 0`, so a day somebody deliberately fasted
     * was dropped from the window AND counted against [completeness] — the same treatment as a day
     * they forgot. That is backwards twice over: the deliberate faster told us exactly what they ate,
     * and a long enough run of them could push a scrupulously tracked person to [Estimate.NotYet] for
     * tracking too well.
     *
     * A fasted day therefore contributes 0 kcal to the mean, which is true, and counts toward
     * completeness, which is also true. Nothing downstream needed changing: the variance, the
     * finite-population correction and the unlogged-day bias all already do the right thing once the
     * day is inside the window.
     *
     * ⚠️ Only an EXPLICIT mark counts. An absent day stays absent — inferring a fast from a missing
     * record would invent zeros for every day somebody simply did not open the app, which is the
     * larger of the two errors by a wide margin.
     */
    data class IntakeDay(
        val dayStartMs: Long,
        val kcal: Double,
        val fasted: Boolean = false,
    ) {
        /**
         * Whether this day is a record of what was eaten, as opposed to a gap.
         *
         * The single definition of "logged". It was written out twice before, which is how a
         * predicate drifts.
         */
        val counted: Boolean get() = kcal.isFinite() && (kcal > 0.0 || fasted)
    }

    /** The five multipliers that turn a resting rate into a daily one. Standard, and coarse. */
    enum class Activity(val multiplier: Double, val label: String) {
        SEDENTARY(1.20, "Desk-bound, little walking"),
        LIGHT(1.375, "On your feet some of the day, or 1–3 sessions a week"),
        MODERATE(1.55, "Active most days, or 3–5 sessions a week"),
        HIGH(1.725, "Hard training most days, or a physical job"),
        VERY_HIGH(1.90, "Hard training twice a day, or heavy manual work"),
    }

    /** Where a figure came from, so the surface never has to guess how much to trust it. */
    enum class Source {
        /** Mifflin–St Jeor times an activity multiplier. A population average wearing your numbers. */
        FORMULA,

        /** Measured from this person's own intake and weight change. */
        MEASURED,

        /** The two, weighted by their own confidence. */
        BLENDED,
    }

    sealed interface Estimate {
        /** A usable figure. [sdKcal] is a one-standard-deviation give-or-take and must be shown. */
        data class Known(
            val kcal: Double,
            val sdKcal: Double,
            val source: Source,
            val windowDays: Double,
            val loggedDays: Int,
            val completeness: Double,
        ) : Estimate

        /** The arithmetic ran and produced something a human body cannot do. Almost always a logging gap. */
        data class Doubtful(
            val kcal: Double,
            val windowDays: Double,
            val loggedDays: Int,
            val completeness: Double,
            val why: String,
        ) : Estimate

        /** Not enough yet, and exactly what is missing. */
        data class NotYet(
            val spanDays: Double,
            val loggedDays: Int,
            val neededSpanDays: Double,
            val neededLoggedDays: Int,
            val why: String,
        ) : Estimate
    }

    // ------------------------------------------------------------------------------- the formula

    /** Resting metabolic rate, Mifflin–St Jeor. See [Body.bmr] — repeated here only as a reminder of the input. */
    fun fromFormula(bmrKcal: Double, activity: Activity): Estimate.Known {
        val kcal = bmrKcal * activity.multiplier
        return Estimate.Known(
            kcal = kcal,
            sdKcal = kcal * FORMULA_SD_FRACTION,
            source = Source.FORMULA,
            windowDays = 0.0,
            loggedDays = 0,
            completeness = 0.0,
        )
    }

    // ---------------------------------------------------------------------------- the measurement

    /**
     * Measure expenditure from logged intake and a weight trend.
     *
     * The window runs back [windowDays] from [nowMs], or to the start of the record if that is shorter —
     * a person twenty days in gets a twenty-day answer rather than a refusal.
     */
    fun measure(
        trend: BodyTrend.Trend,
        intake: List<IntakeDay>,
        nowMs: Long,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): Estimate {
        if (trend !is BodyTrend.Trend.Estimated) {
            return Estimate.NotYet(
                spanDays = 0.0,
                loggedDays = intake.count { it.counted },
                neededSpanDays = MIN_SPAN_DAYS,
                neededLoggedDays = MIN_LOGGED_DAYS,
                why = "No weigh-ins yet. Expenditure is measured from what the scale does, so it needs some.",
            )
        }

        val windowStart = nowMs - (windowDays * MS_PER_DAY).toLong()
        val inWindow = trend.points.filter { it.atMs >= windowStart }
        val first = inWindow.firstOrNull()
        val last = inWindow.lastOrNull()
        val logged = intake.filter { it.counted }

        if (first == null || last == null || first === last) {
            return notYet(0.0, logged.size, "two weigh-ins inside the last $windowDays days")
        }

        val span = (last.atMs - first.atMs) / MS_PER_DAY
        if (span < MIN_SPAN_DAYS) {
            return notYet(span, logged.size, null)
        }
        if ((nowMs - last.atMs) / MS_PER_DAY > STALE_WEIGHIN_DAYS) {
            return Estimate.NotYet(
                spanDays = span,
                loggedDays = logged.size,
                neededSpanDays = MIN_SPAN_DAYS,
                neededLoggedDays = MIN_LOGGED_DAYS,
                why = "The last weigh-in is over a week old, so this cannot describe now. Step on the scale.",
            )
        }

        // Intake days inside the weighed span. A day counts when it starts within it: that is the same
        // count as the physically-right set (the days *between* the two weigh-ins), shifted by the same
        // part-day at each end, and the count is what the mean is taken over.
        val window = logged.filter { it.dayStartMs >= first.atMs && it.dayStartMs < last.atMs }
        val days = kotlin.math.max(1, kotlin.math.round(span).toInt())
        val n = window.size
        val completeness = (n.toDouble() / days).coerceAtMost(1.0)
        if (n < MIN_LOGGED_DAYS || completeness < MIN_COMPLETENESS) {
            return Estimate.NotYet(
                spanDays = span,
                loggedDays = n,
                neededSpanDays = MIN_SPAN_DAYS,
                neededLoggedDays = kotlin.math.max(MIN_LOGGED_DAYS, kotlin.math.ceil(days * MIN_COMPLETENESS).toInt()),
                why = "Only $n of the last $days days are logged. Expenditure needs most of them.",
            )
        }

        val meanIntake = window.sumOf { it.kcal } / n
        val variance = if (n > 1) window.sumOf { (it.kcal - meanIntake) * (it.kcal - meanIntake) } / (n - 1) else 0.0

        // Sampling error on the window's mean, with the finite-population correction: with every day
        // logged there is no sampling error at all, because the days *are* the population.
        val sampling = (variance / n) * (1.0 - n.toDouble() / days).coerceAtLeast(0.0)
        val missing = 1.0 - completeness
        val bias = (missing * UNLOGGED_BIAS_KCAL) * (missing * UNLOGGED_BIAS_KCAL)
        val intakeVar = sampling + bias

        val deltaKg = last.trendKg - first.trendKg
        // The two smoothed endpoints are positively correlated, so summing their variances over-states
        // the spread — and measurement says that over-statement is not enough on its own at short
        // spans, hence the inflation. See DELTA_SD_INFLATION for the numbers.
        val deltaVar = (last.trendSdKg * last.trendSdKg + first.trendSdKg * first.trendSdKg) *
            DELTA_SD_INFLATION * DELTA_SD_INFLATION

        val perDay = KCAL_PER_KG / span
        val kcal = meanIntake - perDay * deltaKg
        val sd = sqrt(intakeVar + perDay * perDay * deltaVar)

        if (!kcal.isFinite() || kcal < PLAUSIBLE_MIN_KCAL || kcal > PLAUSIBLE_MAX_KCAL) {
            return Estimate.Doubtful(
                kcal = kcal,
                windowDays = span,
                loggedDays = n,
                completeness = completeness,
                why = "The intake and the scale disagree with physics over these $days days. " +
                    "That is nearly always unlogged food or a weigh-in typed wrong.",
            )
        }

        return Estimate.Known(
            kcal = kcal,
            sdKcal = sd,
            source = Source.MEASURED,
            windowDays = span,
            loggedDays = n,
            completeness = completeness,
        )
    }

    private fun notYet(span: Double, logged: Int, need: String?): Estimate.NotYet = Estimate.NotYet(
        spanDays = span,
        loggedDays = logged,
        neededSpanDays = MIN_SPAN_DAYS,
        neededLoggedDays = MIN_LOGGED_DAYS,
        why = if (need != null) "Expenditure needs $need." else
            "Two weeks of weigh-ins is the minimum — under that, the scale's noise is bigger than the signal.",
    )

    // ------------------------------------------------------------------------------------- blend

    /**
     * Combine the formula guess with the measurement, each weighted by how sure it is.
     *
     * ⚠️ This is why there is no "switch to adaptive after N days" setting anywhere. Inverse-variance
     * weighting does it on its own: a fortnight in, the measurement's interval is wide and the formula
     * carries most of the answer; a month in, the measurement is tighter than ±15% and the formula
     * stops mattering. Nothing has to decide when, and there is no day on which the number jumps.
     */
    fun blend(formula: Estimate.Known, measured: Estimate.Known): Estimate.Known {
        soleSide(formula, measured)?.let { return it }
        val wf = 1.0 / (formula.sdKcal * formula.sdKcal)
        val wm = 1.0 / (measured.sdKcal * measured.sdKcal)
        val sum = wf + wm
        return Estimate.Known(
            kcal = (formula.kcal * wf + measured.kcal * wm) / sum,
            sdKcal = sqrt(1.0 / sum),
            source = Source.BLENDED,
            windowDays = measured.windowDays,
            loggedDays = measured.loggedDays,
            completeness = measured.completeness,
        )
    }

    /**
     * How much of the blended answer came from the measurement, 0..1 — worth showing while it climbs.
     *
     * ⚠️ Derived from the same [soleSide] as [blend], so the share can never describe a split the blend
     * did not make. The comparison is referential rather than structural on purpose: [soleSide] returns
     * one of its own two arguments, and two estimates that happen to hold equal numbers are still
     * different sides of the question.
     */
    fun measuredShare(formula: Estimate.Known, measured: Estimate.Known): Double {
        val sole = soleSide(formula, measured)
        if (sole != null) return if (sole === measured) 1.0 else 0.0
        val wf = 1.0 / (formula.sdKcal * formula.sdKcal)
        val wm = 1.0 / (measured.sdKcal * measured.sdKcal)
        return wm / (wf + wm)
    }

    /**
     * Which side [blend] takes outright, or null when it genuinely mixes them.
     *
     * ⚠️ **A zero interval is CERTAINTY, not a broken input, and conflating the two was a real bug here.**
     * Inverse-variance weighting gives a zero-variance estimate infinite weight, so that side *is* the
     * answer. The first version of this guard read `if (variance <= 0) return theOtherSide`, which threw
     * away the perfectly known figure in favour of the guess beside it — exactly backwards. An unusable
     * interval (negative, NaN) is the opposite case and does defer to the other side.
     *
     * Both sides unusable still has to return something; the measurement is the better default, since it
     * is at least about this person.
     */
    private fun soleSide(formula: Estimate.Known, measured: Estimate.Known): Estimate.Known? {
        val formulaBroken = !formula.sdKcal.isFinite() || formula.sdKcal < 0.0
        val measuredBroken = !measured.sdKcal.isFinite() || measured.sdKcal < 0.0
        return when {
            formulaBroken -> measured
            measuredBroken -> formula
            formula.sdKcal == 0.0 -> formula
            measured.sdKcal == 0.0 -> measured
            else -> null
        }
    }

    // ----------------------------------------------------------------------------------- wording

    fun sentence(estimate: Estimate): String = when (estimate) {
        is Estimate.Known -> when (estimate.source) {
            Source.FORMULA ->
                "About ${round50(estimate.kcal)} calories a day, from the formula — give or take ${round50(estimate.sdKcal)}. " +
                    "Log for a fortnight and this becomes a measurement of you."
            Source.MEASURED ->
                "Measured at ${round50(estimate.kcal)} calories a day, give or take ${round50(estimate.sdKcal)}, " +
                    "from ${estimate.loggedDays} logged days and ${fmt0(estimate.windowDays)} days of weigh-ins."
            Source.BLENDED ->
                "About ${round50(estimate.kcal)} calories a day, give or take ${round50(estimate.sdKcal)} — " +
                    "part measured from your own ${estimate.loggedDays} logged days, part still the formula."
        }
        is Estimate.Doubtful -> estimate.why
        is Estimate.NotYet -> estimate.why
    }

    /**
     * Calories, to the nearest fifty.
     *
     * ⚠️ Rounding is not cosmetic here. The interval on this figure is a hundred calories at best, so
     * printing "2,437" claims a precision the measurement does not have, and people read trailing digits
     * as accuracy.
     */
    fun round50(kcal: Double): String {
        if (!kcal.isFinite()) return "—"
        val v = (kcal / 50.0).roundToInt() * 50
        return String.format(java.util.Locale.US, "%,d", v)
    }

    private fun fmt0(v: Double): String =
        if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%.0f", abs(v))
}
