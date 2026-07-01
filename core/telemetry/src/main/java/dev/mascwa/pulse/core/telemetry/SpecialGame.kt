package dev.mascwa.pulse.core.telemetry

/**
 * The S.P.E.C.I.A.L. game — a small, self-contained wasteland RPG driven by the seven Fallout-style
 * attributes. This is the pure, CI-tested engine: a [Character] you build up, [Encounter]s with
 * stat-gated [Choice]s, and deterministic resolution (the die [roll] is injected, so every outcome is
 * reproducible and testable). No Android types; the on-device store supplies randomness + persistence,
 * and the STAT-tab UI renders it.
 *
 * Loop: pick an encounter → choose an option gated by a stat check (`stat + d10 + luck ≥ difficulty`) →
 * win XP/caps (sometimes loot or a stat point) or take a hit → level up → allocate a S.P.E.C.I.A.L.
 * point. Higher stats open tougher choices with better payouts, so the attributes actually matter.
 */

/** The seven attributes. [letter] is the Pip-Boy glyph; [display]/[blurb] drive the UI. */
enum class Special(val letter: Char, val display: String, val blurb: String) {
    STRENGTH('S', "STRENGTH", "Raw force — break, carry, overpower."),
    PERCEPTION('P', "PERCEPTION", "Awareness — spot traps, ambushes, detail."),
    ENDURANCE('E', "ENDURANCE", "Grit — soak damage, resist, endure."),
    CHARISMA('C', "CHARISMA", "Presence — persuade, barter, lead."),
    INTELLIGENCE('I', "INTELLIGENCE", "Wits — hack, deduce, decode."),
    AGILITY('A', "AGILITY", "Reflexes — dodge, sneak, strike fast."),
    LUCK('L', "LUCK", "Fortune — the wasteland's thumb on the scale."),
}

/** A reward/penalty applied when a [Choice] resolves. Deltas; [hp] negative = damage. */
data class Outcome(
    val text: String,
    val xp: Int = 0,
    val caps: Int = 0,
    val hp: Int = 0,
    val statPoint: Boolean = false,
)

/**
 * One option in an [Encounter]. [stat] is the gate (null = a safe/no-check option that always "passes");
 * [difficulty] is the target the check must reach. [success]/[failure] are the branches.
 */
data class Choice(
    val text: String,
    val stat: Special?,
    val difficulty: Int,
    val success: Outcome,
    val failure: Outcome,
)

/** A wasteland scenario. Non-[repeatable] encounters are retired once seen. */
data class Encounter(
    val id: String,
    val title: String,
    val prompt: String,
    val choices: List<Choice>,
    val repeatable: Boolean = false,
)

/** The player's persistent state. [stats] each clamp to 1..10; [hp] clamps to [maxHp]. */
data class Character(
    val stats: Map<Special, Int>,
    val level: Int = 1,
    val xp: Int = 0,
    val caps: Int = 25,
    val hp: Int = 40,
    val unspent: Int = 0,
    val seen: Set<String> = emptySet(),
    val currentEncounterId: String? = null,
) {
    fun stat(s: Special): Int = (stats[s] ?: 1).coerceIn(1, 10)

    /** Max HP scales with ENDURANCE, so grit buys survivability. */
    val maxHp: Int get() = BASE_HP + stat(Special.ENDURANCE) * HP_PER_END

    /** XP needed to advance from the current level to the next. */
    val xpToNext: Int get() = level * XP_PER_LEVEL

    /** Downed (HP depleted) — must [SpecialGame.revive] before venturing again. */
    val down: Boolean get() = hp <= 0

    companion object {
        const val BASE_HP = 20
        const val HP_PER_END = 5
        const val XP_PER_LEVEL = 100
        const val START_STAT = 4
        const val START_POINTS = 3
    }
}

/** The maths of a single stat check. [total] = stat + roll + luck modifier. */
data class CheckResult(val success: Boolean, val crit: Boolean, val total: Int, val roll: Int)

/** The full result of resolving a choice: the check + the applied [outcome] + the updated [character]. */
data class Resolution(
    val success: Boolean,
    val crit: Boolean,
    val outcome: Outcome,
    val character: Character,
    val roll: Int,
)

object SpecialGame {

    /** Faces on the check die. A d10: rolls are 1..10. */
    const val DIE = 10

    /** A pass this far over the difficulty (or a natural [DIE]) is a critical — doubled XP + bonus caps. */
    const val CRIT_MARGIN = 6

    /** A fresh operative: every stat at [Character.START_STAT], with [Character.START_POINTS] to spend. */
    fun newCharacter(): Character {
        val base = Special.entries.associateWith { Character.START_STAT }
        val hp = Character.BASE_HP + Character.START_STAT * Character.HP_PER_END
        return Character(stats = base, hp = hp, unspent = Character.START_POINTS)
    }

    /**
     * Resolve a stat check. [roll] is a d10 (1..[DIE]); a natural [DIE] always succeeds (and crits), a
     * natural 1 always fails. Otherwise `stat + roll + luckMod ≥ difficulty`, where LUCK tilts the odds
     * by (luck − 5) / 2 (i.e. −2 at LUCK 1 … +2 at LUCK 10).
     */
    fun check(statValue: Int, difficulty: Int, luck: Int, roll: Int): CheckResult {
        val luckMod = (luck - 5) / 2
        val total = statValue + roll + luckMod
        val success = when {
            roll >= DIE -> true
            roll <= 1 -> false
            else -> total >= difficulty
        }
        val crit = success && (roll >= DIE || total >= difficulty + CRIT_MARGIN)
        return CheckResult(success, crit, total, roll)
    }

    /**
     * Resolve [choiceIndex] of [encounter] for [character] with die [roll]. Applies the winning/losing
     * outcome (crit doubles XP + adds bonus caps), advances XP/level, marks a non-repeatable encounter
     * seen, and clears the current encounter. Pure — same inputs, same result.
     */
    fun resolve(character: Character, encounter: Encounter, choiceIndex: Int, roll: Int): Resolution {
        val choice = encounter.choices.getOrNull(choiceIndex)
            ?: return Resolution(false, false, Outcome("Nothing happens."), character, roll)

        val result = if (choice.stat == null) {
            CheckResult(success = true, crit = false, total = 0, roll = roll)
        } else {
            check(character.stat(choice.stat), choice.difficulty, character.stat(Special.LUCK), roll)
        }

        var outcome = if (result.success) choice.success else choice.failure
        if (result.crit && result.success) {
            outcome = outcome.copy(xp = outcome.xp * 2, caps = outcome.caps + outcome.caps / 2)
        }

        var updated = applyOutcome(character, outcome)
        if (!encounter.repeatable) updated = updated.copy(seen = updated.seen + encounter.id)
        updated = updated.copy(currentEncounterId = null)
        return Resolution(result.success, result.crit, outcome, updated, roll)
    }

    /** Add XP, cascading level-ups (each grants an unspent point + heals to full on the level). */
    fun gainXp(character: Character, amount: Int): Character {
        if (amount <= 0) return character
        var level = character.level
        var xp = character.xp + amount
        var unspent = character.unspent
        var leveled = false
        while (xp >= level * Character.XP_PER_LEVEL) {
            xp -= level * Character.XP_PER_LEVEL
            level++
            unspent++
            leveled = true
        }
        val advanced = character.copy(level = level, xp = xp, unspent = unspent)
        return if (leveled) advanced.copy(hp = advanced.maxHp) else advanced
    }

    /** Spend one unspent point on [s] (no-op at 0 points or when the stat is already 10). */
    fun allocate(character: Character, s: Special): Character {
        if (character.unspent <= 0) return character
        val current = character.stat(s)
        if (current >= 10) return character
        val stats = character.stats.toMutableMap().apply { put(s, current + 1) }
        val updated = character.copy(stats = stats, unspent = character.unspent - 1)
        // Raising ENDURANCE lifts the HP ceiling; grant the new headroom immediately.
        return if (s == Special.ENDURANCE) updated.copy(hp = (character.hp + Character.HP_PER_END).coerceAtMost(updated.maxHp)) else updated
    }

    /** Pick the next encounter: prefer unseen (or repeatable) ones; [roll] chooses within the pool. */
    fun nextEncounter(character: Character, all: List<Encounter>, roll: Int): Encounter? {
        if (all.isEmpty()) return null
        val fresh = all.filter { it.repeatable || it.id !in character.seen }
        val pool = if (fresh.isNotEmpty()) fresh else all.filter { it.repeatable }
        if (pool.isEmpty()) return null
        return pool[roll.coerceAtLeast(0) % pool.size]
    }

    /** Get back up after being downed: full HP, minus a quarter of your caps as the toll. */
    fun revive(character: Character): Character = character.copy(
        hp = character.maxHp,
        caps = (character.caps - character.caps / 4).coerceAtLeast(0),
        currentEncounterId = null,
    )

    private fun applyOutcome(character: Character, o: Outcome): Character {
        var updated = character.copy(
            caps = (character.caps + o.caps).coerceAtLeast(0),
            hp = (character.hp + o.hp).coerceIn(0, character.maxHp),
            unspent = character.unspent + if (o.statPoint) 1 else 0,
        )
        if (o.xp > 0) updated = gainXp(updated, o.xp)
        return updated
    }
}
