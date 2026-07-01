package dev.mascwa.pulse.core.telemetry

/**
 * Faction reputation for the S.P.E.C.I.A.L. game — standing with each kind of wasteland location
 * ([LocationKind]) that rises as you trade and talk there, unlocking shop discounts. Ties the map/shop/NPC
 * systems into a progression layer. Pure + CI-tested; the character carries a per-faction point total and
 * the engine turns it into a tier + a discount.
 */
enum class RepTier(val label: String, val discountPct: Int, val minPoints: Int) {
    NEUTRAL("Neutral", 0, 0),
    LIKED("Liked", 5, 25),
    TRUSTED("Trusted", 10, 60),
    ALLIED("Allied", 20, 120),
}

object Reputation {
    const val MAX = 250
    const val PER_PURCHASE = 3 // rep gained per item bought from a faction's shop
    const val PER_TALK = 4     // rep gained per successful conversation with a faction NPC

    /** The highest tier whose threshold [points] has reached (NEUTRAL's threshold is 0, so always matches). */
    fun tier(points: Int): RepTier = RepTier.entries.last { points >= it.minPoints }

    /** The shop discount (%) [points] of reputation grants. */
    fun discountPct(points: Int): Int = tier(points).discountPct

    /** [baseValue] after the discount [points] earns (never below 1). */
    fun discountedPrice(baseValue: Int, points: Int): Int =
        (baseValue - baseValue * discountPct(points) / 100).coerceAtLeast(1)
}
