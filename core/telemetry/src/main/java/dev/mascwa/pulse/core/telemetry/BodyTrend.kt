package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * What does the scale actually say about you?
 *
 * A daily weight reading is mostly water, gut content and glycogen. A person losing half a kilogram a
 * week is looking for a signal of about 70 grams a day underneath a couple of kilograms of noise, which
 * is why looking at this morning's number against yesterday's is worse than useless — it is
 * overwhelmingly likely to have the wrong **sign**. Every serious body-weight tool therefore reports a
 * *trend*, not a reading.
 *
 * This estimates that trend, and — the part that matters more — how sure it is of the **rate**, because
 * the rate is what the coach turns into a calorie target and what a person acts on. An exponential
 * moving average (what most apps use) gives a smoothed line and no uncertainty at all, so it cannot tell
 * "you are losing 0.4 kg a week" from "you might be losing 0.4 kg a week, or gaining 0.1, there is no
 * way to know yet from six days of data". Those two are the same number and completely different advice.
 *
 * Pure, clock-free (every time is a parameter) and I/O-free, so every case below is reachable in a test.
 *
 * ## The model, and why this one
 *
 * A **local linear trend** state-space model: the hidden state is `[true weight, rate of change]`, the
 * rate wanders slowly, and each weigh-in is that weight plus scale noise. Estimated with a Kalman filter
 * forward and a Rauch–Tung–Striebel smoother backward.
 *
 * **Why a state-space model rather than an EWMA.** It carries a covariance, so the rate arrives with a
 * standard deviation ([Point.rateSdPerDayKg]) rather than as a bare slope. That single fact is what lets
 * the surface refuse to state a rate it cannot support — the same honesty constraint [Novelty] enforces
 * with its ceiling.
 *
 * **Why the trend and not just the level.** A local-*level* filter (weight as a random walk) is the
 * usual textbook choice and would smooth just as well, but the rate would then have to be recovered by
 * differencing the smoothed line, which throws away exactly the uncertainty the previous paragraph is
 * about. Carrying the rate as a state gives it a variance for free.
 *
 * **⚠️ What a sudden step does, because it looks wrong and is not.** A model that carries a rate has to
 * explain an overnight three-kilogram jump as *the rate went up*, so for a fortnight afterwards it
 * reports a real weekly gain and the trend briefly overshoots the new plateau before settling. Measured
 * on a noiseless 3 kg step with daily readings: 0.34 kg over forty days later, 0.03 kg *under* by
 * eighty, exact by a hundred and sixty; with realistic scale noise it is inside 0.2 kg within a month.
 * That is the honest reading of the evidence — somebody who is three kilograms heavier than last week
 * *has* recently gained — and the alternative (a level-only model) has no rate to report at all.
 *
 * **⚠️ Why the backward smoother is not optional, measured rather than assumed.** A forward filter has
 * only the past at every point, so the *earliest* reading in a record is left exactly where the scale put
 * it — carrying the full 0.7 kg of scale noise, with nothing to average it against. That is harmless on
 * a chart and expensive for [Expenditure], which measures the *difference* between two points on this
 * line: half its uncertainty then comes from one unlucky morning months ago.
 *
 * The smoother re-estimates every past point using the whole record. Over 400 synthetic runs per case
 * with a single well-mixed random stream:
 *
 * | | first point's spread | measured expenditure's spread, 43 days |
 * |---|---|---|
 * | forward filter only | ±0.73 kg | ±140 kcal |
 * | **with the smoother** | **±0.23 kg** | **±69 kcal** |
 *
 * So it **halves the uncertainty of the whole feature**, and neither version is meaningfully biased — an
 * earlier version of this note claimed the smoother removed a systematic lag error, which turned out to
 * be an artefact of a harness that seeded `java.util.Random` with consecutive small integers. The real
 * payoff is variance, not bias. The newest point is unaffected by definition (there is no future to add
 * to it), so the "today" figure is the same either way.
 *
 * ## Units
 *
 * Kilograms and days throughout, because the energy constant in [Expenditure] is per kilogram and
 * converting in the middle is how sign errors get in. [MassUnit] converts at the point of display, and
 * [rateSentence] takes the unit so the wording is tested rather than assembled in a composable.
 */
object BodyTrend {

    private const val MS_PER_DAY: Double = 86_400_000.0

    // ------------------------------------------------------------------------------------ tuning

    /**
     * The day-to-day noise on a single weigh-in, as a standard deviation in kilograms.
     *
     * ⚠️ This is **not** the scale's mechanical precision, which is a few tens of grams. It is the
     * spread of what a person genuinely weighs at the same hour on consecutive mornings — hydration,
     * gut content, glycogen and its bound water, salt. Published within-subject day-to-day variation
     * runs around 0.5–1 kg for adults weighing themselves under consistent conditions; 0.7 kg sits in
     * the middle of that and is the number the whole filter's responsiveness is set by.
     *
     * Raising it makes the trend calmer and slower to react; lowering it makes it chase the scale.
     */
    const val SCALE_NOISE_KG: Double = 0.7

    /**
     * How much the rate of change itself wanders, in kilograms per day, per day.
     *
     * ⚠️ **This is the one number that decides whether the trend is useful, and it was chosen by
     * measurement rather than taste.** It sets how much freedom the rate has, which sets how wide the
     * rate's interval settles, which decides whether [Point.rateIsClear] ever becomes true.
     *
     * Derivation: an adherent person's weekly rate drifts by roughly 0.2 kg/week over a month. Wander
     * of `w` per day accumulates as `w × √30` over a month, so `w ≈ (0.2 / 7) / √30 ≈ 0.005`.
     *
     * That derivation was then checked against the shipped filter over synthetic 90-day records with a
     * known −0.45 kg/week loss and 0.7 kg scale noise, forty seeds each:
     *
     * | wander | rate interval | detects a real 0.45 kg/wk loss | false direction on maintenance | days to follow a change of pace |
     * |---|---|---|---|---|
     * | 0.020 | ±0.39 kg/wk | **2 of 40** | 0 of 40 | 11 |
     * | 0.012 | ±0.27 | 15 of 40 | 0 of 40 | 14 |
     * | 0.008 | ±0.20 | 28 of 40 | 0 of 40 | 19 |
     * | **0.005** | **±0.14** | **39 of 40** | **0 of 40** | **22** |
     * | 0.003 | ±0.10 | 40 of 40 | 0 of 40 | 28 |
     *
     * The first draft of this file used 0.020 on reasoning alone, and it produced a filter that told
     * somebody who had visibly lost four kilograms over three months that they were "holding steady".
     * The cost of 0.005 is the last column: about three weeks to follow a genuine change of pace, which
     * is the right speed for a coach that must not thrash.
     *
     * For scale, ordinary least squares on thirty daily readings gives a slope interval of about
     * ±0.10 kg/week and assumes the rate is *perfectly constant*. This filter allows the rate to move
     * and still lands at ±0.15, so it is not over-confident against that bound.
     */
    const val TREND_WANDER_KG_PER_DAY: Double = 0.005

    /**
     * The prior standard deviation on the rate before any rate has been observed, in kg/day.
     *
     * ⚠️ **Deliberately tight, and that is the opposite of what I expected.** The obvious reasoning says
     * a wide prior is more honest — the filter does not know the rate, so it should say so. Measured
     * over 200 synthetic runs, widening it makes the *weight change* the whole feature depends on
     * systematically worse:
     *
     * | prior | bias in the measured 21-day weight change |
     * |---|---|
     * | **0.1 kg/day** | **−0.08 kg** |
     * | 0.3 | −0.16 |
     * | 1.0 (near-diffuse) | −0.17 |
     *
     * A wide prior lets the backward smoother pull the earliest point onto the extrapolated trend line
     * instead of onto its own reading, which stretches the measured change and inflates the expenditure
     * derived from it. 0.1 kg/day is 0.7 kg a week in either direction — wide enough not to fight real
     * data, tight enough that the first reading still anchors itself.
     */
    const val INITIAL_RATE_SD_KG_PER_DAY: Double = 0.1

    /**
     * How far off the prediction a reading may fall, in standard deviations, before it is treated as
     * suspect.
     *
     * ⚠️ This exists because a mistyped weigh-in has physical consequence here: it moves the trend, the
     * trend moves the measured expenditure, and the expenditure moves the calorie target a real person
     * eats to. Four sigma is deliberately loose — a genuine heavy meal or a long-haul flight can put a
     * morning two kilograms out, and those readings are real and should count.
     *
     * Past the gate the reading is not thrown away; its noise is widened in proportion to how far out
     * it is — see [suspectNoiseFactor].
     */
    const val OUTLIER_GATE_SDS: Double = 4.0

    /**
     * How many standard deviations the rate must clear before it is stated as a direction rather than
     * as noise. Two-sided 95%.
     */
    const val RATE_CLEAR_SDS: Double = 1.96

    /** Below this many weigh-ins there is no rate to speak of, whatever the arithmetic returns. */
    const val MIN_FOR_RATE: Int = 2

    /**
     * By how much a suspect reading's noise is widened, from how far past the gate it fell.
     *
     * ⚠️ **Deletion is the wrong tool and so is a fixed multiplier.** Deleting outliers means that if
     * somebody genuinely gained three kilograms over a holiday, every reading afterwards is an outlier
     * against a filter that refuses to move, and the trend never catches up. The first draft used a
     * fixed twenty-five-fold widening instead, which handles a plausible-but-wrong reading well and
     * fails badly on a real typo: measured against the shipped filter, one 850 kg reading in a
     * sixty-day record still dragged the trend **eight kilograms**, which is about 900 calories a day
     * on the target derived from it.
     *
     * Scaling by the square of how far past the gate the reading fell is continuous (a reading just
     * over the gate is barely touched, so there is no cliff), and it grows fast enough that an absurd
     * one is powerless: the same 850 kg reading now moves the trend by grams. A *run* of odd readings
     * still pulls the trend across within a few days, because each one lowers the prediction the next
     * is judged against.
     */
    fun suspectNoiseFactor(innovation: Double, predictionSd: Double): Double {
        val gate = OUTLIER_GATE_SDS * predictionSd
        if (gate <= 0.0 || !gate.isFinite()) return 1.0
        val ratio = abs(innovation) / gate
        return if (ratio <= 1.0) 1.0 else ratio * ratio
    }

    // ------------------------------------------------------------------------------------- types

    /** A mass unit for display. The core computes in kilograms and converts only at the wording. */
    enum class MassUnit(val label: String, val perKg: Double) {
        KG("kg", 1.0),
        LB("lb", 2.2046226218487757),
    }

    /** One reading off a scale. */
    data class Weighin(val atMs: Long, val kg: Double)

    /** The estimate at one weigh-in, after the whole record has been taken into account. */
    data class Point(
        val atMs: Long,
        /** What the scale said. */
        val observedKg: Double,
        /** What the filter believes the person actually weighed. */
        val trendKg: Double,
        /** Standard deviation on [trendKg]. */
        val trendSdKg: Double,
        /** Signed rate of change in kilograms per day. Negative is losing. */
        val ratePerDayKg: Double,
        /** Standard deviation on [ratePerDayKg]. */
        val rateSdPerDayKg: Double,
        /** True when this reading was far enough from the prediction to be down-weighted. */
        val suspect: Boolean,
    ) {
        val ratePerWeekKg: Double get() = ratePerDayKg * 7.0
        val rateSdPerWeekKg: Double get() = rateSdPerDayKg * 7.0

        /** True when the rate's 95% interval excludes zero — the only case worth stating a direction for. */
        val rateIsClear: Boolean get() = abs(ratePerDayKg) > RATE_CLEAR_SDS * rateSdPerDayKg
    }

    sealed interface Trend {
        /** The smoothed record. [points] is oldest first and [latest] is its last entry. */
        data class Estimated(
            val points: List<Point>,
            val latest: Point,
        ) : Trend {
            /** Enough readings for the rate to mean anything at all, before its interval is consulted. */
            val hasRate: Boolean get() = points.size >= MIN_FOR_RATE
        }

        /** Nothing usable was supplied. [sentence] says so in words the surface can print. */
        data class TooLittle(val have: Int, val sentence: String) : Trend
    }

    // --------------------------------------------------------------------------------- the filter

    /**
     * Estimate the trend from a set of weigh-ins.
     *
     * Order does not matter; non-finite and non-positive readings are dropped (a scale that reports zero
     * is reporting a failure, not a weight). Two readings at the same instant are both used, which is
     * the correct treatment of two independent measurements.
     */
    fun estimate(weighins: List<Weighin>): Trend {
        val obs = weighins
            .filter { it.kg.isFinite() && it.kg > 0.0 }
            .sortedBy { it.atMs }
        if (obs.isEmpty()) {
            return Trend.TooLittle(0, "No weigh-ins yet — the trend starts with the first one.")
        }

        val n = obs.size
        val r = SCALE_NOISE_KG * SCALE_NOISE_KG
        val q = TREND_WANDER_KG_PER_DAY * TREND_WANDER_KG_PER_DAY

        // Forward pass. Everything the smoother needs is kept: the posterior at each step, the prior it
        // came from, and the step length that links them.
        val xF = Array(n) { DoubleArray(2) }        // [weight, rate] posterior
        val pF = Array(n) { DoubleArray(3) }        // [p00, p01, p11] posterior
        val xP = Array(n) { DoubleArray(2) }        // prior, before this step's reading
        val pP = Array(n) { DoubleArray(3) }
        val dt = DoubleArray(n)
        val suspect = BooleanArray(n)

        xF[0][0] = obs[0].kg
        xF[0][1] = 0.0
        pF[0][0] = r
        pF[0][1] = 0.0
        pF[0][2] = INITIAL_RATE_SD_KG_PER_DAY * INITIAL_RATE_SD_KG_PER_DAY
        xP[0] = xF[0].copyOf()
        pP[0] = pF[0].copyOf()

        for (k in 1 until n) {
            val step = ((obs[k].atMs - obs[k - 1].atMs) / MS_PER_DAY).coerceAtLeast(0.0)
            dt[k] = step

            // Predict. F = [[1, dt], [0, 1]]; Q is the continuous white-noise-acceleration form.
            val w = xF[k - 1][0] + xF[k - 1][1] * step
            val v = xF[k - 1][1]
            val a00 = pF[k - 1][0]
            val a01 = pF[k - 1][1]
            val a11 = pF[k - 1][2]
            var m00 = a00 + 2.0 * step * a01 + step * step * a11 + q * step * step * step / 3.0
            var m01 = a01 + step * a11 + q * step * step / 2.0
            var m11 = a11 + q * step
            xP[k][0] = w
            xP[k][1] = v
            pP[k][0] = m00
            pP[k][1] = m01
            pP[k][2] = m11

            // Update. H = [1, 0].
            val innovation = obs[k].kg - w
            val sPlain = m00 + r
            val widen = suspectNoiseFactor(innovation, sqrt(sPlain))
            suspect[k] = widen > 1.0
            val s = m00 + r * widen
            val k0 = m00 / s
            val k1 = m01 / s
            xF[k][0] = w + k0 * innovation
            xF[k][1] = v + k1 * innovation
            val n00 = (1.0 - k0) * m00
            val n01 = (1.0 - k0) * m01
            val n11 = m11 - k1 * m01
            pF[k][0] = n00
            pF[k][1] = n01
            pF[k][2] = n11
            m00 = n00; m01 = n01; m11 = n11
        }

        // Backward pass (Rauch–Tung–Striebel). The last point is already final.
        val xS = Array(n) { xF[it].copyOf() }
        val pS = Array(n) { pF[it].copyOf() }
        for (k in n - 2 downTo 0) {
            val step = dt[k + 1]
            val q00 = pP[k + 1][0]
            val q01 = pP[k + 1][1]
            val q11 = pP[k + 1][2]
            val det = q00 * q11 - q01 * q01
            // A degenerate prior means there is nothing to gain from smoothing this step; the filtered
            // value stands rather than being divided by something indistinguishable from zero.
            if (!det.isFinite() || abs(det) < 1e-12) continue

            // A = Pf * Fᵀ
            val a00 = pF[k][0] + step * pF[k][1]
            val a01 = pF[k][1]
            val a10 = pF[k][1] + step * pF[k][2]
            val a11 = pF[k][2]
            // C = A * Pp⁻¹
            val c00 = (a00 * q11 - a01 * q01) / det
            val c01 = (-a00 * q01 + a01 * q00) / det
            val c10 = (a10 * q11 - a11 * q01) / det
            val c11 = (-a10 * q01 + a11 * q00) / det

            val e0 = xS[k + 1][0] - xP[k + 1][0]
            val e1 = xS[k + 1][1] - xP[k + 1][1]
            xS[k][0] = xF[k][0] + c00 * e0 + c01 * e1
            xS[k][1] = xF[k][1] + c10 * e0 + c11 * e1

            val d00 = pS[k + 1][0] - q00
            val d01 = pS[k + 1][1] - q01
            val d11 = pS[k + 1][2] - q11
            val g00 = c00 * d00 + c01 * d01
            val g01 = c00 * d01 + c01 * d11
            val g10 = c10 * d00 + c11 * d01
            val g11 = c10 * d01 + c11 * d11
            pS[k][0] = pF[k][0] + g00 * c00 + g01 * c01
            pS[k][1] = pF[k][1] + g00 * c10 + g01 * c11
            pS[k][2] = pF[k][2] + g10 * c10 + g11 * c11
        }

        val points = (0 until n).map { k ->
            Point(
                atMs = obs[k].atMs,
                observedKg = obs[k].kg,
                trendKg = xS[k][0],
                trendSdKg = sqrt(pS[k][0].coerceAtLeast(0.0)),
                ratePerDayKg = xS[k][1],
                rateSdPerDayKg = sqrt(pS[k][2].coerceAtLeast(0.0)),
                suspect = suspect[k],
            )
        }
        return Trend.Estimated(points, points.last())
    }

    // ------------------------------------------------------------------------------------ reading

    /**
     * The trend nearest a given instant, or null when the record does not reach there.
     *
     * ⚠️ [Expenditure] measures the difference between two of these, so the tolerance matters: a "start
     * of window" point that is really from ten days earlier makes the window longer than the arithmetic
     * thinks it is and biases the answer. [toleranceDays] is how far off the asked-for instant a point
     * may be and still count.
     */
    fun nearest(trend: Trend.Estimated, atMs: Long, toleranceDays: Double): Point? {
        val tolerance = (toleranceDays * MS_PER_DAY).roundToLong()
        var best: Point? = null
        var bestGap = Long.MAX_VALUE
        for (p in trend.points) {
            val gap = abs(p.atMs - atMs)
            if (gap < bestGap) {
                bestGap = gap
                best = p
            }
        }
        return if (best != null && bestGap <= tolerance) best else null
    }

    /** Days between the oldest and newest weigh-in. */
    fun spanDays(trend: Trend.Estimated): Double =
        (trend.points.last().atMs - trend.points.first().atMs) / MS_PER_DAY

    // ----------------------------------------------------------------------------------- wording

    /**
     * How the rate should be said out loud, in the reader's own unit.
     *
     * ⚠️ Returns the "holding steady" wording rather than a direction whenever the interval spans zero.
     * Printing "down 0.1 kg a week" from four days of data would be a claim the data cannot support, and
     * the reader would act on it.
     */
    fun rateSentence(point: Point, unit: MassUnit, hasRate: Boolean = true): String {
        if (!hasRate) return "One weigh-in so far — a second one starts the trend."
        if (!point.rateIsClear) {
            return "Holding steady — any change so far is inside the noise."
        }
        val perWeek = point.ratePerWeekKg * unit.perKg
        val word = if (perWeek < 0.0) "Down" else "Up"
        val give = point.rateSdPerWeekKg * RATE_CLEAR_SDS * unit.perKg
        return "$word ${fmt(abs(perWeek))} ${unit.label} a week, give or take ${fmt(give)}."
    }

    /** The trend weight itself, in the reader's unit. */
    fun trendSentence(point: Point, unit: MassUnit): String =
        "Trend ${fmt(point.trendKg * unit.perKg)} ${unit.label} — the scale said ${fmt(point.observedKg * unit.perKg)}."

    /** Two decimal places for small numbers, one for the rest. Locale-fixed: these are numbers, not prose. */
    private fun fmt(v: Double): String {
        val a = abs(v)
        return if (a < 1.0) String.format(java.util.Locale.US, "%.2f", v)
        else String.format(java.util.Locale.US, "%.1f", v)
    }
}
