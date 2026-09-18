package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Lifting: what was done, what it was worth, and what to load next time.
 *
 * ## Why a nutrition app has a training core at all
 *
 * ⚠️ **NOT so that training days can be given extra calories.** That is the obvious thing to build
 * and it is wrong twice over. The energy cost of resistance training is small and enormously
 * variable between people and sessions, so any figure would be invented — and the app does not need
 * one, because [Expenditure] MEASURES total expenditure from weight change and intake. Training is
 * already in that number. Adding a bonus on top would count it twice, and the person would be
 * eating for work they had already been credited with.
 *
 * What training days genuinely earn is the calorie CYCLING in [WeeklyPlan]: the same weekly budget,
 * moved onto the days the work is on. That is a redistribution of a measured total, not an invented
 * addition to it, and it is the honest join between the two halves of this app.
 *
 * The second reason is simpler: somebody logging what they eat and what they lift wants both in one
 * place, and a second app means a second export.
 */
object Training {

    /** What a movement mostly asks of the body — used to group volume, not to score it. */
    enum class Pattern(val label: String) {
        SQUAT("Squat"),
        HINGE("Hinge"),
        PUSH("Push"),
        PULL("Pull"),
        CARRY("Carry"),
        CORE("Core"),
        CONDITIONING("Conditioning"),
        OTHER("Other"),
    }

    /** A movement, as a person names it. */
    data class Exercise(
        val id: String,
        val name: String,
        val pattern: Pattern = Pattern.OTHER,
        /**
         * ⚠️ Whether the load is external and measurable. A press-up is real work and its "load" is
         * a body, which this file has no business estimating — so its sets carry reps and no weight,
         * and every figure derived from load skips it rather than guessing one.
         */
        val loaded: Boolean = true,
    )

    /**
     * One set as it was performed.
     *
     * @param rpe rating of perceived exertion, 1..10, or null when not recorded. Ten means nothing
     *   left; eight means about two more were available.
     */
    data class SetEntry(
        val reps: Int,
        val loadKg: Double? = null,
        val rpe: Double? = null,
    ) {
        /** Whether this set can contribute to anything that multiplies by weight. */
        val weighed: Boolean get() = loadKg != null && loadKg.isFinite() && loadKg > 0.0 && reps > 0

        /** Reps in reserve, the more directly useful reading of [rpe]. */
        val repsInReserve: Double? get() = rpe?.takeIf { it in 1.0..10.0 }?.let { 10.0 - it }
    }

    /** Everything done on one exercise in one session. */
    data class Movement(val exercise: Exercise, val sets: List<SetEntry>)

    /** One training session. */
    data class Session(val atMs: Long, val movements: List<Movement>, val note: String = "")

    // ------------------------------------------------------------------------------------- volume

    /**
     * Kilograms lifted: sets × reps × load, over everything that had a load.
     *
     * ⚠️ Unweighted work is EXCLUDED rather than counted as zero, and the difference matters when
     * this figure is compared week to week: a week of press-ups counted as zero tonnage reads as a
     * week off.
     */
    fun tonnageKg(session: Session): Double =
        session.movements.sumOf { m -> m.sets.filter { it.weighed }.sumOf { it.reps * it.loadKg!! } }

    /**
     * Sets that were actually hard.
     *
     * ⚠️ **The unit that training volume is measured in, and deliberately not "all sets".** Warm-ups
     * are sets; they are not work. A set counts when it was taken to [HARD_RPE] or beyond, or — when
     * nothing was recorded — when it was within [WARMUP_LOAD_SHARE] of the heaviest set on that
     * movement, which is the best available proxy for "not a warm-up".
     */
    fun hardSets(session: Session): Int =
        session.movements.sumOf { m ->
            val top = m.sets.mapNotNull { it.loadKg }.maxOrNull()
            m.sets.count { s ->
                when {
                    s.reps <= 0 -> false
                    s.rpe != null -> s.rpe >= HARD_RPE
                    top != null && s.loadKg != null -> s.loadKg >= top * WARMUP_LOAD_SHARE
                    else -> true
                }
            }
        }

    /** Hard sets by movement pattern, for somebody asking whether a week was balanced. */
    fun setsByPattern(sessions: List<Session>): Map<Pattern, Int> {
        val out = mutableMapOf<Pattern, Int>()
        for (s in sessions) {
            for (m in s.movements) {
                val n = hardSets(Session(s.atMs, listOf(m)))
                if (n > 0) out[m.exercise.pattern] = (out[m.exercise.pattern] ?: 0) + n
            }
        }
        return out
    }

    const val HARD_RPE: Double = 7.0

    /** A set at or above this share of the day's heaviest on that movement is treated as work. */
    const val WARMUP_LOAD_SHARE: Double = 0.85

    // -------------------------------------------------------------------------------- one-rep max

    /** Which published estimator produced a figure. Part of the claim, so it travels with it. */
    enum class Formula(val label: String) {
        /** Epley: 1RM = w × (1 + r / 30). */
        EPLEY("Epley"),

        /** Brzycki: 1RM = w × 36 / (37 − r). */
        BRZYCKI("Brzycki"),
    }

    /**
     * The most reps either formula is fitted for.
     *
     * ⚠️ **Both diverge badly past about ten and Brzycki divides by zero at thirty-seven.** Neither
     * was fitted on high-rep sets, so an estimate from a set of twenty is not a weaker estimate — it
     * is a number with no support behind it. Refused rather than clamped.
     */
    const val MAX_REPS_FOR_ESTIMATE: Int = 10

    /**
     * Estimate a one-rep maximum from a set that was taken near failure.
     *
     * ⚠️ Returns null rather than a figure whenever the inputs cannot support one — no load, no
     * reps, or more reps than the formula was fitted for. A one-rep max is a number people load a
     * bar from.
     *
     * ⚠️ It also assumes the set was taken CLOSE TO FAILURE, which is the condition both formulas
     * were fitted under. A set of five with four left in reserve says almost nothing about a maximum.
     * [estimateOneRepMax] with an [SetEntry] applies that check; the raw overload does not, because a
     * caller with a known-maximal set should not have to fabricate an RPE to use it.
     */
    fun oneRepMax(reps: Int, loadKg: Double, formula: Formula = Formula.EPLEY): Double? {
        if (reps <= 0 || reps > MAX_REPS_FOR_ESTIMATE) return null
        if (!loadKg.isFinite() || loadKg <= 0.0) return null
        if (reps == 1) return loadKg
        return when (formula) {
            Formula.EPLEY -> loadKg * (1.0 + reps / 30.0)
            Formula.BRZYCKI -> loadKg * 36.0 / (37.0 - reps)
        }
    }

    /** The reps in reserve past which a set says nothing useful about a maximum. */
    const val MAX_RIR_FOR_ESTIMATE: Double = 3.0

    /** As [oneRepMax], but refusing a set that was recorded as far from failure. */
    fun estimateOneRepMax(set: SetEntry, formula: Formula = Formula.EPLEY): Double? {
        val load = set.loadKg ?: return null
        val rir = set.repsInReserve
        if (rir != null && rir > MAX_RIR_FOR_ESTIMATE) return null
        return oneRepMax(set.reps, load, formula)
    }

    /** The best estimate across a session's sets on one movement, or null when none supports one. */
    fun bestOneRepMax(movement: Movement, formula: Formula = Formula.EPLEY): Double? =
        movement.sets.mapNotNull { estimateOneRepMax(it, formula) }.maxOrNull()

    // ------------------------------------------------------------------------------- progression

    /** The smallest change worth making to a bar, by how the movement is loaded. */
    const val SMALL_INCREMENT_KG: Double = 1.25

    const val LARGE_INCREMENT_KG: Double = 2.5

    /** Patterns whose loads move in the larger step, because the plates and the movement allow it. */
    private val LARGE_STEP = setOf(Pattern.SQUAT, Pattern.HINGE, Pattern.CARRY)

    /** How far from the target RPE a set has to land before the load moves. */
    const val RPE_DEADBAND: Double = 0.5

    sealed interface Advice {
        /** Move the load, and by how much. Zero means hold it deliberately. */
        data class Load(val deltaKg: Double, val toKg: Double, val sentence: String) : Advice

        /**
         * Nothing to advise, and why.
         *
         * ⚠️ A first-class answer. Without an RPE there is nothing to autoregulate against, and an
         * app that invented a progression anyway would be adding weight on a schedule rather than on
         * evidence — which is the thing autoregulation exists to replace.
         */
        data class Unknown(val sentence: String) : Advice
    }

    /**
     * What to load next time, from how the last hard set actually felt.
     *
     * ⚠️ **RPE, not the calendar.** Linear "add 2.5 kg every week" schedules fail the first week
     * somebody sleeps badly, and then keep failing because the schedule does not know. Judging the
     * set that was performed is the whole idea: came in easier than asked for, the load goes up;
     * harder, it comes down; inside the dead band, it holds.
     *
     * @param targetRpe how hard the set is meant to be. Eight is the common working figure — about
     *   two reps left — and the caller supplies it because that is a programme decision, not this
     *   file's.
     */
    fun nextLoad(movement: Movement, targetRpe: Double = 8.0): Advice {
        // ⚠️ Asked BEFORE looking for a weighed set, and the order is the point. An unloaded movement
        // has no weighed sets by construction, so the other branch would fire first and tell somebody
        // who HAD recorded how hard their press-ups felt to go and record it.
        if (!movement.exercise.loaded) {
            return Advice.Unknown(
                "${movement.exercise.name} is not loaded with a weight, so the way to make it harder " +
                    "is reps or leverage rather than a number on a bar.",
            )
        }
        val working = movement.sets.filter { it.weighed && it.rpe != null }
        val last = working.lastOrNull()
            ?: return Advice.Unknown(
                "No effort recorded on a weighed set, so there is nothing to judge the next load " +
                    "against. Note how hard the last set felt and this can answer.",
            )
        val rpe = last.rpe!!
        val load = last.loadKg!!
        val step = if (movement.exercise.pattern in LARGE_STEP) LARGE_INCREMENT_KG else SMALL_INCREMENT_KG
        val gap = targetRpe - rpe

        if (abs(gap) <= RPE_DEADBAND) {
            return Advice.Load(
                0.0,
                load,
                "That landed about where it should, so keep ${fmt(load)} kg and aim for the same again.",
            )
        }
        // ⚠️ One step at a time, however large the gap. Two steps from one set is extrapolating from
        // a single data point, and the next session provides another one anyway.
        val wanted = if (gap > 0) step else -step
        // ⚠️ A decrease is bounded by the CURRENT load as well as by the step, and the upper bound is
        // the one that matters: at a load already below one increment, `load + delta` is negative, and
        // flooring it at the step alone turns "that was too hard" into an instruction to add weight.
        val raw = load + wanted
        val to = if (wanted < 0) raw.coerceIn(minOf(load, step), load) else raw.coerceAtLeast(step)
        val actual = to - load
        return Advice.Load(
            actual,
            to,
            when {
                actual > 0 -> "That came in easier than asked for, so put it up to ${fmt(to)} kg."
                actual < 0 -> "That was harder than asked for, so take it back to ${fmt(to)} kg and earn it again."
                else ->
                    "That was harder than asked for, but ${fmt(load)} kg is already the lightest this " +
                        "loads to — hold it there and let the reps come down instead."
            },
        )
    }

    // ------------------------------------------------------------------------ the join to eating

    /**
     * Which days of the week a training record says are the heavy ones.
     *
     * ⚠️ **This is the whole join between lifting and eating, and it moves no calories of its own.**
     * It answers "which days did work happen on", which is exactly the input [WeeklyPlan] needs to
     * cycle a measured weekly budget onto the days that need it. No energy figure is invented at any
     * point, because [Expenditure] has already measured the total.
     *
     * @param dayIndexOf supplied by the caller, because which index is Monday is a calendar question
     *   and this file has no calendar in it — the same rule every pure core here follows.
     */
    fun heavyDays(sessions: List<Session>, dayIndexOf: (Long) -> Int): Set<Int> =
        sessions.filter { hardSets(it) > 0 }
            .map { dayIndexOf(it.atMs) }
            .filter { it in 0 until WeeklyPlan.DAYS }
            .toSet()

    // ------------------------------------------------------------------------------- the catalogue

    /**
     * Movements offered before anybody has typed one.
     *
     * ⚠️ **In the core rather than in either app**, so a session logged on the phone and read on the
     * desktop is talking about the same exercise. The ids are permanent for the same reason
     * `NutrientSet`'s are: they are written into saved sessions, and renaming one would detach
     * somebody's history from the movement it belongs to.
     *
     * ⚠️ Deliberately short. This is a starting point, not an attempt at every lift that exists —
     * anything missing is one text field away, and a list nobody can read past is worse than a list
     * somebody adds to. Every pattern the volume breakdown can report appears at least once, or a
     * beginner would find a category on that screen they had no way to fill.
     */
    val STARTER: List<Exercise> = listOf(
        Exercise("squat.back", "Back squat", Pattern.SQUAT),
        Exercise("squat.front", "Front squat", Pattern.SQUAT),
        Exercise("squat.goblet", "Goblet squat", Pattern.SQUAT),
        Exercise("squat.split", "Bulgarian split squat", Pattern.SQUAT),
        Exercise("squat.legpress", "Leg press", Pattern.SQUAT),
        Exercise("hinge.deadlift", "Deadlift", Pattern.HINGE),
        Exercise("hinge.romanian", "Romanian deadlift", Pattern.HINGE),
        Exercise("hinge.hipthrust", "Hip thrust", Pattern.HINGE),
        Exercise("hinge.goodmorning", "Good morning", Pattern.HINGE),
        Exercise("push.bench", "Bench press", Pattern.PUSH),
        Exercise("push.incline", "Incline bench press", Pattern.PUSH),
        Exercise("push.overhead", "Overhead press", Pattern.PUSH),
        Exercise("push.dip", "Dip", Pattern.PUSH, loaded = false),
        Exercise("push.pressup", "Press-up", Pattern.PUSH, loaded = false),
        Exercise("pull.row", "Barbell row", Pattern.PULL),
        Exercise("pull.pulldown", "Lat pulldown", Pattern.PULL),
        Exercise("pull.chinup", "Chin-up", Pattern.PULL, loaded = false),
        Exercise("pull.facepull", "Face pull", Pattern.PULL),
        Exercise("pull.curl", "Biceps curl", Pattern.PULL),
        Exercise("carry.farmer", "Farmer's carry", Pattern.CARRY),
        Exercise("carry.suitcase", "Suitcase carry", Pattern.CARRY),
        Exercise("core.plank", "Plank", Pattern.CORE, loaded = false),
        Exercise("core.hanging", "Hanging leg raise", Pattern.CORE, loaded = false),
        Exercise("core.rollout", "Ab rollout", Pattern.CORE, loaded = false),
        Exercise("cond.row", "Rowing machine", Pattern.CONDITIONING, loaded = false),
        Exercise("cond.bike", "Stationary bike", Pattern.CONDITIONING, loaded = false),
        Exercise("cond.run", "Run", Pattern.CONDITIONING, loaded = false),
        Exercise("cond.sled", "Sled push", Pattern.CONDITIONING),
    )

    /**
     * What marks a movement somebody added rather than one the build ships.
     *
     * ⚠️ **In the core, beside the catalogue whose ids it must not collide with.** An added
     * movement's id is written into every session that uses it, so the prefix is part of the saved
     * format rather than a UI detail — and three places need to agree about it: the model that
     * mints ids, the picker that decides which entries can be forgotten, and anything that later
     * tells the two apart.
     */
    const val OWN_PREFIX: String = "own:"

    /** A movement by id, from the catalogue or from anything the person added themselves. */
    fun exercise(id: String, added: List<Exercise> = emptyList()): Exercise? =
        added.firstOrNull { it.id == id } ?: STARTER.firstOrNull { it.id == id }

    // ---------------------------------------------------------------------------------- sentences

    /** One line describing a session, for a list that has room for a sentence and not a table. */
    fun sentence(session: Session): String {
        val sets = hardSets(session)
        if (sets == 0) return "Nothing logged on this session yet."
        val tonnage = tonnageKg(session)
        val movements = session.movements.count { it.sets.isNotEmpty() }
        val volumePart = if (tonnage > 0) " · ${fmt(tonnage)} kg moved" else ""
        return "$sets hard ${if (sets == 1) "set" else "sets"} across $movements " +
            "${if (movements == 1) "movement" else "movements"}$volumePart"
    }

    private fun fmt(kg: Double): String =
        if (abs(kg - kg.roundToInt()) < 0.01) "${kg.roundToInt()}"
        else String.format(java.util.Locale.US, "%.2f", kg).trimEnd('0').trimEnd('.')
}
