package dev.mascwa.pulse.core.telemetry

/**
 * The LARP "tale" — your standing across the wasteland, a.k.a. **renown**. You can **curate** it by choosing
 * an [Archetype] that seeds who the world thinks you are, OR leave it to **grow on its own** from your deeds
 * — either way it shifts as you act ([recordDeed]). Renown bends how towns, shops and factions treat you:
 * the revered get discounts and a friendly ear; the reviled get gouged and turned away.
 *
 * Pure + CI-tested; the on-device layer persists a [Legend], records deeds as you play, and folds the effects
 * ([shopPricePct]/[charismaBonus]) into the shop + resolve maths.
 */
enum class RenownTier(val label: String, val floor: Int, val welcome: String) {
    REVILED("Reviled", -100, "Doors bar at your approach; guns follow you out of town."),
    FEARED("Feared", -55, "They step aside — not out of respect."),
    UNKNOWN("Unknown", -20, "Just another face on the road."),
    KNOWN("Known", 20, "Your name earns a nod here and there."),
    RESPECTED("Respected", 55, "Word of you opens doors and loosens purse-strings."),
    REVERED("Revered", 80, "A living legend — the wasteland tells your story back to you."),
}

/** A curated starting tale — pick one to seed your renown, or take ENIGMA/WANDERER to start a blank slate. */
enum class Archetype(val label: String, val seedRenown: Int, val blurb: String) {
    HERO("Hero", 25, "You walk in as one of the good ones — and the wasteland has heard."),
    OUTLAW("Outlaw", -25, "A name spoken in low voices. Feared before you say a word."),
    MERCHANT("Merchant", 10, "Coin talks, and you've made sure it says your name kindly."),
    WANDERER("Wanderer", 0, "No past that follows you. You are exactly who you seem, today."),
    ENIGMA("Enigma", 0, "You keep no tale of your own — let the wasteland write it."),
}

/** Something you did that the wasteland remembers, and how much it shifts your renown. */
enum class DeedKind(val delta: Int, val note: String) {
    HELPED(6, "lent a hand"),
    SPARED(5, "showed mercy"),
    WON_TALK(3, "talked your way through"),
    TRADED_FAIR(2, "dealt squarely"),
    FLED(-1, "turned tail"),
    INTIMIDATED(-3, "leaned on the weak"),
    ROBBED(-7, "took what wasn't yours"),
    BETRAYED(-9, "broke a trust"),
}

/** Your tale so far: a renown value (clamped to [Legends.MIN]..[Legends.MAX]) and the archetype you curated. */
data class Legend(val renown: Int = 0, val archetype: Archetype? = null)

object Legends {
    const val MIN = -100
    const val MAX = 100

    private val tiersHighFirst = RenownTier.entries.sortedByDescending { it.floor }

    /** The [RenownTier] a renown value falls into. */
    fun tierFor(renown: Int): RenownTier =
        tiersHighFirst.firstOrNull { renown >= it.floor } ?: RenownTier.REVILED

    /** Begin a tale — seeded by the curated [archetype] (null / ENIGMA / WANDERER start neutral). */
    fun start(archetype: Archetype?): Legend =
        Legend((archetype?.seedRenown ?: 0).coerceIn(MIN, MAX), archetype)

    /** Record a [kind] of deed — the wasteland's memory shifts your renown (clamped). */
    fun recordDeed(legend: Legend, kind: DeedKind): Legend =
        legend.copy(renown = (legend.renown + kind.delta).coerceIn(MIN, MAX))

    /**
     * Shop-price modifier (percent) from your standing: the revered are given cut rates (negative), the
     * reviled are gouged (positive). Clamped to ±20% so it stacks sanely with reputation + world events.
     */
    fun shopPricePct(renown: Int): Int = (-renown / 5).coerceIn(-20, 20)

    /** A CHARISMA bump (or penalty) in social dealings for the well-regarded (or the hated). */
    fun charismaBonus(renown: Int): Int = when {
        renown >= 80 -> 2
        renown >= 40 -> 1
        renown <= -80 -> -2
        renown <= -40 -> -1
        else -> 0
    }

    /** A one-line read of the tale so far (tier + how the wasteland receives you). */
    fun describe(legend: Legend): String {
        val tier = tierFor(legend.renown)
        return "${tier.label} · ${tier.welcome}"
    }
}
