package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [Recipes] + the [SpecialGame] crafting ops. */
class CraftingTest {

    private fun char(inv: Map<String, Int> = emptyMap(), int: Int = 4): Character {
        val stats = Special.entries.associateWith { 4 }.toMutableMap()
        stats[Special.INTELLIGENCE] = int
        return Character(stats = stats, inventory = inv)
    }

    @Test fun recipeCatalogIsValid() {
        assertTrue(Recipes.ALL.isNotEmpty())
        assertEquals(Recipes.ALL.size, Recipes.ALL.map { it.id }.toSet().size) // unique ids
        assertNotNull(Recipes.byId("craft_grip"))
        assertNull(Recipes.byId("nope"))
    }

    @Test fun everyRecipeIdResolvesInItems() {
        // A typo'd input/output id would make the recipe silently un-craftable / grant nothing.
        Recipes.ALL.forEach { r ->
            assertNotNull("Unknown output '${r.outputId}' in ${r.id}", Items.byId(r.outputId))
            r.inputs.keys.forEach { id -> assertNotNull("Unknown input '$id' in ${r.id}", Items.byId(id)) }
        }
    }

    @Test fun canCraftWhenInputsPresent() {
        val r = Recipes.byId("craft_grip")!! // scrap_metal x2 + wire_spool x1
        assertTrue(SpecialGame.canCraft(char(mapOf("scrap_metal" to 2, "wire_spool" to 1)), r))
        assertFalse(SpecialGame.canCraft(char(mapOf("scrap_metal" to 1, "wire_spool" to 1)), r)) // short a scrap
        assertFalse(SpecialGame.canCraft(char(), r)) // nothing
    }

    @Test fun statGateBlocksCraft() {
        val r = Recipes.byId("craft_slate")!! // INT >= 5
        val inv = mapOf("circuit_board" to 2, "wire_spool" to 1)
        assertFalse(SpecialGame.canCraft(char(inv, int = 4), r)) // too dim
        assertTrue(SpecialGame.canCraft(char(inv, int = 5), r))
    }

    @Test fun craftConsumesInputsAddsOutputAndXp() {
        val r = Recipes.byId("craft_grip")!!
        val before = char(mapOf("scrap_metal" to 3, "wire_spool" to 1))
        val after = SpecialGame.craft(before, r)
        assertEquals(1, after.inventory["scrap_metal"]) // 3 - 2 consumed
        assertNull(after.inventory["wire_spool"])       // 1 - 1 consumed → key gone
        assertEquals(1, after.inventory["grip_gloves"]) // output added
        assertEquals(r.xp, after.xp)                    // small xp granted
    }

    @Test fun craftIsNoOpWhenShort() {
        val r = Recipes.byId("craft_rig")!! // scrap_metal x3 + wire_spool x1
        val before = char(mapOf("scrap_metal" to 1))
        assertEquals(before, SpecialGame.craft(before, r)) // unchanged
    }

    @Test fun craftIsNoOpWhenStatGated() {
        val r = Recipes.byId("craft_injector")!! // INT >= 6
        val before = char(mapOf("medkit" to 1, "circuit_board" to 1), int = 4)
        assertEquals(before, SpecialGame.craft(before, r))
    }
}
