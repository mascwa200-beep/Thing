package dev.mascwa.pulse.core.telemetry

/**
 * Companions for the S.P.E.C.I.A.L. game — a wasteland ally you hire (for caps) who tags along and helps
 * on every encounter. Unlike perks you can only have ONE active at a time, and they cost caps to hire, so
 * they're a build choice + a caps sink (grind). Their bonus folds into [SpecialGame.resolve] just like a
 * perk's. Pure + CI-tested.
 */
data class Companion(
    val id: String,
    val name: String,
    val desc: String,
    /** Caps to hire. Hiring a different companion replaces the current one (and costs again). */
    val cost: Int,
    val statBonus: Special? = null,
    val statBonusAmt: Int = 0,
    val healOnWin: Int = 0,
    val luckierCrits: Boolean = false,
)

object Companions {
    val ALL: List<Companion> = listOf(
        Companion("hound", "Wasteland Hound", "A loyal mutt that sniffs out trouble. +2 PERCEPTION.", 70, Special.PERCEPTION, 2),
        Companion("scout", "Wasteland Scout", "Light-footed and quick on the draw. +2 AGILITY.", 80, Special.AGILITY, 2),
        Companion("charmer", "Silver-Tongue", "Smooths over every deal and standoff. +2 CHARISMA.", 85, Special.CHARISMA, 2),
        Companion("fixer", "Tech Fixer", "Talks you through the hard tech. +2 INTELLIGENCE.", 85, Special.INTELLIGENCE, 2),
        Companion("merc", "Hired Gun", "A grizzled mercenary who does the heavy lifting. +2 STRENGTH.", 90, Special.STRENGTH, 2),
        Companion("medic", "Field Medic", "Patches you up after every scrap. Heal +4 on a win.", 100, healOnWin = 4),
        Companion("charm", "Charm-Bearer", "Fortune favours you both — critical wins come easier.", 120, luckierCrits = true),
    )

    private val byId: Map<String, Companion> = ALL.associateBy { it.id }
    fun byId(id: String): Companion? = byId[id]
}
