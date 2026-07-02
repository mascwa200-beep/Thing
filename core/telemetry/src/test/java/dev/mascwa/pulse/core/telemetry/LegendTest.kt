package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the LARP "tale"/renown system. */
class LegendTest {

    @Test fun tierThresholdsMapCorrectly() {
        assertEquals(RenownTier.REVILED, Legends.tierFor(-100))
        assertEquals(RenownTier.REVILED, Legends.tierFor(-56))
        assertEquals(RenownTier.FEARED, Legends.tierFor(-55))
        assertEquals(RenownTier.FEARED, Legends.tierFor(-21))
        assertEquals(RenownTier.UNKNOWN, Legends.tierFor(-20))
        assertEquals(RenownTier.UNKNOWN, Legends.tierFor(0))
        assertEquals(RenownTier.UNKNOWN, Legends.tierFor(19))
        assertEquals(RenownTier.KNOWN, Legends.tierFor(20))
        assertEquals(RenownTier.KNOWN, Legends.tierFor(54))
        assertEquals(RenownTier.RESPECTED, Legends.tierFor(55))
        assertEquals(RenownTier.RESPECTED, Legends.tierFor(79))
        assertEquals(RenownTier.REVERED, Legends.tierFor(80))
        assertEquals(RenownTier.REVERED, Legends.tierFor(100))
    }

    @Test fun startSeedsFromArchetype() {
        assertEquals(25, Legends.start(Archetype.HERO).renown)
        assertEquals(Archetype.HERO, Legends.start(Archetype.HERO).archetype)
        assertEquals(-25, Legends.start(Archetype.OUTLAW).renown)
        assertEquals(10, Legends.start(Archetype.MERCHANT).renown)
        assertEquals(0, Legends.start(Archetype.WANDERER).renown)
        assertEquals(0, Legends.start(Archetype.ENIGMA).renown)
        // No curated archetype → blank slate.
        assertEquals(0, Legends.start(null).renown)
        assertNull(Legends.start(null).archetype)
    }

    @Test fun recordDeedShiftsRenownAndClamps() {
        val start = Legend(renown = 0)
        assertEquals(6, Legends.recordDeed(start, DeedKind.HELPED).renown)
        assertEquals(-9, Legends.recordDeed(start, DeedKind.BETRAYED).renown)
        // Clamps at the ceiling.
        val high = Legend(renown = 98)
        assertEquals(Legends.MAX, Legends.recordDeed(high, DeedKind.HELPED).renown)
        // Clamps at the floor.
        val low = Legend(renown = -98)
        assertEquals(Legends.MIN, Legends.recordDeed(low, DeedKind.BETRAYED).renown)
        // Archetype is carried through a deed.
        val hero = Legends.start(Archetype.HERO)
        assertEquals(Archetype.HERO, Legends.recordDeed(hero, DeedKind.WON_TALK).archetype)
    }

    @Test fun deedsAccumulateIntoATale() {
        var tale = Legends.start(Archetype.WANDERER) // 0
        tale = Legends.recordDeed(tale, DeedKind.HELPED)      // +6
        tale = Legends.recordDeed(tale, DeedKind.TRADED_FAIR) // +2
        tale = Legends.recordDeed(tale, DeedKind.WON_TALK)    // +3
        assertEquals(11, tale.renown)
        tale = Legends.recordDeed(tale, DeedKind.ROBBED)      // -7
        assertEquals(4, tale.renown)
    }

    @Test fun shopPriceRewardsTheReveredAndGougesTheReviled() {
        // Revered → a discount (negative percent).
        assertTrue("revered get a discount", Legends.shopPricePct(100) < 0)
        assertEquals(-20, Legends.shopPricePct(100))
        // Reviled → a markup (positive percent).
        assertTrue("reviled get gouged", Legends.shopPricePct(-100) > 0)
        assertEquals(20, Legends.shopPricePct(-100))
        // Neutral → no modifier.
        assertEquals(0, Legends.shopPricePct(0))
        // Clamped to ±20 even past the renown range.
        assertEquals(-20, Legends.shopPricePct(200))
        assertEquals(20, Legends.shopPricePct(-200))
    }

    @Test fun charismaBonusTiersBySide() {
        assertEquals(2, Legends.charismaBonus(80))
        assertEquals(2, Legends.charismaBonus(100))
        assertEquals(1, Legends.charismaBonus(40))
        assertEquals(1, Legends.charismaBonus(79))
        assertEquals(0, Legends.charismaBonus(0))
        assertEquals(0, Legends.charismaBonus(39))
        assertEquals(0, Legends.charismaBonus(-39))
        assertEquals(-1, Legends.charismaBonus(-40))
        assertEquals(-1, Legends.charismaBonus(-79))
        assertEquals(-2, Legends.charismaBonus(-80))
        assertEquals(-2, Legends.charismaBonus(-100))
    }

    @Test fun describeReadsTheTier() {
        assertTrue(Legends.describe(Legend(renown = 100)).startsWith("Revered"))
        assertTrue(Legends.describe(Legend(renown = -100)).startsWith("Reviled"))
        assertTrue(Legends.describe(Legend(renown = 0)).startsWith("Unknown"))
    }

    // --- Integration with SpecialGame (renown bends checks + shops; curate/grow on the character) ---

    @Test fun renownBendsCharismaChecks() {
        val enc = Encounter(
            "t", "T", "p",
            listOf(Choice("charm", Special.CHARISMA, 11, Outcome("win", xp = 10), Outcome("lose"))),
        )
        val c = SpecialGame.newCharacter() // CHA 4, LUCK 4 → luckMod 0
        // 4 + roll 6 + 0 = 10 < 11 → fail at neutral renown.
        assertFalse(SpecialGame.resolve(c, enc, 0, roll = 6).success)
        // Revered (+2 CHA): 6 + 6 = 12 ≥ 11 → success.
        assertTrue(SpecialGame.resolve(c.copy(legend = Legend(renown = 100)), enc, 0, roll = 6).success)
        // Reviled (−2 CHA) only makes it worse.
        assertFalse(SpecialGame.resolve(c.copy(legend = Legend(renown = -100)), enc, 0, roll = 6).success)
    }

    @Test fun renownBendsShopPrices() {
        val item = Items.ALL.first { it.value >= 50 }
        val c = SpecialGame.newCharacter()
        val neutral = SpecialGame.shopPrice(item, c, LocationKind.TRADER)
        val revered = SpecialGame.shopPrice(item, c.copy(legend = Legend(renown = 100)), LocationKind.TRADER)
        val reviled = SpecialGame.shopPrice(item, c.copy(legend = Legend(renown = -100)), LocationKind.TRADER)
        assertTrue("the revered are given cut rates", revered < neutral)
        assertTrue("the reviled get gouged", reviled > neutral)
    }

    @Test fun curateAndRecordDeedUpdateTheCharacter() {
        val c = SpecialGame.newCharacter()
        val hero = SpecialGame.curateLegend(c, Archetype.HERO)
        assertEquals(25, hero.legend.renown)
        assertEquals(Archetype.HERO, hero.legend.archetype)
        val helped = SpecialGame.recordDeed(hero, DeedKind.HELPED)
        assertEquals(31, helped.legend.renown) // 25 + 6
    }
}
