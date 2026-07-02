package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the rarity-weighted [LootTable]. */
class LootTableTest {

    @Test fun lootablePoolExcludesQuestItems() {
        assertTrue(LootTable.LOOTABLE.isNotEmpty())
        assertTrue(LootTable.LOOTABLE.none { it.kind == ItemKind.QUEST })
    }

    @Test fun baseWeightStrictlyFavoursCommon() {
        // Rarer tiers must never out-weigh commoner ones.
        val w = (1..5).map { LootTable.baseWeight(it) }
        for (i in 0 until w.size - 1) assertTrue("rarity ${i + 1} should out-weigh ${i + 2}", w[i] > w[i + 1])
    }

    @Test fun luckLiftsRareTiersOnly() {
        val rare = Items.byId("fortune_idol")!!   // rarity 5
        val mid = Items.byId("grip_gloves")!!     // rarity 3
        val common = Items.byId("scrap_metal")!!  // rarity 1
        assertTrue("luck should raise a rarity-5 weight", LootTable.weight(rare, 10) > LootTable.weight(rare, 1))
        assertEquals("luck must not touch rarity-3", LootTable.weight(mid, 10), LootTable.weight(mid, 1))
        assertEquals("luck must not touch rarity-1", LootTable.weight(common, 10), LootTable.weight(common, 1))
    }

    @Test fun pickIsDeterministicAtTheEnds() {
        val pool = LootTable.LOOTABLE
        assertEquals("roll 0 → first item", pool.first(), LootTable.pick(5, 0.0, pool))
        assertEquals("roll ~1 → last item", pool.last(), LootTable.pick(5, 0.9999999, pool))
    }

    @Test fun pickAlwaysReturnsFromPool() {
        val pool = listOf(Items.byId("clean_water")!!, Items.byId("medkit")!!, Items.byId("fortune_idol")!!)
        for (r in listOf(0.0, 0.1, 0.5, 0.75, 0.99)) {
            assertTrue(LootTable.pick(3, r, pool) in pool)
        }
    }

    @Test fun scavengeMergesCountsById() {
        // Two identical rolls over a single-item pool → that item ×2.
        val pool = listOf(Items.byId("scrap_metal")!!)
        val bundle = LootTable.scavenge(5, listOf(0.2, 0.8), pool)
        assertEquals(mapOf("scrap_metal" to 2), bundle)
    }

    @Test fun scavengeYieldsRealItemIds() {
        val bundle = LootTable.scavenge(7, listOf(0.05, 0.35, 0.65))
        assertTrue(bundle.isNotEmpty())
        bundle.keys.forEach { id -> assertTrue("loot id '$id' must resolve", Items.byId(id) != null) }
        assertTrue(bundle.values.all { it > 0 })
    }
}
