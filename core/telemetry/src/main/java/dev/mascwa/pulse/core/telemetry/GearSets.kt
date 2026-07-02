package dev.mascwa.pulse.core.telemetry

/**
 * Gear sets for the S.P.E.C.I.A.L. game — a synergy layer on top of individual GEAR bonuses. Carrying every
 * piece of a set grants an extra [bonusStat] boost *beyond* the pieces' own passive bonuses, so a
 * well-matched loadout rewards chasing specific (mostly rare, +2-tier) gear via loot / crafting / shops.
 *
 * Pure + CI-tested. The bonus rides the exact same check path as [SpecialGame.gearStatBonus], so it stacks
 * cleanly with perks / companions / env / life. All [pieces] ids resolve in [Items].
 */
data class GearSet(
    val id: String,
    val name: String,
    val desc: String,
    val pieces: Set<String>,
    val bonusStat: Special,
    val bonusAmt: Int,
)

object GearSets {
    val ALL: List<GearSet> = listOf(
        GearSet(
            "set_enforcer", "Enforcer", "Heavy hitter's rig — the gauntlet and webbing move as one.",
            setOf("power_gauntlet", "combat_webbing"), Special.STRENGTH, 1,
        ),
        GearSet(
            "set_infiltrator", "Infiltrator", "Move unseen, strike first — servos and recon optics in sync.",
            setOf("sprint_servos", "recon_optics"), Special.AGILITY, 1,
        ),
        GearSet(
            "set_envoy", "Envoy", "Words that open doors — the suit and neural implant read the room.",
            setOf("negotiator_suit", "neural_implant"), Special.CHARISMA, 1,
        ),
        GearSet(
            "set_fortune", "Fortune's Favor", "The wasteland smiles — idol and charm bend every roll.",
            setOf("fortune_idol", "lucky_charm"), Special.LUCK, 2,
        ),
    )

    private val byId: Map<String, GearSet> = ALL.associateBy { it.id }
    fun byId(id: String): GearSet? = byId[id]

    /** Whether every piece of [set] is carried (count > 0). */
    fun isComplete(set: GearSet, inventory: Map<String, Int>): Boolean =
        set.pieces.all { (inventory[it] ?: 0) > 0 }

    /** The sets fully assembled in [inventory], in catalog order. */
    fun active(inventory: Map<String, Int>): List<GearSet> = ALL.filter { isComplete(it, inventory) }

    /** How many pieces of [set] are carried (for a "2/3" progress readout). */
    fun ownedPieces(set: GearSet, inventory: Map<String, Int>): Int =
        set.pieces.count { (inventory[it] ?: 0) > 0 }

    /** Total set-bonus to checks gated by [s] from every complete set that boosts [s]. */
    fun statBonus(inventory: Map<String, Int>, s: Special): Int =
        active(inventory).filter { it.bonusStat == s }.sumOf { it.bonusAmt }
}
