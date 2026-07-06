package dev.mascwa.pulse.core.telemetry

/**
 * Lingering survival AFFLICTIONS — the next layer of consequence beneath the needs. Leaving a need at
 * CRITICAL too long doesn't just dip a stat while it's low; it makes you SICK. Each need has an affliction
 * (dehydration, malnutrition, exhaustion, infection) that:
 *  - **incubates** while the need sits critical (a sickness meter fills), then **takes hold** at [ONSET_MS],
 *  - **sticks around** even after you top the need back up — taxing your checks HARDER than the raw need did,
 *  - only **clears** once you've kept that need genuinely HEALTHY long enough to shake it (the meter drains),
 *  - **holds steady** in between (topping a need out of critical stops it worsening, but you must get it well
 *    up to actually recover — a Fallout-hardcore disease doesn't vanish the instant you take a sip).
 *
 * Pure + deterministic (the caller injects the elapsed time and the profile) → CI-testable. This is the
 * consequence engine only; the on-device layer persists [AfflictionState], advances it on the decay tick,
 * folds [effects] into the check, and surfaces/notifies contracted & cured transitions. A fresh state is
 * empty, so it never touches the "blank profile is neutral" invariant.
 */
enum class Affliction(
    val label: String,
    /** The need whose sustained neglect causes it (and whose recovery cures it). */
    val need: NeedKind,
    /** The check penalties it applies while active — deliberately worse than the raw need's toll. */
    val effects: List<LifeEffect>,
    val desc: String,
    val cure: String,
) {
    DEHYDRATION(
        "Dehydration", NeedKind.HYDRATION,
        listOf(LifeEffect(Special.ENDURANCE, -2, "Dehydration"), LifeEffect(Special.PERCEPTION, -1, "Dizzy spells")),
        "Prolonged thirst has left you weak and light-headed.",
        "Stay hydrated to shake it.",
    ),
    MALNUTRITION(
        "Malnutrition", NeedKind.NOURISHMENT,
        listOf(LifeEffect(Special.STRENGTH, -2, "Malnutrition"), LifeEffect(Special.ENDURANCE, -1, "Wasting away")),
        "Going hungry too long has wasted your strength.",
        "Keep well-fed to recover.",
    ),
    EXHAUSTION(
        "Exhaustion", NeedKind.ENERGY,
        listOf(
            LifeEffect(Special.INTELLIGENCE, -2, "Sleep-deprived"),
            LifeEffect(Special.AGILITY, -1, "Slowed reflexes"),
            LifeEffect(Special.PERCEPTION, -1, "Foggy-headed"),
        ),
        "Deep sleep debt has dulled your mind and reflexes.",
        "Rest well to recover.",
    ),
    INFECTION(
        "Infection", NeedKind.HYGIENE,
        listOf(LifeEffect(Special.ENDURANCE, -2, "Infection"), LifeEffect(Special.CHARISMA, -1, "Feverish")),
        "Filth set into a wound — you've caught an infection.",
        "Stay clean to fight it off.",
    ),
    ;

    companion object {
        /** The affliction a given need can cause. */
        fun forNeed(need: NeedKind): Affliction = entries.first { it.need == need }
    }
}

/**
 * The persisted sickness state: a per-affliction accumulator ([meterMs], 0..[Afflictions.ONSET_MS]) plus the
 * set of afflictions that have actually taken hold ([active]). Two fields give clean hysteresis — the meter
 * fills/drains continuously, but an affliction only flips active at full and clears at empty, so it can't
 * flicker on a value hovering at the boundary.
 */
data class AfflictionState(
    val meterMs: Map<Affliction, Long> = emptyMap(),
    val active: Set<Affliction> = emptySet(),
)

object Afflictions {
    /** Sustained-critical time before a need's affliction takes hold. */
    const val ONSET_MS: Long = 6L * 60 * 60 * 1000        // 6h
    /** Healthy-need time to fully shake an affliction (recovery is slower than onset). */
    const val CURE_MS: Long = 10L * 60 * 60 * 1000        // 10h
    /** A need must be at or below this to worsen an affliction (matches the needs' CRITICAL band). */
    const val WORSEN_AT = LifeStats.NEED_CRITICAL          // ≤15
    /** …and at or above this to recover from one (must be genuinely healthy, not merely out of critical). */
    const val HEAL_AT = LifeStats.NEED_HEALTHY             // ≥60

    /**
     * Advance the sickness state by [elapsedMs] of real time against the current [profile]. For each need:
     * critical → the meter fills (capped at [ONSET_MS]); healthy → it drains toward 0 (over [CURE_MS]); in
     * between → it holds. An affliction flips **active** the instant its meter fills, and clears only when the
     * meter is fully drained. Deterministic; [elapsedMs] ≤ 0 is a no-op.
     */
    fun advance(state: AfflictionState, profile: LifeProfile, elapsedMs: Long): AfflictionState {
        if (elapsedMs <= 0) return state
        val meter = state.meterMs.toMutableMap()
        val active = state.active.toMutableSet()
        Affliction.entries.forEach { a ->
            val v = a.need.value(profile)
            val cur = meter[a] ?: 0L
            val next = when {
                v <= WORSEN_AT -> (cur + elapsedMs).coerceAtMost(ONSET_MS)
                v >= HEAL_AT -> (cur - (elapsedMs * ONSET_MS) / CURE_MS).coerceAtLeast(0L)
                else -> cur
            }
            if (next <= 0L) meter.remove(a) else meter[a] = next
            when {
                next >= ONSET_MS -> active += a
                next <= 0L -> active -= a
            }
        }
        return AfflictionState(meter, active)
    }

    /** The stacked check penalties from every active affliction. Empty when you're healthy. */
    fun effects(state: AfflictionState): List<LifeEffect> = state.active.flatMap { it.effects }

    /** Net affliction modifier to a check gated by [s]. */
    fun statBonus(state: AfflictionState, s: Special): Int =
        effects(state).filter { it.stat == s }.sumOf { it.delta }

    /** How far a not-yet-active affliction has incubated, 0f..1f (0 when none). For an "onset warning" UI. */
    fun incubation(state: AfflictionState, a: Affliction): Float {
        if (a in state.active) return 1f
        val m = state.meterMs[a] ?: return 0f
        return (m.toFloat() / ONSET_MS).coerceIn(0f, 1f)
    }

    /** Afflictions that are brewing but haven't taken hold yet (meter started, not yet active). */
    fun incubating(state: AfflictionState): List<Affliction> =
        Affliction.entries.filter { it !in state.active && (state.meterMs[it] ?: 0L) > 0L }

    /** How far along a cure is for an active affliction, 0f (just contracted) .. 1f (about to clear). */
    fun cureProgress(state: AfflictionState, a: Affliction): Float {
        if (a !in state.active) return 0f
        val m = state.meterMs[a] ?: return 1f
        return (1f - m.toFloat() / ONSET_MS).coerceIn(0f, 1f)
    }

    /** Afflictions newly taken hold going [old] → [new] (for a "you've contracted X" notification). */
    fun newlyContracted(old: AfflictionState, new: AfflictionState): List<Affliction> =
        new.active.filter { it !in old.active }

    /** Afflictions newly cleared going [old] → [new] (for a "you've recovered from X" notification). */
    fun newlyCured(old: AfflictionState, new: AfflictionState): List<Affliction> =
        old.active.filter { it !in new.active }

    /** A compact one-line status, e.g. "AFFLICTED · Dehydration, Exhaustion" or "" when well. */
    fun summary(state: AfflictionState): String =
        if (state.active.isEmpty()) "" else "AFFLICTED · " + state.active.joinToString(", ") { it.label }
}
