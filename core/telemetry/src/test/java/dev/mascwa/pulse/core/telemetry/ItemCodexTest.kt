package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the completionist [ItemCodex]. */
class ItemCodexTest {

    @Test fun totalMatchesCatalog() {
        assertEquals(Items.ALL.size, ItemCodex.TOTAL)
        assertTrue(ItemCodex.TOTAL >= 37)
    }

    @Test fun emptySetIsZeroProgressGreenhorn() {
        assertEquals(0, ItemCodex.found(emptySet()))
        assertEquals(0f, ItemCodex.completion(emptySet()), 0.0001f)
        assertEquals("Greenhorn", ItemCodex.rank(emptySet()))
        assertEquals(ItemCodex.TOTAL, ItemCodex.undiscovered(emptySet()).size)
        assertTrue(ItemCodex.discoveredItems(emptySet()).isEmpty())
    }

    @Test fun fullSetIsCompleteArchivist() {
        val all = Items.ALL.map { it.id }.toSet()
        assertEquals(ItemCodex.TOTAL, ItemCodex.found(all))
        assertEquals(1f, ItemCodex.completion(all), 0.0001f)
        assertEquals("Archivist", ItemCodex.rank(all))
        assertTrue(ItemCodex.undiscovered(all).isEmpty())
    }

    @Test fun unknownIdsAreIgnored() {
        // A stale id (a catalog item removed later) must never inflate the count past the real total.
        val padded = setOf("clean_water", "not_a_real_item", "also_fake")
        assertEquals(1, ItemCodex.found(padded))
        assertTrue(ItemCodex.found(padded) <= ItemCodex.TOTAL)
    }

    @Test fun discoveredAndUndiscoveredPartitionTheCatalog() {
        val some = setOf("clean_water", "medkit", "fortune_idol")
        val disc = ItemCodex.discoveredItems(some)
        val undisc = ItemCodex.undiscovered(some)
        assertEquals(3, disc.size)
        assertEquals(ItemCodex.TOTAL - 3, undisc.size)
        assertTrue(disc.none { it in undisc }) // disjoint
        assertEquals(ItemCodex.TOTAL, disc.size + undisc.size) // cover the whole catalog
    }

    @Test fun byKindSumsToTheCatalog() {
        val some = Items.ofKind(ItemKind.AID).take(2).map { it.id }.toSet()
        val breakdown = ItemCodex.byKind(some)
        // Every kind's total matches the catalog; the found counts sum to the discovered size.
        val totalItems = breakdown.values.sumOf { it.second }
        val totalFound = breakdown.values.sumOf { it.first }
        assertEquals(ItemCodex.TOTAL, totalItems)
        assertEquals(2, totalFound)
        val aid = breakdown[ItemKind.AID]!!
        assertEquals(2, aid.first)
        assertFalse(aid.second < 2)
    }

    @Test fun rankClimbsWithCompletion() {
        val ids = Items.ALL.map { it.id }
        fun rankFor(n: Int) = ItemCodex.rank(ids.take(n).toSet())
        assertEquals("Greenhorn", rankFor(0))
        assertEquals("Picker", rankFor(1))
        assertEquals("Scavenger", rankFor((ItemCodex.TOTAL * 0.25f).toInt() + 1))
        assertEquals("Archivist", rankFor(ItemCodex.TOTAL))
    }
}
