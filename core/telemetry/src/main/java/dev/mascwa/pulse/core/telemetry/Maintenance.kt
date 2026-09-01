package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coming off a deficit, and knowing when the scale can confirm anything.
 *
 * Two questions this app could not answer, both about the gap between changing what you ask for and
 * seeing whether it worked.
 *
 * ## Why there is no reverse diet here
 *
 * The conventional advice for ending a deficit is to add fifty or a hundred calories a week and
 * creep upward for months. It exists because somebody with no measurement has to grope for their
 * maintenance — and this app has one. Your measured expenditure right now *is* your maintenance
 * right now, suppressed and all, so eating at it is the correct answer immediately, and the
 * measurement then follows the number upward on its own as it recovers.
 *
 * ⚠️ **What is NOT claimed: that a large rebound is coming.** Adaptive thermogenesis is real and its
 * magnitude outside severe restriction is modest and argued over. So this does not predict a
 * recovery — it measures one, refuses to when it cannot, and says which.
 */
object Maintenance {

    /**
     * One expenditure estimate, as it stood on a day.
     *
     * ⚠️ Carries its own [windowDays] because that is what decides whether two readings are
     * independent — see [recovery]. A reading that does not remember its window cannot be compared
     * with another honestly.
     */
    data class Reading(val atMs: Long, val kcal: Double, val windowDays: Double)

    private const val MS_PER_DAY: Double = 86_400_000.0

    /**
     * How far apart two readings must be, as a multiple of the measurement window, before their
     * difference means anything.
     *
     * ⚠️ **This is the honesty constraint and the whole reason `recovery` can refuse.** Expenditure
     * is recomputed from a rolling window, so two readings a week apart share three weeks of the
     * same data — most of any difference between them is the window turning over, not the body
     * changing. One full window apart is the first point at which the two answers rest on disjoint
     * evidence. Anything shorter is autocorrelation wearing the costume of a trend.
     */
    const val INDEPENDENT_WINDOWS: Double = 1.0

    /**
     * The smallest change worth calling a change.
     *
     * ⚠️ Roughly half a typical interval on a settled measurement. Below it the difference is inside
     * the noise of both readings, and reporting "your expenditure rose 20 calories" from an estimate
     * whose own give-or-take is a hundred and fifty is a precision claim the data cannot support.
     */
    const val MEANINGFUL_KCAL: Double = 75.0

    sealed interface Recovery {
        /** Two independent readings, and what happened between them. */
        data class Measured(
            val fromKcal: Double,
            val toKcal: Double,
            val spanDays: Double,
            val sentence: String,
        ) : Recovery {
            val deltaKcal: Double get() = toKcal - fromKcal

            /** Whether the change clears [MEANINGFUL_KCAL]. Flat is a real answer, not a failure. */
            val moved: Boolean get() = abs(deltaKcal) >= MEANINGFUL_KCAL
        }

        /** Not yet two readings far enough apart to be about different evidence. */
        data class TooSoon(val haveDays: Double, val needDays: Double, val sentence: String) : Recovery
    }

    /**
     * Has measured expenditure moved since the oldest reading that is genuinely independent of the
     * newest one?
     *
     * ⚠️ Compares the newest reading against the newest one that is at least [INDEPENDENT_WINDOWS]
     * windows older — not against the oldest reading in the list. The oldest describes a person
     * further away and would exaggerate every change; the point is the closest comparison that is
     * still a fair one.
     */
    fun recovery(readings: List<Reading>, nowMs: Long): Recovery {
        val usable = readings.filter { it.kcal.isFinite() && it.kcal > 0.0 && it.atMs <= nowMs }
            .sortedBy { it.atMs }
        val newest = usable.lastOrNull()
        if (newest == null) {
            return Recovery.TooSoon(
                haveDays = 0.0,
                needDays = Expenditure.DEFAULT_WINDOW_DAYS * INDEPENDENT_WINDOWS,
                sentence = "No expenditure readings kept yet, so there is nothing to compare.",
            )
        }
        val needDays = newest.windowDays * INDEPENDENT_WINDOWS
        val cutoff = newest.atMs - (needDays * MS_PER_DAY).toLong()
        val earlier = usable.lastOrNull { it.atMs <= cutoff }
        if (earlier == null) {
            val have = (newest.atMs - usable.first().atMs) / MS_PER_DAY
            return Recovery.TooSoon(
                haveDays = have,
                needDays = needDays,
                sentence = "Two readings this close share most of the same days, so any difference " +
                    "between them is the window turning over rather than you changing. Ask again in " +
                    "about ${(needDays - have).roundToInt().coerceAtLeast(1)} days.",
            )
        }

        val span = (newest.atMs - earlier.atMs) / MS_PER_DAY
        val delta = newest.kcal - earlier.kcal
        val sentence = when {
            abs(delta) < MEANINGFUL_KCAL ->
                "Your measured expenditure is about where it was ${span.roundToInt()} days ago — " +
                    "${Expenditure.round50(newest.kcal)} a day."
            delta > 0 ->
                "Your measured expenditure has risen about ${Expenditure.round50(delta)} calories a " +
                    "day over the last ${span.roundToInt()} days, to ${Expenditure.round50(newest.kcal)}. " +
                    "Your target has been rising with it."
            else ->
                "Your measured expenditure has fallen about ${Expenditure.round50(abs(delta))} " +
                    "calories a day over the last ${span.roundToInt()} days, to " +
                    "${Expenditure.round50(newest.kcal)}. Your target has followed it down."
        }
        return Recovery.Measured(earlier.kcal, newest.kcal, span, sentence)
    }

    // ------------------------------------------------------------------------------- the step up

    /** What ending a deficit does to today's number. */
    data class StepUp(val fromKcal: Int, val toKcal: Int, val sentence: String) {
        val deltaKcal: Int get() = toKcal - fromKcal
    }

    /**
     * Eat at what has been measured, straight away.
     *
     * ⚠️ No ramp, and the omission is the point. A ramp exists to find a maintenance figure nobody
     * has measured; this app has measured one. Going to it now means the estimate starts tracking a
     * body that is no longer in a deficit immediately, rather than in three months' time.
     */
    fun stepUp(currentTargetKcal: Int, measuredKcal: Double): StepUp {
        val to = if (measuredKcal.isFinite()) measuredKcal.roundToInt() else currentTargetKcal
        val delta = to - currentTargetKcal
        val sentence = when {
            delta > 0 ->
                "Eating at what has actually been measured means $to a day — $delta more than now. " +
                    "No slow ramp: the measurement already knows what you are burning, including " +
                    "however much a long deficit has taken off it, and it will follow that figure up " +
                    "on its own as it recovers."
            delta < 0 ->
                "What has been measured is $to a day, which is ${-delta} LESS than your current " +
                    "target — so the target has been running ahead of the measurement rather than a " +
                    "deficit needing to be ended."
            else -> "You are already eating at what has been measured — $to a day."
        }
        return StepUp(currentTargetKcal, to, sentence)
    }

    // ------------------------------------------------------------- when the scale can confirm it

    /**
     * How many standard deviations of trend movement count as having seen something.
     *
     * ⚠️ Two, which is the ordinary bar and deliberately not one. At one sigma a settled weight would
     * "confirm" a change roughly a third of the time by chance, and a confirmation that fires on
     * noise is worse than none — it would tell somebody a rate is working when nothing has happened.
     */
    const val CONFIRM_SIGMA: Double = 2.0

    /** Past this, the change is too slow to separate from noise in any useful time. */
    const val CONFIRM_MAX_DAYS: Double = 120.0

    sealed interface Confirmation {
        /** The trend should be able to show it, in about this long. */
        data class InDays(val days: Int, val sentence: String) : Confirmation

        /**
         * Nothing will confirm it, and why.
         *
         * ⚠️ A real and common case rather than an error: asking to maintain means asking for no
         * change, and no amount of waiting distinguishes no change from noise. Saying so is more
         * useful than a countdown that never ends.
         */
        data class Never(val sentence: String) : Confirmation
    }

    /**
     * When the weight trend will be able to tell whether a newly-set rate is happening.
     *
     * The trend carries its own give-or-take in kilograms; a rate is kilograms per day. So the wait
     * is however long it takes the expected movement to clear [CONFIRM_SIGMA] times that interval.
     *
     * ⚠️ **This is a prediction and the sentence says so.** It describes when a measurement will
     * become possible, not what the measurement will find — the app has no idea yet whether the rate
     * is being achieved, and a screen that implied otherwise would be inventing a result.
     */
    fun confirmIn(ratePerWeekKg: Double, trendSdKg: Double): Confirmation {
        val perDay = abs(ratePerWeekKg) / 7.0
        if (!ratePerWeekKg.isFinite() || perDay <= 0.0) {
            return Confirmation.Never(
                "You are aiming to hold steady, so there is no change for the scale to confirm — " +
                    "what it can show is the trend staying flat, which it already does.",
            )
        }
        if (!trendSdKg.isFinite() || trendSdKg < 0.0) {
            return Confirmation.Never(
                "Not enough weigh-ins yet for the trend to have a margin, so there is nothing to " +
                    "measure a change against.",
            )
        }
        val days = CONFIRM_SIGMA * trendSdKg / perDay
        if (days > CONFIRM_MAX_DAYS) {
            return Confirmation.Never(
                "At this pace the change is slower than the scale's own noise, so it will not show " +
                    "up as a clear signal — the log and the measured expenditure are the better " +
                    "evidence that it is working.",
            )
        }
        val whole = days.roundToInt().coerceAtLeast(1)
        return Confirmation.InDays(
            whole,
            "Keep weighing in and the trend should show this in about $whole days. That is when it " +
                "becomes measurable rather than a prediction — it is not a forecast of what it will say.",
        )
    }
}
