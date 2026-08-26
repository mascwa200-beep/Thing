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

    // --------------------------------------------------------------------------------------- steps

    /**
     * One day's step count.
     *
     * ⚠️ Steps and NOT a wearable's calorie estimate, and the distinction is the whole reason this
     * exists. A step count is a measurement — a number of events a sensor counted. A wrist device's
     * calorie figure is a proprietary model's opinion, and the published comparisons put it more
     * than ten per cent out in the large majority of cases, in both directions, without saying which.
     * Nothing here will ever accept one.
     */
    data class StepDay(val dayStartMs: Long, val steps: Int)

    /**
     * The published step-count categories (Tudor-Locke and Bassett, 2004), which are what a
     * pedometer reading is conventionally read against.
     *
     * ⚠️ These are NOT the [Activity] multipliers wearing different names. They classify walking
     * volume; the multipliers describe total daily activity, of which walking is one part. Mapping
     * one onto the other is a judgement — see [suggestedActivity] — not an equation.
     */
    enum class StepBand(val label: String, val floor: Int) {
        SEDENTARY("Sedentary", 0),
        LOW_ACTIVE("Low active", 5_000),
        SOMEWHAT_ACTIVE("Somewhat active", 7_500),
        ACTIVE("Active", 10_000),
        HIGHLY_ACTIVE("Highly active", 12_500),
    }

    /**
     * Which band a mean daily step count falls in.
     *
     * ⚠️ `lastOrNull`, not `last`. A negative count satisfies no band's floor and `last {}` throws on
     * an empty match — so a sensor that reported nonsense would take the whole screen down rather
     * than reading as sedentary, which is what a negative step count means in every real case.
     */
    fun stepBand(meanPerDay: Int): StepBand =
        StepBand.entries.lastOrNull { meanPerDay >= it.floor } ?: StepBand.SEDENTARY

    /**
     * The activity band a measured step volume supports, or null when it supports nothing more than
     * what is already set.
     *
     * ⚠️ **A floor, not a verdict, and it is deliberately conservative in three ways.**
     *
     * First, it never suggests [Activity.VERY_HIGH]. That band means hard training twice a day or
     * heavy manual work, and a pedometer cannot see either — a long dog walk and a shift on a
     * building site produce similar counts and nothing about them is similar.
     *
     * Second, it only ever suggests something HIGHER than the current setting. Somebody who trains
     * hard and takes three thousand steps has told us something a step counter cannot contradict,
     * and talking them down would be the app overruling a fact with a proxy.
     *
     * Third, that asymmetry is in the safe direction and worth naming: over-estimating expenditure
     * raises the calorie target, which slows progress and is recoverable. Under-estimating lowers
     * it, which means eating too little, and that is not.
     *
     * ⚠️ It is a SUGGESTION the person confirms, never an automatic override. The value it would
     * replace is one they typed, and silently rewriting somebody's own answer teaches them that the
     * setting does not mean anything.
     */
    fun suggestedActivity(band: StepBand, current: Activity): Activity? {
        val supported = when (band) {
            StepBand.SEDENTARY -> Activity.SEDENTARY
            StepBand.LOW_ACTIVE -> Activity.LIGHT
            StepBand.SOMEWHAT_ACTIVE -> Activity.LIGHT
            StepBand.ACTIVE -> Activity.MODERATE
            StepBand.HIGHLY_ACTIVE -> Activity.HIGH
        }
        // ⚠️ Compared by multiplier rather than by ordinal: the ordinal happens to ascend today, and
        // a comparison that depends on declaration order is one reordering away from being backwards.
        return supported.takeIf { it.multiplier > current.multiplier }
    }

    /** Each side of a step comparison needs at least this many days before it is worth believing. */
    const val MIN_STEP_DAYS_EACH_SIDE: Int = 5

    /** How recent "recently" is, when asking whether step volume has moved. */
    const val STEP_SHIFT_RECENT_DAYS: Int = 7

    /** A change smaller than this fraction of the earlier mean is noise, not a shift. */
    const val STEP_SHIFT_FRACTION: Double = 0.25

    /**
     * ⚠️ And an absolute floor beside the fraction, because a fraction alone is meaningless on a
     * small base: four hundred steps becoming six hundred is a fifty per cent rise and describes two
     * days spent equally on a sofa.
     */
    const val STEP_SHIFT_MIN_ABSOLUTE: Int = 1_500

    /**
     * How much a confirmed shift widens the measured interval.
     *
     * ⚠️ 1.5, and the mechanism matters more than the number. Widening the interval is how a shift
     * reaches the answer, because [blend] weights by inverse variance — so a wider measured interval
     * automatically hands weight to the formula, which is itself informed by the recent step count.
     * Nothing is discarded, no threshold is crossed, and the estimate cannot fall back to
     * [Estimate.NotYet] the way SHORTENING the window could.
     */
    const val STEP_SHIFT_SD_INFLATION: Double = 1.5

    /**
     * Whether daily step volume has moved enough that the older half of the window describes a
     * different way of living from the recent half.
     */
    data class StepShift(
        val earlierMean: Int,
        val recentMean: Int,
        val earlierDays: Int,
        val recentDays: Int,
        val changed: Boolean,
        val sentence: String,
    ) {
        /** Signed, so a caller can say which way without recomputing it. */
        val delta: Int get() = recentMean - earlierMean
    }

    /**
     * Compare the last [recentDays] against the rest of the window.
     *
     * ⚠️ Both sides need [MIN_STEP_DAYS_EACH_SIDE] real days. A comparison against two days is a
     * comparison against a weekend, and every week contains one.
     */
    fun stepShift(
        steps: List<StepDay>,
        nowMs: Long,
        recentDays: Int = STEP_SHIFT_RECENT_DAYS,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): StepShift {
        val windowStart = nowMs - (windowDays * MS_PER_DAY).toLong()
        val recentStart = nowMs - (recentDays * MS_PER_DAY).toLong()
        val usable = steps.filter { it.dayStartMs >= windowStart && it.steps >= 0 }
        val recent = usable.filter { it.dayStartMs >= recentStart }
        val earlier = usable.filter { it.dayStartMs < recentStart }

        if (recent.size < MIN_STEP_DAYS_EACH_SIDE || earlier.size < MIN_STEP_DAYS_EACH_SIDE) {
            return StepShift(
                earlierMean = earlier.meanSteps(),
                recentMean = recent.meanSteps(),
                earlierDays = earlier.size,
                recentDays = recent.size,
                changed = false,
                sentence = "Not enough days of step counts on both sides to say whether anything has changed.",
            )
        }

        val e = earlier.meanSteps()
        val r = recent.meanSteps()
        val diff = abs(r - e)
        val changed = diff >= STEP_SHIFT_MIN_ABSOLUTE && diff >= e * STEP_SHIFT_FRACTION
        val sentence = when {
            !changed -> "Your daily steps are about where they were — around $e a day."
            r > e ->
                "You are walking a good deal more than you were — about $r a day against $e. The " +
                    "measurement is averaging over both, so it will lag until the older days fall out " +
                    "of the window."
            else ->
                "You are walking a good deal less than you were — about $r a day against $e. The " +
                    "measurement is averaging over both, so it will lag until the older days fall out " +
                    "of the window."
        }
        return StepShift(e, r, earlier.size, recent.size, changed, sentence)
    }

    private fun List<StepDay>.meanSteps(): Int =
        if (isEmpty()) 0 else (sumOf { it.steps.toLong() } / size).toInt()

    // -------------------------------------------------------------------------- eating differently

    /**
     * Both sides of an intake comparison need this many logged days.
     *
     * ⚠️ Derived from the step figure rather than restated, because the reason is word for word the
     * same one: a comparison against two days is a comparison against a weekend, and every week
     * contains one. Two honest names, one number, and moving the step figure moves this with it.
     */
    const val MIN_INTAKE_DAYS_EACH_SIDE: Int = MIN_STEP_DAYS_EACH_SIDE

    /** How recent "recently" is, when asking whether intake has moved. */
    const val INTAKE_SHIFT_RECENT_DAYS: Int = STEP_SHIFT_RECENT_DAYS

    /**
     * A change smaller than this fraction of the earlier mean is day-to-day variation, not a change
     * of plan.
     *
     * ⚠️ Tighter than the step fraction, and the reason is that the two quantities are not alike.
     * Steps swing enormously between a quiet week and a busy one, so a quarter is the point at which
     * one stops being the other. Intake is far steadier for somebody tracking it, and a deliberate
     * change is typically three to five hundred calories — around fifteen per cent of an ordinary
     * day. A quarter would miss most real ones.
     */
    const val INTAKE_SHIFT_FRACTION: Double = 0.15

    /**
     * ⚠️ And an absolute floor beside the fraction, for the same reason the step one has one: on an
     * 1,100-calorie base fifteen per cent is 165 calories, which is a snack rather than a decision.
     * Two hundred and fifty a day is roughly half a pound a week of weight change, which is not.
     */
    const val INTAKE_SHIFT_MIN_ABSOLUTE: Double = 250.0

    /**
     * How much a confirmed intake shift widens the measured interval.
     *
     * ⚠️ The same figure as the step one and derived from it, because it is the same statement: the
     * window no longer describes one steady way of living.
     *
     * ⚠️ **But what widening BUYS here is weaker than it is for steps, and pretending otherwise would
     * be the overstatement this file is careful about elsewhere.** A step shift hands weight to the
     * formula, and the formula is itself informed by recent walking through [Activity] — so the
     * answer genuinely improves. Nothing in the formula knows anything about intake, so here the
     * blend leans on a number that is merely *uncontaminated* rather than better informed. What this
     * is really for is the interval and the sentence: a reader decides how much to trust a figure
     * from its give-or-take, and a stale window currently understates it while saying nothing at all
     * about why.
     */
    const val INTAKE_SHIFT_SD_INFLATION: Double = STEP_SHIFT_SD_INFLATION

    /**
     * Whether daily intake has moved enough that the older half of the window describes a different
     * way of eating.
     *
     * ⚠️ **This does NOT mean the arithmetic is wrong.** Energy balance over a window is an average,
     * and an average is valid whether or not intake was constant across it. What a shift breaks is
     * the assumption a reader makes about the answer — that it describes them *now*. It does not,
     * because the weight trend responds to an intake change with a lag, and because a body that
     * started eating differently a fortnight ago has probably started spending differently too.
     */
    data class IntakeShift(
        val earlierMean: Double,
        val recentMean: Double,
        val earlierDays: Int,
        val recentDays: Int,
        val changed: Boolean,
        val sentence: String,
    ) {
        /** Signed, so a caller can say which way without recomputing it. */
        val delta: Double get() = recentMean - earlierMean
    }

    /**
     * The identity for [widenForShifts] — nothing known about intake, so nothing to widen for.
     *
     * ⚠️ Exists so [widenForShift] can delegate instead of keeping a second copy of the widening.
     */
    val NO_INTAKE_SHIFT: IntakeShift =
        IntakeShift(0.0, 0.0, 0, 0, false, "Intake not considered.")

    /** Compare the last [recentDays] of logged intake against the rest of the window. */
    fun intakeShift(
        intake: List<IntakeDay>,
        nowMs: Long,
        recentDays: Int = INTAKE_SHIFT_RECENT_DAYS,
        windowDays: Int = DEFAULT_WINDOW_DAYS,
    ): IntakeShift {
        val windowStart = nowMs - (windowDays * MS_PER_DAY).toLong()
        val recentStart = nowMs - (recentDays * MS_PER_DAY).toLong()
        // ⚠️ `counted`, so a marked fast is a real zero-calorie day and an unopened one is a gap. That
        // predicate exists precisely so this distinction is made in one place — see [IntakeDay].
        val usable = intake.filter { it.dayStartMs >= windowStart && it.counted }
        val recent = usable.filter { it.dayStartMs >= recentStart }
        val earlier = usable.filter { it.dayStartMs < recentStart }

        if (recent.size < MIN_INTAKE_DAYS_EACH_SIDE || earlier.size < MIN_INTAKE_DAYS_EACH_SIDE) {
            return IntakeShift(
                earlierMean = earlier.meanKcal(),
                recentMean = recent.meanKcal(),
                earlierDays = earlier.size,
                recentDays = recent.size,
                changed = false,
                sentence = "Not enough logged days on both sides to say whether how much you eat has changed.",
            )
        }

        val e = earlier.meanKcal()
        val r = recent.meanKcal()
        val diff = abs(r - e)
        val changed = diff >= INTAKE_SHIFT_MIN_ABSOLUTE && diff >= e * INTAKE_SHIFT_FRACTION
        val sentence = when {
            !changed -> "You are eating about what you were — around ${e.roundToInt()} calories a day."
            r > e ->
                "You are eating a good deal more than you were — about ${r.roundToInt()} calories a " +
                    "day against ${e.roundToInt()}. The measurement averages over both, so it will " +
                    "lag until the older days fall out of the window."
            else ->
                "You are eating a good deal less than you were — about ${r.roundToInt()} calories a " +
                    "day against ${e.roundToInt()}. The measurement averages over both, so it will " +
                    "lag until the older days fall out of the window."
        }
        return IntakeShift(e, r, earlier.size, recent.size, changed, sentence)
    }

    private fun List<IntakeDay>.meanKcal(): Double =
        if (isEmpty()) 0.0 else sumOf { it.kcal } / size

    /**
     * Widen a measured estimate for every reason the window is not describing one steady way of
     * living.
     *
     * ⚠️ **The largest inflation, NOT the product, and that is the load-bearing decision.** Both
     * shifts assert the same thing — the older days no longer describe you — so multiplying them
     * gives 2.25× from two facts neither of which supports more than 1.5 on its own. That would push
     * a measured estimate almost entirely onto the formula on the strength of arithmetic rather than
     * evidence. The window is inhomogeneous; the worst inhomogeneity governs.
     */
    fun widenForShifts(
        measured: Estimate.Known,
        steps: StepShift,
        intake: IntakeShift,
    ): Estimate.Known {
        val inflation = maxOf(
            if (steps.changed) STEP_SHIFT_SD_INFLATION else 1.0,
            if (intake.changed) INTAKE_SHIFT_SD_INFLATION else 1.0,
        )
        return if (inflation <= 1.0) measured else measured.copy(sdKcal = measured.sdKcal * inflation)
    }

    /**
     * Widen a measured estimate's interval because the window spans a change in how much you move.
     *
     * ⚠️ **Widening, not discarding, and not shortening the window either.** The obvious response to
     * "the older days no longer describe you" is to measure over fewer days — but a shorter window
     * has fewer logged days in it, and dropping under [MIN_LOGGED_DAYS] turns a working estimate into
     * [Estimate.NotYet]. Somebody whose habits changed would be punished with no number at all for a
     * fortnight, which is precisely when they most want one. Widening keeps every day, states the
     * larger uncertainty honestly, and lets the inverse-variance blend shift weight on its own.
     *
     * Returns the estimate unchanged when nothing shifted, so a caller can apply it unconditionally.
     */
    fun widenForShift(measured: Estimate.Known, shift: StepShift): Estimate.Known =
        // ⚠️ Delegates rather than repeating the arithmetic. Two functions that both widen an
        // interval are two places the rule can drift, and this file has a note elsewhere about
        // exactly that happening to a sentence. `NO_INTAKE_SHIFT` is the identity for the plural
        // form, so this is the one-shift case of one definition.
        widenForShifts(measured, shift, NO_INTAKE_SHIFT)

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
