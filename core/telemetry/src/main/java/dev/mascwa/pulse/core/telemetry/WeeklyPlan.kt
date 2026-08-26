package dev.mascwa.pulse.core.telemetry

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A week's worth of eating, rather than a number for today.
 *
 * [MacroTargets] answers one question — what should today be — and answers it well. It cannot answer
 * the one people actually live by: **a week is not seven identical days.** Somebody who trains on
 * Tuesday and Thursday and does nothing at the weekend is being told the same figure on all seven,
 * which is either too little on the days that matter or too much on the days that do not. The
 * arithmetic that fixes it is not complicated; what it needs is somewhere to live where the guard
 * rails cannot be lost.
 *
 * ## The three ways somebody can be in charge
 *
 * The distinction is **who owns the total**, and each mode is honest about it:
 *
 * - [Mode.COACHED] — the app owns it. The measurement sets the week, and every day is the same,
 *   because nothing has told it which days differ. A flat week is not a failure to distribute; it is
 *   the correct answer in the absence of information.
 * - [Mode.COLLABORATIVE] — the app owns the **total**, you own its **shape**. Name the heavy days
 *   and the same weekly budget moves onto them. Nothing is added or removed; it is redistributed.
 * - [Mode.MANUAL] — you own it. The app takes the number you set, works out what it will actually
 *   do to your weight, and says so. It changes nothing.
 *
 * ## ⚠️ The floor is per DAY, and that is the whole reason this is a core and not a UI helper
 *
 * [MacroTargets.ABSOLUTE_FLOOR_KCAL] exists so nobody is told to eat an amount that cannot carry a
 * day's nutrition. Applied to a weekly average it stops meaning anything: 1200 a day on average
 * permits 600 on Monday and 1800 on Tuesday, and the person eating 600 has been failed by a
 * guard rail that reported itself satisfied. Every constraint here is evaluated on the LOWEST day.
 *
 * ⚠️ And the floor a caller should pass is usually **not** the absolute one. [MacroTargets] refuses
 * to set a daily target below the resting rate, so a week whose light days dip under it would undo
 * a decision already taken one layer up. Pass `max(ABSOLUTE_FLOOR_KCAL, restingKcal)`.
 */
object WeeklyPlan {

    /** Seven, and it is a real constant rather than a magic number, because every share divides by it. */
    const val DAYS: Int = 7

    /**
     * How much of a light day's calories may be moved onto a heavy one, before the caps bite.
     *
     * ⚠️ 0.15 rather than something dramatic. This is the *default request*, not a limit — the limits
     * below are what actually protect anybody. A 15% cut on a rest day is a meaningful shift that
     * still leaves a rest day looking like a day somebody eats, which matters more than the size of
     * the swing: a plan that makes light days feel punitive is a plan that gets abandoned, and an
     * abandoned plan logs nothing, and nothing logged corrupts the measurement for a month.
     */
    const val DEFAULT_SHIFT: Double = 0.15

    /**
     * The most a heavy day may exceed the flat figure.
     *
     * ⚠️ Load-bearing when heavy days are FEW. Shifting 15% off six light days onto one heavy day
     * puts 190% of the daily figure on that day — arithmetically correct and not a thing to put in
     * front of somebody as a target. The cap is what turns "one training day a week" from a
     * two-thousand-calorie jump into a sensible one, and when it binds the plan says so.
     */
    const val MAX_HEAVY_MULTIPLE: Double = 1.35

    /** Which mode of being in charge this week is under. */
    enum class Mode(val label: String, val ownsTheTotal: String) {
        COACHED("Coached", "The app sets the week from what it has measured."),
        COLLABORATIVE("Collaborative", "The app sets the weekly total; you decide which days are heavy."),
        MANUAL("Manual", "You set the daily figure. The app works out what it will do."),
    }

    /** What a day is asked to be. */
    enum class DayKind(val label: String) {
        /** More than the flat figure — a training day, or whatever the person nominated. */
        HEAVY("Heavy"),

        /** The flat figure. Every day in a coached week, and any un-nominated day in a flat one. */
        EVEN("Even"),

        /** Less than the flat figure, so a heavy day can be more. */
        LIGHT("Light"),
    }

    /**
     * One day of the week.
     *
     * @param index 0..6. ⚠️ Deliberately an index and not a weekday name: which index is Monday is a
     *   calendar question, and this file has no calendar in it — the same discipline every pure core
     *   here follows, and the reason none of them are a day out in another time zone.
     */
    data class Day(
        val index: Int,
        val kcal: Int,
        val kind: DayKind,
        val targets: MacroTargets.Targets,
    )

    /** Why a requested shift was not the shift delivered. */
    enum class LimitKind {
        /** A light day would have fallen below the floor it was given. */
        FLOOR_HELD_THE_LIGHT_DAYS,

        /** A heavy day would have exceeded [MAX_HEAVY_MULTIPLE]. */
        HEAVY_DAY_CAPPED,

        /** Every day is heavy, or none is. There is nothing to move and nothing to move it from. */
        NOTHING_TO_SHIFT,
    }

    data class Limit(val kind: LimitKind, val sentence: String)

    data class Week(
        val mode: Mode,
        val budgetKcal: Int,
        val flatKcal: Int,
        val days: List<Day>,
        /** The shift actually applied, which is the requested one only when nothing bound. */
        val shift: Double,
        val limits: List<Limit>,
    ) {
        val heavyDays: List<Int> get() = days.filter { it.kind == DayKind.HEAVY }.map { it.index }
        val lightestKcal: Int get() = days.minOf { it.kcal }
        val heaviestKcal: Int get() = days.maxOf { it.kcal }

        /** ⚠️ Recomputed from the days rather than trusted: it is the invariant worth being able to assert. */
        val distributedKcal: Int get() = days.sumOf { it.kcal }
    }

    // ------------------------------------------------------------------------------------ building

    /**
     * Turn a daily plan into a week.
     *
     * @param base what [MacroTargets.plan] settled on for a single day. Its calories are the flat
     *   figure and its macros are the shape every day is built from.
     * @param mode who is in charge. [Mode.COACHED] and [Mode.MANUAL] both produce a flat week — for
     *   different reasons, which [Mode.ownsTheTotal] states — so [heavy] is consulted only under
     *   [Mode.COLLABORATIVE].
     * @param heavy which day indices are the heavy ones, 0..6. Out-of-range and duplicate entries are
     *   dropped rather than refused: this comes from a set of toggles, and a stale index is a bug in
     *   the caller, not a reason to give somebody no plan at all.
     * @param floorKcal the lowest any single day may be. See the class note — usually
     *   `max(ABSOLUTE_FLOOR_KCAL, restingKcal)`, not the absolute floor alone.
     * @param requestedShift the fraction to take off a light day before the caps are applied.
     */
    fun build(
        base: MacroTargets.Targets,
        mode: Mode,
        heavy: Set<Int> = emptySet(),
        floorKcal: Double = MacroTargets.ABSOLUTE_FLOOR_KCAL,
        requestedShift: Double = DEFAULT_SHIFT,
    ): Week {
        val flat = base.kcal
        val budget = flat * DAYS
        val limits = mutableListOf<Limit>()

        val nominated = if (mode == Mode.COLLABORATIVE) heavy.filter { it in 0 until DAYS }.toSet() else emptySet()
        val h = nominated.size
        val l = DAYS - h

        if (h == 0 || l == 0) {
            if (mode == Mode.COLLABORATIVE) {
                limits += Limit(
                    LimitKind.NOTHING_TO_SHIFT,
                    if (h == 0) {
                        "No heavy days picked, so the week is even. Choose the days you train and the " +
                            "same total moves onto them."
                    } else {
                        "Every day is marked heavy, so there is nothing to move calories from — which " +
                            "makes the week even again."
                    },
                )
            }
            return flatWeek(base, mode, budget, limits)
        }

        // ⚠️ Both ceilings are computed BEFORE anything is distributed, and the smaller one wins.
        // Applying a shift and then repairing the days it broke is how a floor ends up honoured on
        // six days and quietly missed on the seventh.
        val fromFloor = if (flat > 0) 1.0 - floorKcal / flat else 0.0
        val fromCap = (MAX_HEAVY_MULTIPLE - 1.0) * h / l
        val shift = min(max(requestedShift, 0.0), max(min(fromFloor, fromCap), 0.0))

        if (shift < requestedShift - 1e-9) {
            if (fromFloor <= fromCap) {
                limits += Limit(
                    LimitKind.FLOOR_HELD_THE_LIGHT_DAYS,
                    "The lighter days stop at ${floorKcal.roundToInt()} kcal — below that a day cannot " +
                        "carry what a body needs from it, whatever the week averages out at.",
                )
            } else {
                limits += Limit(
                    LimitKind.HEAVY_DAY_CAPPED,
                    "With $h heavy ${if (h == 1) "day" else "days"} against $l lighter ones, a bigger " +
                        "swing would put an unrealistic amount on the heavy days, so it is held at " +
                        "${(MAX_HEAVY_MULTIPLE * 100).roundToInt() - 100}% above the even figure.",
                )
            }
        }

        val lightKcal = flat * (1.0 - shift)
        val heavyKcal = flat * (1.0 + shift * l / h)

        // ⚠️ Rounded once, then reconciled against the budget — never rounded per day and hoped for.
        // Seven independent roundings drift by up to three kcal either way, and a week that does not
        // sum to its own stated budget is the first thing anybody checking the arithmetic will find.
        val raw = (0 until DAYS).map { if (it in nominated) heavyKcal else lightKcal }
        val rounded = reconcile(raw, budget)

        val days = (0 until DAYS).map { i ->
            val kcal = rounded[i]
            val kind = when {
                i in nominated -> DayKind.HEAVY
                shift <= 0.0 -> DayKind.EVEN
                else -> DayKind.LIGHT
            }
            Day(i, kcal, kind, macrosFor(base, kcal))
        }

        return Week(mode, budget, flat, days, shift, limits)
    }

    private fun flatWeek(
        base: MacroTargets.Targets,
        mode: Mode,
        budget: Int,
        limits: List<Limit>,
    ): Week = Week(
        mode = mode,
        budgetKcal = budget,
        flatKcal = base.kcal,
        days = (0 until DAYS).map { Day(it, base.kcal, DayKind.EVEN, base) },
        shift = 0.0,
        limits = limits,
    )

    /**
     * Round a week's worth of real numbers to whole calories that sum to exactly [budget].
     *
     * Largest-remainder: floor everything, then hand the shortfall out one calorie at a time to
     * whichever days were rounded down hardest. ⚠️ Not "give the remainder to day one", which would
     * quietly make Monday a few calories richer than an identical Tuesday for ever.
     */
    internal fun reconcile(raw: List<Double>, budget: Int): List<Int> {
        val floors = raw.map { kotlin.math.floor(it).toInt() }
        var short = budget - floors.sum()
        val order = raw.indices.sortedByDescending { raw[it] - floors[it] }
        val out = floors.toMutableList()
        var i = 0
        while (short > 0 && order.isNotEmpty()) {
            out[order[i % order.size]] += 1
            short -= 1
            i += 1
        }
        // A negative shortfall means the floors already overshot, which only happens if the caller
        // handed us a budget smaller than the numbers. Take it back the same way, largest first.
        var over = -short
        var j = 0
        while (over > 0 && order.isNotEmpty()) {
            val idx = order[order.size - 1 - (j % order.size)]
            if (out[idx] > 0) {
                out[idx] -= 1
                over -= 1
            }
            j += 1
            if (j > DAYS * 4) break
        }
        return out
    }

    /**
     * The macros for a day that is not the flat figure.
     *
     * ⚠️ **Carbohydrate carries the difference, and that is not an arbitrary pick.** Protein is
     * prescribed per kilogram of body mass and that requirement does not know what day of the week it
     * is; fat has a floor under it for reasons that are equally independent of the calendar. Carbs
     * are the one macro whose job is fuel for the work being done, so a day with more work in it is
     * a day with more carbohydrate. Cycling protein instead would mean under-eating it on rest days
     * for no reason at all.
     *
     * ⚠️ Fat gives way only when carbohydrate has already reached zero, and protein only when fat has
     * reached its own floor. That ordering is the same one [MacroTargets] applies, so a light day and
     * a daily plan cannot disagree about which macro is the last to be cut.
     */
    internal fun macrosFor(base: MacroTargets.Targets, kcal: Int): MacroTargets.Targets {
        if (kcal == base.kcal) return base
        val delta = kcal - base.kcal

        var carbG = base.carbG + delta / MacroTargets.KCAL_PER_G_CARB
        var fatG = base.fatG
        var proteinG = base.proteinG

        if (carbG < 0) {
            // Carbohydrate has run out; the rest comes off fat.
            val stillShortKcal = -carbG * MacroTargets.KCAL_PER_G_CARB
            carbG = 0
            fatG -= stillShortKcal / MacroTargets.KCAL_PER_G_FAT
            if (fatG < 0) {
                val shortAgain = -fatG * MacroTargets.KCAL_PER_G_FAT
                fatG = 0
                proteinG = max(0, proteinG - shortAgain / MacroTargets.KCAL_PER_G_PROTEIN)
            }
        }

        return MacroTargets.Targets(kcal = kcal, proteinG = proteinG, fatG = fatG, carbG = carbG)
    }

    // ----------------------------------------------------------------------------------- sentences

    /** One line describing the week, for a screen that has room for a sentence and not a table. */
    fun sentence(week: Week): String = when {
        week.shift <= 0.0 ->
            "${week.flatKcal} kcal a day, every day — ${week.budgetKcal} across the week."

        else ->
            "${week.heaviestKcal} kcal on your ${week.heavyDays.size} heavy " +
                "${if (week.heavyDays.size == 1) "day" else "days"} and ${week.lightestKcal} on the " +
                "rest — the same ${week.budgetKcal} across the week, moved to where the work is."
    }
}
