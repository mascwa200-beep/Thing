package dev.mascwa.pulse.core.telemetry

import kotlin.math.roundToInt

/**
 * EXERTION — the core loop spends survival needs. Until now the needs decayed only on the wall clock, so an
 * engaged player was decoupled from their own survival: you could grind twenty fights and your hydration
 * barely moved. Exertion makes the fight / scavenge / travel loop itself a pressure source — every ENGAGE,
 * VENTURE, SCAVENGE and real-world TRAVEL costs a little hydration / energy / (on hard fights) nourishment,
 * so a run of action walks your needs down through the existing tiers → penalties → afflictions → alerts with
 * no new downstream code. It NEVER touches HP (so it can't down you or soft-lock) and NEVER touches
 * [LifeStats.effects] (so a blank profile stays neutral) — it only nudges the four survival needs.
 *
 * Pure + deterministic: the caller injects the difficulty / gesture intensity / travelled metres and the
 * store applies the cost with the same capture-decayed-then-re-anchor discipline as [LifeStats] top-ups.
 */
enum class ExertKind(val label: String) {
    ENGAGE("Fight"),
    VENTURE("Venture"),
    SCAVENGE("Scavenge"),
    TRAVEL("Travel"),
}

/** A drain on the survival needs (points, ≥0). Hygiene is left to time/motion decay, not exertion. */
data class ExertCost(val hydration: Int = 0, val energy: Int = 0, val nourishment: Int = 0) {
    val isEmpty: Boolean get() = hydration == 0 && energy == 0 && nourishment == 0
    operator fun plus(o: ExertCost) =
        ExertCost(hydration + o.hydration, energy + o.energy, nourishment + o.nourishment)

    /** Compact readout, e.g. "−3 HYD −4 EN". Blank when empty. */
    fun describe(): String = buildList {
        if (hydration > 0) add("−$hydration HYD")
        if (energy > 0) add("−$energy EN")
        if (nourishment > 0) add("−$nourishment NRG")
    }.joinToString(" ")
}

/**
 * The sub-one-point remainder carried between travel steps, so a slow accumulation of small distances still
 * eventually costs a whole point instead of always flooring to zero. Persisted alongside the travel counters.
 */
data class ExertCarry(val hyd: Double = 0.0, val en: Double = 0.0, val nour: Double = 0.0)

object Exertion {
    // Encounter (ENGAGE) costs. A check difficulty at/below EASY_DC is a light scrap; each band above adds a
    // little more thirst/fatigue, and a HARD_DC+ fight also burns food. A vigorous gesture burns more energy.
    const val EASY_DC = 8
    const val HARD_DC = 14
    const val ENGAGE_HYDRATION = 2       // base thirst from any fight
    const val ENGAGE_ENERGY = 1          // base fatigue from any fight
    const val ENGAGE_HARD_NOURISHMENT = 1 // a hard fight also costs food
    const val ENGAGE_DIFF_BAND = 6       // every 6 DC over EASY adds +1 hyd and +1 en
    const val ENGAGE_INTENSITY_ENERGY = 2 // a flat-out gesture adds up to this much energy

    // Venture (drawing an encounter) is a small physical commitment; scavenging is heavier legwork.
    const val VENTURE_ENERGY = 1
    const val SCAVENGE_HYDRATION = 3
    const val SCAVENGE_ENERGY = 4

    // Per-metre travel drain by mode (points/metre). DRIVE/STILL cost nothing; running is the most taxing.
    private const val WALK_HYD = 0.006
    private const val WALK_EN = 0.006
    private const val RUN_HYD = 0.012
    private const val RUN_EN = 0.015
    private const val RUN_NOUR = 0.004
    private const val CYCLE_HYD = 0.004
    private const val CYCLE_EN = 0.005

    /** The cost of one fight, scaled by the check [difficulty] and how hard you swung the gesture ([intensity] 0..1). */
    fun engageCost(difficulty: Int, intensity: Float): ExertCost {
        val band = ((difficulty - EASY_DC).coerceAtLeast(0)) / ENGAGE_DIFF_BAND
        val i = intensity.coerceIn(0f, 1f)
        return ExertCost(
            hydration = ENGAGE_HYDRATION + band,
            energy = ENGAGE_ENERGY + band + (i * ENGAGE_INTENSITY_ENERGY).roundToInt(),
            nourishment = if (difficulty >= HARD_DC) ENGAGE_HARD_NOURISHMENT else 0,
        )
    }

    /** The cost of venturing out to draw an encounter. */
    fun ventureCost(): ExertCost = ExertCost(energy = VENTURE_ENERGY)

    /** The cost of combing a site for loot. */
    fun scavengeCost(): ExertCost = ExertCost(hydration = SCAVENGE_HYDRATION, energy = SCAVENGE_ENERGY)

    private fun ratesFor(mode: TransportMode): Triple<Double, Double, Double> = when (mode) {
        TransportMode.WALK -> Triple(WALK_HYD, WALK_EN, 0.0)
        TransportMode.RUN -> Triple(RUN_HYD, RUN_EN, RUN_NOUR)
        TransportMode.CYCLE -> Triple(CYCLE_HYD, CYCLE_EN, 0.0)
        TransportMode.DRIVE, TransportMode.STILL -> Triple(0.0, 0.0, 0.0)
    }

    /**
     * The cost of covering [meters] on foot/wheel in [mode], carrying the sub-point remainder in [carry] so a
     * trickle of small distances eventually spends a whole point. Returns the whole-point cost to apply now +
     * the updated carry. DRIVE/STILL (and non-positive distance) cost nothing.
     */
    fun travelCost(mode: TransportMode, meters: Double, carry: ExertCarry): Pair<ExertCost, ExertCarry> {
        if (meters <= 0.0) return ExertCost() to carry
        val (rh, re, rn) = ratesFor(mode)
        val hydAcc = carry.hyd + meters * rh
        val enAcc = carry.en + meters * re
        val nourAcc = carry.nour + meters * rn
        val cost = ExertCost(hydAcc.toInt(), enAcc.toInt(), nourAcc.toInt())
        return cost to ExertCarry(hydAcc - cost.hydration, enAcc - cost.energy, nourAcc - cost.nourishment)
    }

    /** The most a perk stack can shave off exertion cost — a floor so exertion never becomes free. */
    const val REDUCTION_CAP = 60

    /**
     * Reduce a [cost] by [reductionPct] (0..[REDUCTION_CAP]), rounding each need to the nearest point and
     * clamping at 0 — a resilience perk ([Perk.exertReductionPct]) makes the fight/trek cost the body less.
     * Small one-point costs survive a modest reduction (round-to-nearest), which is intended.
     */
    fun scale(cost: ExertCost, reductionPct: Int): ExertCost {
        val pct = reductionPct.coerceIn(0, REDUCTION_CAP)
        if (pct == 0 || cost.isEmpty) return cost
        fun cut(v: Int) = (v - (v * pct / 100.0).roundToInt()).coerceAtLeast(0)
        return ExertCost(cut(cost.hydration), cut(cost.energy), cut(cost.nourishment))
    }

    /** Apply a cost to the profile's needs, clamped 0..100. Never touches hygiene, HP or any [LifeEffect]. */
    fun apply(p: LifeProfile, cost: ExertCost): LifeProfile = p.copy(
        hydration = (p.hydration - cost.hydration).coerceIn(0, 100),
        energy = (p.energy - cost.energy).coerceIn(0, 100),
        nourishment = (p.nourishment - cost.nourishment).coerceIn(0, 100),
    )
}
