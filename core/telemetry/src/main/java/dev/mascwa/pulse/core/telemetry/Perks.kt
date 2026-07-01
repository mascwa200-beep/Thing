package dev.mascwa.pulse.core.telemetry

/**
 * Perks for the [SpecialGame] — passive bonuses the player picks on milestone levels (a perk pick every
 * even level). Pure data (CI-gated); [SpecialGame] resolves an owned perk set into its effects at check /
 * reward time. Effects stack, so a build is more than the sum of its stats.
 */
data class Perk(
    val id: String,
    val name: String,
    val desc: String,
    /** Grants [statBonusAmt] to checks gated by this attribute. */
    val statBonus: Special? = null,
    val statBonusAmt: Int = 2,
    /** +% caps from a winning encounter. */
    val capsBonusPct: Int = 0,
    /** +% XP from a winning encounter. */
    val xpBonusPct: Int = 0,
    /** Crits come easier (a smaller margin over the difficulty counts as critical). */
    val luckierCrits: Boolean = false,
    /** HP recovered on any successful encounter. */
    val healOnWin: Int = 0,
)

object Perks {
    val ALL: List<Perk> = listOf(
        Perk("strong_back", "Strong Back", "+2 to STRENGTH checks.", statBonus = Special.STRENGTH),
        Perk("eagle_eye", "Eagle Eye", "+2 to PERCEPTION checks.", statBonus = Special.PERCEPTION),
        Perk("iron_gut", "Iron Gut", "+2 to ENDURANCE checks.", statBonus = Special.ENDURANCE),
        Perk("smooth_talker", "Smooth Talker", "+2 to CHARISMA checks.", statBonus = Special.CHARISMA),
        Perk("cryptographer", "Cryptographer", "+2 to INTELLIGENCE checks.", statBonus = Special.INTELLIGENCE),
        Perk("light_step", "Light Step", "+2 to AGILITY checks.", statBonus = Special.AGILITY),
        Perk("four_leaf", "Four-Leaf", "+2 to LUCK checks.", statBonus = Special.LUCK),
        Perk("scrounger", "Scrounger", "+25% caps from every win.", capsBonusPct = 25),
        Perk("fast_learner", "Fast Learner", "+25% XP from every win.", xpBonusPct = 25),
        Perk("field_medic", "Field Medic", "Recover 4 HP on any successful encounter.", healOnWin = 4),
        Perk("born_lucky", "Born Lucky", "Critical successes come easier.", luckierCrits = true),
    )

    fun byId(id: String): Perk? = ALL.firstOrNull { it.id == id }
}
