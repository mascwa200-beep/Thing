package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * When a new set of targets is handed down, and what changed since the last one.
 *
 * ⚠️ **This exists because the targets were recomputed on every state build.** [MacroTargets.plan]
 * is a pure function of the live expenditure estimate, and that estimate moves with every weigh-in
 * and every logged meal — so the number somebody was eating to changed underneath them, several
 * times a day, with nothing on screen to say it had. That is the opposite of a plan. You cannot shop
 * for the week against a figure that will be different by Thursday, and a target that drifts silently
 * is one nobody can tell they have missed.
 *
 * The remedy is the one the whole measured-expenditure model was built around: **the measurement runs
 * continuously and the targets are published on a cadence.** Between check-ins the published set is
 * what the screens show and what the day is judged against; at a check-in the planner's current
 * answer is taken whole, and the difference is stated in words.
 *
 * ⚠️ **The rule that is easy to get backwards: a new weigh-in must NOT trigger a republish.**
 * Absorbing measured drift on a cadence is the entire purpose. But a change the person MADE — a new
 * goal rate, a different diet mode, a protein override, a corrected height — is an instruction, not
 * drift, and holding a stale target against it would read as the app ignoring them. So [verdict]
 * separates the two: [Stated] is the half a person chooses and any change to it publishes at once;
 * everything else waits for the cadence.
 *
 * ⚠️ A consequence worth stating rather than discovering: the protein floor is grams per kilogram of
 * body mass, so between check-ins it rests on the mass at the last check-in. That is correct — it is
 * the same reason the calorie figure holds — but it means a week of real weight change shows up in
 * the protein number all at once, at the check-in, rather than a gram at a time.
 *
 * ⚠️ Nothing here reads a clock, takes a locale or formats a date. `now` is passed in, every
 * sentence is built from numbers already in hand, and that is what lets CI hold the whole file.
 */
object CheckIn {

    /** How long a published set of targets stands before the next check-in is due. */
    const val INTERVAL_DAYS = 7

    /**
     * A change smaller than this in the daily calorie figure is not worth publishing as news.
     *
     * ⚠️ It does not suppress the publish — the cadence does that — it suppresses the SENTENCE. A
     * check-in that reports "calories are 3 kcal higher" trains somebody to stop reading check-ins,
     * and 3 kcal is well inside the interval the estimate itself carries.
     */
    const val KCAL_WORTH_SAYING = 25

    /** Same idea for the macros, in grams. */
    const val GRAMS_WORTH_SAYING = 3

    private const val DAY_MS = 86_400_000L

    /**
     * The half of a plan a person CHOOSES, as opposed to the half the app measures.
     *
     * ⚠️ Every field here is something somebody typed or picked. None of them is derived from a
     * weigh-in or from the food log — that separation is the whole mechanism, so a field added here
     * by mistake would make the targets republish on measured drift and undo the feature.
     *
     * Body mass is deliberately absent for exactly that reason, even though [MacroTargets] takes it:
     * it is measured, not stated.
     */
    data class Stated(
        val heightCm: Double = 0.0,
        val birthYear: Int = 0,
        val sex: String = "",
        val goalKg: Double = 0.0,
        val ratePerWeekKg: Double = 0.0,
        val dietMode: String = "",
        val proteinGPerKg: Double = 0.0,
        val bodyFatPct: Double = 0.0,
        val athlete: Boolean = false,
        val programMode: String = "",
    ) {
        /**
         * A stable string that changes if and only if one of these values does.
         *
         * ⚠️ **Exists so the published snapshot costs ONE persisted field instead of ten.** The
         * comparison [verdict] makes is "is what you chose still what you chose", and storing a
         * whole second copy of the settings to answer it is ten more fields to keep in step with
         * this type — the kind of parallel list that drifts silently.
         *
         * ⚠️ Every part is written with Kotlin's own number formatting, which is locale-independent,
         * so a phone set to a comma decimal produces the same string as one set to a point. A
         * locale-sensitive format here would republish the targets on a language change.
         *
         * ⚠️ **Adding a field to [Stated] changes every fingerprint already on disk**, so everybody
         * gets one extra check-in the first time they open the new build. That is the correct
         * behaviour — the app is now considering an input it was not — and it is worth knowing
         * rather than discovering.
         */
        fun fingerprint(): String = listOf(
            heightCm.toString(),
            birthYear.toString(),
            sex,
            goalKg.toString(),
            ratePerWeekKg.toString(),
            dietMode,
            proteinGPerKg.toString(),
            bodyFatPct.toString(),
            athlete.toString(),
            programMode,
        ).joinToString("\u001F")
    }

    /**
     * A set of targets that was handed down, and the situation it rested on.
     *
     * [expenditureKcal] and [weightKg] are kept not to be compared against but to be REPORTED: at the
     * next check-in they are what makes "your measured expenditure came out 90 higher" a statement
     * about this person's own last week rather than an assertion with nothing behind it.
     */
    data class Published(
        val atMs: Long,
        val targets: MacroTargets.Targets,
        /** [Stated.fingerprint] as it stood when this was handed down. */
        val statedFingerprint: String,
        val expenditureKcal: Double,
        val weightKg: Double,
        /**
         * What the published calories will actually do, as the planner computed it at the time.
         *
         * ⚠️ Held rather than recomputed against today's expenditure, because it describes THESE
         * numbers. A rate worked out from held targets and a moved expenditure is a different
         * statement, and putting it beside targets from last week would be two figures from two
         * moments rendered as one plan.
         */
        val effectiveRatePerWeekKg: Double = 0.0,
        /**
         * The limits that bit when these targets were made — "your calories were raised to the
         * floor" and the like.
         *
         * ⚠️ Held for the same reason: they explain the numbers ON SCREEN. Showing today's freshly
         * computed adjustments beside held targets would describe a set of numbers nobody can see.
         */
        val adjustments: List<MacroTargets.Adjustment> = emptyList(),
    )

    /** What to do right now. */
    sealed interface Verdict {
        /** Take the planner's current answer and publish it. [why] is for the screen, not a log. */
        data class Publish(val why: Reason) : Verdict

        /** Keep showing what was published. [daysUntilDue] counts whole days, floored at zero. */
        data class Hold(val daysUntilDue: Int) : Verdict
    }

    /** Why a publish is happening, so the surface can say something true about it. */
    enum class Reason(val sentence: String) {
        /** First run, or the targets were cleared. Nothing to compare against. */
        FIRST("Your first set of targets."),

        /** The week elapsed. This is the ordinary one. */
        DUE("Your weekly check-in — a new week of measurements is in."),

        /**
         * Something the person chose has changed. ⚠️ Published at once rather than at the next
         * check-in: a target that ignored a goal you just changed would read as the app not
         * listening, and unlike measured drift there is nothing to average out.
         */
        CHANGED("You changed your plan, so the targets are worked out again now."),
    }

    /**
     * Whether to publish now, given what was last published.
     *
     * ⚠️ Order matters and is not arbitrary. Nothing published outranks everything; a changed
     * instruction outranks the cadence, because waiting six days to honour it is the failure mode
     * that matters; the cadence is last.
     */
    fun verdict(published: Published?, stated: Stated, nowMs: Long): Verdict {
        if (published == null) return Verdict.Publish(Reason.FIRST)
        if (published.statedFingerprint != stated.fingerprint()) return Verdict.Publish(Reason.CHANGED)
        val elapsed = nowMs - published.atMs
        // ⚠️ A published time in the future — a clock moved back, a restored backup — is treated as
        // due rather than as a very long hold. The alternative is targets frozen until the clock
        // catches up, which could be months and would look exactly like the app being broken.
        if (elapsed < 0L) return Verdict.Publish(Reason.DUE)
        val days = elapsed / DAY_MS
        if (days >= INTERVAL_DAYS) return Verdict.Publish(Reason.DUE)
        return Verdict.Hold((INTERVAL_DAYS - days).toInt().coerceAtLeast(0))
    }

    /**
     * What moved between two sets of targets, in plain sentences, or empty when nothing did.
     *
     * ⚠️ **No praise and no scolding, in either direction**, which is the register the rest of this
     * feature already uses: the numbers moved because the measurement moved, and a person who ate
     * more last week has not done anything wrong. "Calories are 90 higher" is a fact; "you can eat
     * more" is a reward and "your deficit shrank" is a reprimand.
     *
     * ⚠️ Changes under [KCAL_WORTH_SAYING] and [GRAMS_WORTH_SAYING] produce no sentence. Reporting a
     * three-calorie move as news is how a weekly summary becomes something nobody opens.
     */
    fun changes(before: MacroTargets.Targets, after: MacroTargets.Targets): List<String> {
        val out = mutableListOf<String>()
        moved("Calories", before.kcal, after.kcal, "kcal", KCAL_WORTH_SAYING)?.let { out += it }
        moved("Protein", before.proteinG, after.proteinG, "g", GRAMS_WORTH_SAYING)?.let { out += it }
        moved("Fat", before.fatG, after.fatG, "g", GRAMS_WORTH_SAYING)?.let { out += it }
        moved("Carbohydrate", before.carbG, after.carbG, "g", GRAMS_WORTH_SAYING)?.let { out += it }
        return out
    }

    private fun moved(label: String, before: Int, after: Int, unit: String, floor: Int): String? {
        val delta = after - before
        if (abs(delta) < floor) return null
        val direction = if (delta > 0) "up" else "down"
        return "$label $direction ${abs(delta)} $unit, to $after."
    }

    /**
     * Why the calorie figure moved, when the reason is one the app can honestly state.
     *
     * ⚠️ Null rather than a guess. The only cause this can point at is the measured expenditure,
     * because it is the only input that moves on its own — and even then the sentence says the
     * MEASUREMENT changed, never that the person's metabolism did. The estimate is a blend of a
     * formula and an energy balance over a month of logging; "you burned 90 more calories a day" is
     * a claim about a body, and this has only ever measured a ledger.
     */
    fun whyCaloriesMoved(before: Published, afterExpenditureKcal: Double): String? {
        if (!before.expenditureKcal.isFinite() || !afterExpenditureKcal.isFinite()) return null
        val delta = (afterExpenditureKcal - before.expenditureKcal).roundToInt()
        if (abs(delta) < KCAL_WORTH_SAYING) return null
        val direction = if (delta > 0) "higher" else "lower"
        return "Your measured expenditure came out ${abs(delta)} kcal a day $direction than it did " +
            "at the last check-in."
    }

    /**
     * How the weight moved over the period the published set covered, or null when it cannot be said.
     *
     * ⚠️ Uses the TREND weight both times, never a single morning's reading — a check-in that
     * reported the difference between two arbitrary weigh-ins would be reporting mostly water. The
     * caller passes the trend figure; this only phrases it.
     */
    fun weightMoved(before: Published, nowKg: Double, unit: BodyTrend.MassUnit): String? {
        if (before.weightKg <= 0.0 || !nowKg.isFinite() || nowKg <= 0.0) return null
        val delta = (nowKg - before.weightKg) * unit.perKg
        // A tenth of a unit is below what a trend can resolve over a week, and rendering "0.0 up"
        // is worse than saying nothing.
        if (abs(delta) < 0.1) return "Your trend weight has held steady since the last check-in."
        val direction = if (delta > 0) "up" else "down"
        return "Your trend weight is $direction ${fmt(abs(delta))} ${unit.label} since the last check-in."
    }

    private fun fmt(v: Double): String {
        val tenths = (v * 10.0).roundToInt()
        return "${tenths / 10}.${abs(tenths % 10)}"
    }
}
