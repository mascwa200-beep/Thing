package dev.mascwa.pulse.core.telemetry

/**
 * The daily things worth keeping up, and the walking that feeds what you burn.
 *
 * ## Observed, never ticked
 *
 * ⚠️ **No habit here is a checkbox.** Every one is derived from a record the app already keeps — a
 * day with food logged, a day with a weigh-in, a day the protein target was met — so a streak cannot
 * be kept by tapping. That is not strictness for its own sake: [Expenditure] measures what you burn
 * from the calorie log, so "how consistently am I logging" is a statement about how much the number
 * on COACH can be trusted, and a self-reported version of it would be a comfortable lie about the
 * one figure this feature exists to produce.
 *
 * The day sets come from the caller, which is the same split every core here uses — the app layer
 * knows the zone and owns the stores, and this file does the arithmetic.
 */
object Habits {

    /**
     * A habit, and what keeping it actually buys.
     *
     * The blurbs say the consequence rather than encouraging: this tab tells a real person how much
     * to eat, and praise for a streak is the wrong register when the streak's whole purpose is to say
     * how much the arithmetic can be relied on.
     */
    enum class Habit(val label: String, val blurb: String) {
        LOG_EVERY_DAY(
            "Log every day",
            "The measured expenditure is taken from the calorie log. Gaps make it a guess.",
        ),
        WEIGH_IN(
            "Step on the scale",
            "The trend needs readings. Daily is best; the filter is built for the noise.",
        ),
        HIT_PROTEIN(
            "Reach the protein target",
            "The one macro worth defending when calories are down.",
        ),
        STAY_IN_BAND(
            "Land inside the calorie band",
            "Within a tenth of target. Nobody hits the number exactly.",
        ),
    }

    /**
     * A run of days.
     *
     * [doneToday] is separate from [current] on purpose. A streak that stands because yesterday
     * counted, with today still open, is a different thing from one already secured today — and the
     * surface should be able to say which without recomputing the rule.
     */
    data class Streak(
        val current: Int,
        val longest: Int,
        val lastMs: Long?,
        val doneToday: Boolean,
    )

    /**
     * The current and longest run in a set of day-starts.
     *
     * ⚠️ **A run ending YESTERDAY is still current.** Today is not over: a person who logs dinner at
     * eight has an empty "today" at four in the afternoon, and breaking their streak at four would
     * make the measure punish the clock rather than the behaviour. The run is broken only once a
     * whole day has passed with nothing in it. This is the same rule [StudyProgress] uses for its
     * streak, deliberately — two streak counters in one app that disagree about midnight would be
     * worse than either.
     *
     * @param dayBefore the day-start before a given one, from the caller's calendar.
     *
     * ⚠️ **Consecutive is what the calendar says, not a fixed 86,400,000 apart.** These keys are local
     * day starts, and a local day is 23 hours the morning the clocks go forward and 25 the morning
     * they go back, so consecutive days across a transition are NEVER exactly a day apart. Comparing
     * the difference against a constant therefore splits every run that spans one — and worse, the
     * "ended yesterday" test fails outright on the day after, so somebody who logged yesterday and
     * has not yet logged today sees a long streak read **zero**. Twice a year, on the one measure in
     * this tab whose entire subject is consistency.
     *
     * [StudyProgress.streak] does not have this problem because it counts integer day indices, where
     * consecutive really does mean one apart. This module is clock-free and zone-free, so the calendar
     * comes from the caller — with no default, because a default of `it - DAY_MS` would be exactly the
     * bug, silently, for anyone who forgot to pass one.
     */
    fun streak(days: Set<Long>, todayStartMs: Long, dayBefore: (Long) -> Long): Streak {
        if (days.isEmpty()) return Streak(0, 0, null, false)
        val sorted = days.sorted()
        val last = sorted.last()

        var longest = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i - 1] == dayBefore(sorted[i])) run + 1 else 1
            if (run > longest) longest = run
        }

        // The current run is only the trailing one, and only if it reaches today or yesterday.
        val current = if (last != todayStartMs && last != dayBefore(todayStartMs)) {
            0
        } else {
            var n = 1
            var i = sorted.size - 1
            while (i > 0 && sorted[i - 1] == dayBefore(sorted[i])) {
                n++
                i--
            }
            n
        }
        return Streak(current, longest, last, doneToday = days.contains(todayStartMs))
    }

    /**
     * The streak said plainly, or null when there is nothing to say.
     *
     * ⚠️ Silent at zero rather than announcing a broken streak. "0 days" on four habits at once is
     * a wall of failure on the screen somebody opened to work out what to have for lunch, and it
     * tells them nothing they did not know.
     */
    fun summary(s: Streak): String? = when {
        s.current <= 0 -> null
        s.current == 1 && s.doneToday -> "Today."
        s.current == 1 -> "Yesterday."
        s.doneToday -> "${s.current} days, including today."
        else -> "${s.current} days, up to yesterday."
    }

    // ------------------------------------------------------------------------------------ steps

    /**
     * What the pedometer said, and what that means for today.
     *
     * ⚠️ **`TYPE_STEP_COUNTER` is cumulative since the device last booted, not since midnight.** So
     * today's count is the current reading minus whatever it read at the start of the day, and that
     * baseline has to be carried. Reading the raw figure as a daily total is the obvious mistake and
     * it produces a number that only grows — plausibly, for weeks, until somebody notices they have
     * apparently walked four hundred thousand steps today.
     */
    data class Steps(
        /** What the counter read when this day began, as far as we know. */
        val baseline: Long,
        val dayStartMs: Long,
        val today: Long,
        /**
         * Whether the device restarted since the last reading, which loses that day's earlier steps.
         *
         * ⚠️ Surfaced rather than hidden. The steps taken before a reboot are genuinely gone — the
         * counter starts again from zero and nothing recorded the old total — so the honest thing is
         * an incomplete count that says it is incomplete, not a total quietly missing a morning.
         */
        val partial: Boolean,
    )

    /**
     * Fold a new raw reading into the running day.
     *
     * @param previous what was carried from the last reading, or null on the first ever.
     * @param raw the sensor's cumulative-since-boot figure.
     */
    fun steps(previous: Steps?, raw: Long, todayStartMs: Long): Steps {
        if (raw < 0) return previous ?: Steps(0, todayStartMs, 0, partial = false)

        // A new day: today starts from wherever the counter is now.
        if (previous == null || previous.dayStartMs != todayStartMs) {
            return Steps(baseline = raw, dayStartMs = todayStartMs, today = 0, partial = false)
        }

        // ⚠️ A reading BELOW the baseline can only mean the counter restarted, which means the
        // device did. Re-baseline to zero rather than to `raw`: the steps since the reboot are real
        // and countable, and only the ones before it are lost.
        if (raw < previous.baseline) {
            return Steps(baseline = 0, dayStartMs = todayStartMs, today = raw, partial = true)
        }

        return previous.copy(today = raw - previous.baseline)
    }

    /** A day's walking, said plainly. Null below a floor, because a handful of steps is noise. */
    fun describe(s: Steps?): String? {
        if (s == null || s.today < MIN_WORTH_SAYING) return null
        val n = s.today
        return if (s.partial) "$n steps since the phone restarted" else "$n steps today"
    }

    /** Below this the count is somebody walking to the kettle, and saying it is clutter. */
    const val MIN_WORTH_SAYING = 100L

    /**
     * Why there is no step count, when there is none.
     *
     * ⚠️ **Three situations, and both applications used to answer all of them with one sentence
     * about the hardware.** "The pedometer is not reporting" is a claim about the phone: it is false
     * when the permission was refused, and false again in the ordinary first seconds before an
     * on-change sensor has said anything. They call for different actions — grant it, wait, or
     * nothing at all — so telling somebody the wrong one points them nowhere.
     *
     * ⚠️ **The sentences live here rather than in either screen**, for the reason [describe] does:
     * one vocabulary about steps, read by two applications with different chrome. Two copies of the
     * same explanation drift, and the copy that drifts is always the one nobody is reading.
     */
    enum class StepSilence {
        /** Allowed, a counter exists, and it has not reported yet. It reports when you move. */
        WAITING,

        /** Refused. The only one of the three with something to be done about it. */
        NO_PERMISSION,

        /** Allowed, and this phone has no pedometer at all. */
        NO_SENSOR,
    }

    /**
     * What to say when [describe] returns null and there is no count at all.
     *
     * ⚠️ Deliberately NOT a fallback inside [describe]. That function answers "what does this count
     * say", and a count of zero — somebody who genuinely has not moved — is a different answer from
     * no count at all. Folding them together is what produced the one-sentence-for-three-situations
     * defect in the first place.
     */
    fun explain(silence: StepSilence): String = when (silence) {
        StepSilence.WAITING -> "Waiting for the first reading — the counter reports when you move."
        StepSilence.NO_PERMISSION ->
            "No step count — this app has not been allowed to read the pedometer."
        StepSilence.NO_SENSOR ->
            "No step count — this phone has no pedometer. Everything else here works without one."
    }
}
