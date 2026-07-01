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

/** A reward/penalty applied when a [Choice] resolves. Deltas; [hp] negative = damage. [items] = loot dropped (id → count). */
data class Outcome(
    val text: String,
    val xp: Int = 0,
    val caps: Int = 0,
    val hp: Int = 0,
    val statPoint: Boolean = false,
    val items: Map<String, Int> = emptyMap(),
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
    val perks: Set<String> = emptySet(),
    val perkPicks: Int = 0,
    val inventory: Map<String, Int> = emptyMap(),
    val companion: String? = null,
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

    /** The [CRIT_MARGIN] the "Born Lucky" perk swaps in — crits come easier. */
    const val LUCKY_CRIT_MARGIN = 3

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
    fun check(statValue: Int, difficulty: Int, luck: Int, roll: Int, critMargin: Int = CRIT_MARGIN): CheckResult {
        val luckMod = (luck - 5) / 2
        val total = statValue + roll + luckMod
        val success = when {
            roll >= DIE -> true
            roll <= 1 -> false
            else -> total >= difficulty
        }
        val crit = success && (roll >= DIE || total >= difficulty + critMargin)
        return CheckResult(success, crit, total, roll)
    }

    /**
     * Resolve [choiceIndex] of [encounter] for [character] with die [roll]. The check value stacks the
     * character's base stat + perks + carried GEAR + the real-world [env] modifier + an optional [useItemId]
     * CHEM (consumed for this check). Applies the winning/losing outcome (crit doubles XP + adds bonus caps,
     * loot dropped), advances XP/level, marks a non-repeatable encounter seen, and clears the current
     * encounter. Pure — same inputs, same result. [env]/[useItemId] default to none, so existing callers
     * behave exactly as before.
     */
    fun resolve(
        character: Character,
        encounter: Encounter,
        choiceIndex: Int,
        roll: Int,
        env: EnvContext? = null,
        useItemId: String? = null,
    ): Resolution {
        val choice = encounter.choices.getOrNull(choiceIndex)
            ?: return Resolution(false, false, Outcome("Nothing happens."), character, roll)

        // A CHEM only "fires" (and is consumed) when it's held and matches this check's stat.
        val activeChem: Item? = useItemId?.let { Items.byId(it) }?.takeIf {
            it.kind == ItemKind.CHEM &&
                (character.inventory[it.id] ?: 0) > 0 &&
                choice.stat != null && it.statBonus == choice.stat
        }

        val critMargin = if (perkLuckierCrits(character) || companionLuckierCrits(character)) LUCKY_CRIT_MARGIN else CRIT_MARGIN
        val result = if (choice.stat == null) {
            CheckResult(success = true, crit = false, total = 0, roll = roll)
        } else {
            val statValue = character.stat(choice.stat) +
                perkStatBonus(character, choice.stat) +
                gearStatBonus(character, choice.stat) +
                companionStatBonus(character, choice.stat) +
                (env?.let { Environment.statBonus(it, choice.stat) } ?: 0) +
                (activeChem?.statBonusAmt ?: 0)
            check(statValue, choice.difficulty, character.stat(Special.LUCK), roll, critMargin)
        }

        var outcome = if (result.success) choice.success else choice.failure
        if (result.crit && result.success) {
            outcome = outcome.copy(xp = outcome.xp * 2, caps = outcome.caps + outcome.caps / 2)
        }
        // Perks reward success: +% caps / +% XP, and a little healing.
        if (result.success) {
            outcome = outcome.copy(
                caps = pctScale(outcome.caps, perkCapsPct(character)),
                xp = pctScale(outcome.xp, perkXpPct(character)),
                hp = outcome.hp + perkHealOnWin(character) + companionHealOnWin(character),
            )
        }

        var updated = applyOutcome(character, outcome)
        if (activeChem != null) updated = removeItem(updated, activeChem.id, 1)
        if (!encounter.repeatable) updated = updated.copy(seen = updated.seen + encounter.id)
        updated = updated.copy(currentEncounterId = null)
        return Resolution(result.success, result.crit, outcome, updated, roll)
    }

    // --- Inventory + items ---

    /** Passive bonus from carried GEAR that boosts checks gated by [s] (each distinct piece counts once). */
    fun gearStatBonus(c: Character, s: Special): Int = c.inventory.entries.sumOf { (id, qty) ->
        if (qty <= 0) return@sumOf 0
        val item = Items.byId(id)
        if (item != null && item.kind == ItemKind.GEAR && item.statBonus == s) item.statBonusAmt else 0
    }

    /** Add [qty] of item [id] to the inventory (no-op for unknown ids or non-positive counts). */
    fun addItem(c: Character, id: String, qty: Int = 1): Character {
        if (qty <= 0 || Items.byId(id) == null) return c
        return c.copy(inventory = c.inventory + (id to (c.inventory[id] ?: 0) + qty))
    }

    /** Remove [qty] of item [id]; drops the key when the count hits zero. */
    fun removeItem(c: Character, id: String, qty: Int = 1): Character {
        val have = c.inventory[id] ?: 0
        if (have <= 0 || qty <= 0) return c
        val next = have - qty
        val inv = if (next <= 0) c.inventory - id else c.inventory + (id to next)
        return c.copy(inventory = inv)
    }

    /** Use an AID item from the inventory to heal (consumed; no-op if none held or it isn't an AID). */
    fun useAid(c: Character, id: String): Character {
        val item = Items.byId(id) ?: return c
        if (item.kind != ItemKind.AID || item.healAmt <= 0) return c
        if ((c.inventory[id] ?: 0) <= 0) return c
        val healed = c.copy(hp = (c.hp + item.healAmt).coerceIn(0, c.maxHp))
        return removeItem(healed, id, 1)
    }

    /** Sell one of item [id] for half its [Item.value] in caps (min 1). No-op if none held. */
    fun sellItem(c: Character, id: String): Character {
        val item = Items.byId(id) ?: return c
        if ((c.inventory[id] ?: 0) <= 0) return c
        val gain = (item.value / 2).coerceAtLeast(1)
        return removeItem(c.copy(caps = c.caps + gain), id, 1)
    }

    /** Buy one of item [id] for its [Item.value] in caps. No-op if the character can't afford it. */
    fun buyItem(c: Character, id: String): Character {
        val item = Items.byId(id) ?: return c
        if (c.caps < item.value) return c
        return addItem(c.copy(caps = c.caps - item.value), id, 1)
    }

    /** Whether [c] can craft [r] — holds every input and meets the recipe's stat gate. */
    fun canCraft(c: Character, r: Recipe): Boolean {
        if (Items.byId(r.outputId) == null) return false
        if (r.stat != null && c.stat(r.stat) < r.minStat) return false
        return r.inputs.all { (id, qty) -> (c.inventory[id] ?: 0) >= qty }
    }

    /** Craft [r]: consume its inputs, yield its output, grant its XP. No-op if [canCraft] is false. */
    fun craft(c: Character, r: Recipe): Character {
        if (!canCraft(c, r)) return c
        var updated = c
        for ((id, qty) in r.inputs) updated = removeItem(updated, id, qty)
        updated = addItem(updated, r.outputId, r.outputQty)
        if (r.xp > 0) updated = gainXp(updated, r.xp)
        return updated
    }

    /** Add XP, cascading level-ups (each grants an unspent point + heals to full on the level). */
    fun gainXp(character: Character, amount: Int): Character {
        if (amount <= 0) return character
        var level = character.level
        var xp = character.xp + amount
        var unspent = character.unspent
        var perkPicks = character.perkPicks
        var leveled = false
        while (xp >= level * Character.XP_PER_LEVEL) {
            xp -= level * Character.XP_PER_LEVEL
            level++
            unspent++
            if (level % 2 == 0) perkPicks++ // a perk to pick every even level
            leveled = true
        }
        val advanced = character.copy(level = level, xp = xp, unspent = unspent, perkPicks = perkPicks)
        return if (leveled) advanced.copy(hp = advanced.maxHp) else advanced
    }

    /** Choose a perk (spends a perk pick; no-op without a pick, an unknown id, or one already owned). */
    fun choosePerk(character: Character, perkId: String): Character {
        if (character.perkPicks <= 0) return character
        if (perkId in character.perks) return character
        if (Perks.byId(perkId) == null) return character
        return character.copy(perks = character.perks + perkId, perkPicks = character.perkPicks - 1)
    }

    // --- Perk effect resolution (an owned perk set → its stacked bonuses) ---
    private fun owned(c: Character): List<Perk> = Perks.ALL.filter { it.id in c.perks }

    /** Total bonus a character's perks grant to checks gated by [s]. */
    fun perkStatBonus(c: Character, s: Special): Int = owned(c).filter { it.statBonus == s }.sumOf { it.statBonusAmt }

    private fun perkCapsPct(c: Character): Int = owned(c).sumOf { it.capsBonusPct }
    private fun perkXpPct(c: Character): Int = owned(c).sumOf { it.xpBonusPct }
    private fun perkHealOnWin(c: Character): Int = owned(c).sumOf { it.healOnWin }
    private fun perkLuckierCrits(c: Character): Boolean = owned(c).any { it.luckierCrits }

    // --- Companion effect resolution (the one active hired ally) ---
    private fun activeCompanion(c: Character): Companion? = c.companion?.let { Companions.byId(it) }

    /** The bonus the active companion grants to checks gated by [s]. */
    fun companionStatBonus(c: Character, s: Special): Int =
        activeCompanion(c)?.takeIf { it.statBonus == s }?.statBonusAmt ?: 0

    private fun companionHealOnWin(c: Character): Int = activeCompanion(c)?.healOnWin ?: 0
    private fun companionLuckierCrits(c: Character): Boolean = activeCompanion(c)?.luckierCrits ?: false

    /** Hire companion [id] for its caps cost (replaces any current one). No-op if unknown, already hired, or too poor. */
    fun hireCompanion(c: Character, id: String): Character {
        val comp = Companions.byId(id) ?: return c
        if (c.companion == id) return c
        if (c.caps < comp.cost) return c
        return c.copy(caps = c.caps - comp.cost, companion = id)
    }

    /** Send the active companion on their way (no refund). */
    fun dismissCompanion(c: Character): Character = c.copy(companion = null)

    /** Scale a positive reward by a percentage (no-op for zero pct or non-positive rewards). */
    private fun pctScale(value: Int, pct: Int): Int = if (pct == 0 || value <= 0) value else value + value * pct / 100

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
        for ((id, qty) in o.items) updated = addItem(updated, id, qty)
        if (o.xp > 0) updated = gainXp(updated, o.xp)
        return updated
    }
}
