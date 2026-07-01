package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [Reputation] + the [SpecialGame] faction ops. */
class ReputationTest {

    private fun char(caps: Int = 200, rep: Map<String, Int> = emptyMap()): Character =
        Character(stats = Special.entries.associateWith { 4 }, caps = caps, reputation = rep)

    @Test fun tiersByThreshold() {
        assertEquals(RepTier.NEUTRAL, Reputation.tier(0))
        assertEquals(RepTier.NEUTRAL, Reputation.tier(24))
        assertEquals(RepTier.LIKED, Reputation.tier(25))
        assertEquals(RepTier.TRUSTED, Reputation.tier(60))
        assertEquals(RepTier.ALLIED, Reputation.tier(120))
        assertEquals(RepTier.ALLIED, Reputation.tier(9999))
    }

    @Test fun discountFollowsTier() {
        assertEquals(0, Reputation.discountPct(0))
        assertEquals(5, Reputation.discountPct(25))
        assertEquals(20, Reputation.discountPct(200))
    }

    @Test fun discountedPriceNeverBelowOne() {
        assertEquals(100, Reputation.discountedPrice(100, 0))     // neutral
        assertEquals(90, Reputation.discountedPrice(100, 60))     // trusted 10%
        assertEquals(80, Reputation.discountedPrice(100, 120))    // allied 20%
        assertEquals(1, Reputation.discountedPrice(1, 200))       // floor
    }

    @Test fun addRepClampsToRange() {
        val c = char()
        assertEquals(5, SpecialGame.rep(SpecialGame.addRep(c, LocationKind.TRADER, 5), LocationKind.TRADER))
        val maxed = SpecialGame.addRep(c, LocationKind.TRADER, 9999)
        assertEquals(Reputation.MAX, SpecialGame.rep(maxed, LocationKind.TRADER))
        val floored = SpecialGame.addRep(c, LocationKind.TRADER, -50)
        assertEquals(0, SpecialGame.rep(floored, LocationKind.TRADER))
    }

    @Test fun repIsPerFaction() {
        val c = SpecialGame.addRep(char(), LocationKind.MEDIC, 30)
        assertEquals(30, SpecialGame.rep(c, LocationKind.MEDIC))
        assertEquals(0, SpecialGame.rep(c, LocationKind.TRADER)) // independent
    }

    @Test fun buyAtAppliesDiscountAndEarnsRep() {
        // medkit is 40 caps; at ALLIED (20% off) it costs 32.
        val c = char(caps = 100, rep = mapOf(LocationKind.TRADER.name to 120))
        val after = SpecialGame.buyItemAt(c, "medkit", LocationKind.TRADER)
        assertEquals(100 - 32, after.caps)
        assertEquals(1, after.inventory["medkit"])
        assertEquals(120 + Reputation.PER_PURCHASE, SpecialGame.rep(after, LocationKind.TRADER))
    }

    @Test fun buyAtNoOpWhenTooPoor() {
        val c = char(caps = 5)
        assertEquals(c, SpecialGame.buyItemAt(c, "medkit", LocationKind.TRADER)) // 40 > 5
    }

    @Test fun buyAtFullPriceAtNeutral() {
        val c = char(caps = 100)
        val after = SpecialGame.buyItemAt(c, "medkit", LocationKind.TRADER)
        assertEquals(100 - Items.MEDKIT.value, after.caps) // no discount
        assertTrue(SpecialGame.rep(after, LocationKind.TRADER) > 0) // still earns standing
    }
}
