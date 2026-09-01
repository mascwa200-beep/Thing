package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * When you get there, if you carry on exactly as you are.
 *
 * A goal weight is set in this app and nothing has ever answered the obvious question about it. The
 * arithmetic is one division — distance over rate — and every interesting thing here is about the
 * cases where that division must not be performed.
 *
 * ## The refusal is the feature
 *
 * ⚠️ **A rate whose interval spans zero cannot be projected at all, and that is arithmetic rather
 * than caution.** Days-to-goal is distance divided by rate, so its uncertainty is the distribution
 * of one over a normal variable — and when that variable's interval straddles zero the reciprocal is
 * unbounded in both directions, with no mean and no useful quantiles. There is no honest interval to
 * quote, at any confidence. [BodyTrend.Point.rateIsClear] is exactly the test for it, and it is the
 * same test [BodyTrend.rateSentence] already uses before it will name a direction, so the two cannot
 * disagree about whether the scale is saying anything.
 *
 * Once the rate is clear, both ends of its interval share a sign, the reciprocal is well behaved,
 * and the projection is a genuine range.
 *
 * ## And it is a projection, said out loud
 *
 * ⚠️ Every sentence here opens by naming its assumption. This is not a forecast of the future — it
 * carries no knowledge of a holiday, an illness, a training block or adaptive thermogenesis. It is
 * the single statement "the last few weeks, continued". A reader who takes it for a prediction will
 * be wrong on a normal life, so the wording never lets them.
 *
 * ⚠️ **Deliberately no adaptation curve.** [Maintenance] already records this project's position and
 * its reasoning: adaptive thermogenesis is real, its magnitude outside severe restriction is modest
 * and argued over, so this app measures a change rather than predicting one. Bending the projection
 * by an invented coefficient would put a fabricated number in the one part of this app that tells a
 * real person how much to eat.
 */
object GoalProjection {

    private const val MS_PER_DAY: Double = 86_400_000.0

    /**
     * Beyond this the projection stops quoting a far end.
     *
     * ⚠️ Not a display preference. Two years at a modest half a kilogram a week is fifty-two
     * kilograms, far past any single goal anybody sets — so this only ever bites when the rate is so
     * slow that the arrival date is dominated by whatever happens in between. "You arrive in 2036" is
     * arithmetically correct and worthless, and quoting it invites somebody to act on it.
     */
    const val MAX_HORIZON_DAYS: Double = 730.0

    /**
     * The two-sided coverage each end of the interval is taken at.
     *
     * ⚠️ Shared with [BodyTrend.RATE_CLEAR_SDS] on purpose. The interval is only well defined
     * *because* the rate cleared zero at this width, so widening the projection past the test that
     * licensed it would quote a range including rates the trend has already rejected.
     */
    const val INTERVAL_SDS: Double = BodyTrend.RATE_CLEAR_SDS

    /** What can be said about reaching a goal. */
    sealed interface Projection {

        /**
         * The whole answer in one line, whichever case this is.
         *
         * ⚠️ Declared on the interface rather than left to each variant, so a surface that only wants
         * to print something never has to write a `when` — and cannot forget a branch when a case is
         * added later. The structured fields are there for a surface that wants to draw the range.
         */
        val sentence: String

        /**
         * The goal is already met, as near as the scale can resolve.
         *
         * ⚠️ A distinct answer rather than "0 days", because the two mean different things: this says
         * the remaining distance is inside the trend's own uncertainty, so there is nothing left to
         * project. Quoting "3 days" from a gap of 200 grams would be precision the filter does not have.
         */
        data class Arrived(
            val trendKg: Double,
            val goalKg: Double,
            override val sentence: String,
        ) : Projection

        /** The rate is clear and it points the other way. */
        data class MovingAway(
            val distanceKg: Double,
            val ratePerWeekKg: Double,
            override val sentence: String,
        ) : Projection

        /**
         * A real range of arrival times.
         *
         * [latestDays] is **null** when the slow end of the rate interval puts arrival past
         * [MAX_HORIZON_DAYS] — the soonest is known, the latest is not worth naming.
         */
        data class Projected(
            /** Signed: negative when the goal is below where you are now. */
            val distanceKg: Double,
            val soonestDays: Double,
            val likelyDays: Double,
            val latestDays: Double?,
            override val sentence: String,
        ) : Projection {
            val openEnded: Boolean get() = latestDays == null
        }

        /** Nothing can be said, and [sentence] says which of the reasons it is. */
        data class NotYet(override val sentence: String) : Projection
    }

    /**
     * Project arrival at [goalKg] from the newest trend point.
     *
     * @param hasRate [BodyTrend.Trend.Estimated.hasRate] — whether there are enough weigh-ins for a
     *   rate to mean anything at all, which is a separate question from whether it clears zero.
     */
    fun project(
        point: BodyTrend.Point,
        hasRate: Boolean,
        goalKg: Double,
        unit: BodyTrend.MassUnit = BodyTrend.MassUnit.KG,
    ): Projection {
        if (!goalKg.isFinite() || goalKg <= 0.0) {
            return Projection.NotYet("No goal weight set, so there is nothing to count down to.")
        }
        if (!point.trendKg.isFinite() || point.trendKg <= 0.0) {
            return Projection.NotYet("No trend weight yet — a couple of weigh-ins start it.")
        }
        if (!hasRate) {
            return Projection.NotYet("One weigh-in so far. A second one starts the trend, and the count-down with it.")
        }

        // Signed distance, negative when the goal is below where the trend has you.
        val distance = goalKg - point.trendKg
        val reach = INTERVAL_SDS * point.trendSdKg
        if (abs(distance) <= reach) {
            return Projection.Arrived(
                trendKg = point.trendKg,
                goalKg = goalKg,
                sentence = "You are at your goal, as near as the scale can tell — the gap left is " +
                    "inside what the trend can resolve.",
            )
        }

        if (!point.rateIsClear) {
            // ⚠️ The one case worth spelling out, because the reader will otherwise read a missing
            // date as a bug rather than as the honest answer. See the class note: the reciprocal of a
            // rate whose interval spans zero has no bounded quantiles, so there is no range to shrink
            // this to — it is not that the answer is vague, it is that there is no answer.
            return Projection.NotYet(
                "Your weight is holding steady within the noise, so there is no rate to count down " +
                    "with. A week or two more of weigh-ins will settle it either way.",
            )
        }

        val ratePerDay = point.ratePerDayKg
        val towardGoal = (distance > 0.0) == (ratePerDay > 0.0)
        if (!towardGoal) {
            return Projection.MovingAway(
                distanceKg = distance,
                ratePerWeekKg = point.ratePerWeekKg,
                sentence = "Carrying on as you are moves you away from your goal, not toward it — " +
                    "${direction(point.ratePerWeekKg, unit)} against a goal " +
                    "${fmt(abs(distance) * unit.perKg)} ${unit.label} the other way.",
            )
        }

        val gap = abs(distance)
        val speed = abs(ratePerDay)
        val rateSd = point.rateSdPerDayKg
        val gapSd = point.trendSdKg

        // ⚠️ The conservative combination, deliberately: the fastest arrival pairs the shortest
        // credible distance with the highest credible speed, and the slowest pairs the longest with
        // the lowest. Treating both bounds as simultaneous makes the joint coverage wider than the
        // nominal 95%, which is the right direction to err — a range that is too narrow is the
        // failure that gets acted on.
        val fastSpeed = speed + INTERVAL_SDS * rateSd
        val slowSpeed = speed - INTERVAL_SDS * rateSd
        val nearGap = (gap - INTERVAL_SDS * gapSd).coerceAtLeast(0.0)
        val farGap = gap + INTERVAL_SDS * gapSd

        val soonest = if (fastSpeed > 0.0) nearGap / fastSpeed else 0.0
        val likely = gap / speed
        // `rateIsClear` guarantees speed > INTERVAL_SDS * rateSd, so slowSpeed is strictly positive
        // whenever we reach here — but the guard costs nothing and a future caller might not know that.
        val latestRaw = if (slowSpeed > 0.0) farGap / slowSpeed else Double.POSITIVE_INFINITY
        val latest = if (latestRaw.isFinite() && latestRaw <= MAX_HORIZON_DAYS) latestRaw else null

        return Projection.Projected(
            distanceKg = distance,
            soonestDays = soonest,
            likelyDays = likely,
            latestDays = latest,
            sentence = projectedSentence(gap, soonest, likely, latest, unit),
        )
    }

    // ------------------------------------------------------------------------------------ wording

    private fun projectedSentence(
        gapKg: Double,
        soonest: Double,
        likely: Double,
        latest: Double?,
        unit: BodyTrend.MassUnit,
    ): String {
        val gap = "${fmt(gapKg * unit.perKg)} ${unit.label}"
        // ⚠️ Opens on the assumption, every time. This is the whole of what makes it visibly a
        // projection rather than a forecast, and it is the reason the phrase is not a suffix.
        val lead = "If the last few weeks carry on, $gap to go —"
        return when {
            latest != null ->
                "$lead somewhere between ${span(soonest)} and ${span(latest)}, most likely ${span(likely)}."

            // ⚠️ **The middle estimate is capped as well as the far end, and leaving it uncapped was
            // a real defect caught by working the arithmetic before writing the assertion.** A gap of
            // five kilograms at two grams a day gives a likely arrival of 2,500 days, which `span`
            // renders as "82 months" — precisely the "you arrive in 2036" that [MAX_HORIZON_DAYS]
            // exists to keep off the screen. Past the horizon the only honest thing left to say is
            // that there is nothing worth saying.
            likely > MAX_HORIZON_DAYS ->
                "$lead but at the rate you are actually moving that is further off than this is worth " +
                    "putting a date on."

            else ->
                "$lead about ${span(likely)}, though at the slow end of your current rate it could be a " +
                    "good deal longer than this is worth guessing at."
        }
    }

    /**
     * A duration in the unit somebody would actually use for it.
     *
     * ⚠️ Days below a fortnight, weeks below a season, months beyond. Quoting "163 days" is precise
     * and unreadable, and quoting "5 months" for nine days is neither.
     *
     * ⚠️ **There is no singular "a week" or "a month" here, and their absence is deliberate rather
     * than an oversight.** The boundaries make them unreachable: the weeks branch begins at fourteen
     * days, which is already two weeks, and the months branch at a hundred, which is already three.
     * A first draft carried both and neither could ever have fired — the dead-branch defect this
     * project keeps correcting, caught here by working out which inputs could reach them. If a
     * boundary is ever lowered, `spanNeverSaysOne` fails and asks for the singular back.
     */
    fun span(days: Double): String {
        if (!days.isFinite() || days < 0.0) return "an unknown time"
        if (days.roundToInt() <= 1) return "a day"
        if (days < 14.0) return "${days.roundToInt()} days"
        if (days < 100.0) return "${(days / 7.0).roundToInt()} weeks"
        // 30.44 days: the mean calendar month, so "3 months" does not drift a week per quarter.
        return "${(days / 30.44).roundToInt()} months"
    }

    private fun direction(perWeekKg: Double, unit: BodyTrend.MassUnit): String {
        val v = perWeekKg * unit.perKg
        val word = if (v < 0.0) "down" else "up"
        return "$word ${fmt(abs(v))} ${unit.label} a week"
    }

    /** The instant a projected day count lands on, for a surface that would rather show a date. */
    fun atMs(nowMs: Long, days: Double): Long = nowMs + (days * MS_PER_DAY).toLong()

    /** Two decimals below one, one above. Locale-fixed: these are numbers, not prose. */
    private fun fmt(v: Double): String {
        val a = abs(v)
        return if (a < 1.0) String.format(java.util.Locale.US, "%.2f", v)
        else String.format(java.util.Locale.US, "%.1f", v)
    }
}
