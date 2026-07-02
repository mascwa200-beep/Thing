package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [GearSets] + its [SpecialGame] check integration. */
class GearSetsTest {

    private fun char(inv: Map<String, Int>): Character =
        Character(stats = Special.entries.associateWith { 4 }, inventory = inv)

    @Test fun everySetPieceResolvesInItemsAsGear() {
        assertTrue(GearSets.ALL.isNotEmpty())
        assertEquals(GearSets.ALL.size, GearSets.ALL.map { it.id }.toSet().size) // unique ids
        GearSets.ALL.forEach { set ->
            assertTrue("a set needs 2+ pieces", set.pieces.size >= 2)
            set.pieces.forEach { id ->
                val item = Items.byId(id)
                assertNotNull("Unknown set piece '$id' in ${set.id}", item)
                assertEquals("set pieces should be GEAR", ItemKind.GEAR, item!!.kind)
            }
        }
    }

    @Test fun setIsCompleteOnlyWithEveryPiece() {
        val enforcer = GearSets.byId("set_enforcer")!! // power_gauntlet + combat_webbing
        assertFalse(GearSets.isComplete(enforcer, mapOf("power_gauntlet" to 1)))
        assertEquals(1, GearSets.ownedPieces(enforcer, mapOf("power_gauntlet" to 1)))
        assertTrue(GearSets.isComplete(enforcer, mapOf("power_gauntlet" to 1, "combat_webbing" to 1)))
        assertEquals(2, GearSets.ownedPieces(enforcer, mapOf("power_gauntlet" to 1, "combat_webbing" to 1)))
    }

    @Test fun activeAndStatBonusReflectCompleteSetsOnly() {
        val half = mapOf("fortune_idol" to 1)
        assertTrue(GearSets.active(half).isEmpty())
        assertEquals(0, GearSets.statBonus(half, Special.LUCK))
        val full = mapOf("fortune_idol" to 1, "lucky_charm" to 1)
        assertEquals(1, GearSets.active(full).size)
        assertEquals(2, GearSets.statBonus(full, Special.LUCK)) // Fortune's Favor = +2 LUCK
        assertEquals(0, GearSets.statBonus(full, Special.STRENGTH)) // unrelated stat unaffected
    }

    @Test fun setBonusFeedsTheResolveCheck() {
        // A LUCK check the character would just miss becomes reachable once the LUCK set is complete.
        assertEquals(0, SpecialGame.setStatBonus(char(mapOf("fortune_idol" to 1)), Special.LUCK))
        assertEquals(2, SpecialGame.setStatBonus(char(mapOf("fortune_idol" to 1, "lucky_charm" to 1)), Special.LUCK))
    }
}
