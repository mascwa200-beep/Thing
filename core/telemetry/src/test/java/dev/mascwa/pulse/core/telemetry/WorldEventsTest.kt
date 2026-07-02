package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the deterministic [WorldEvents] daily situation. */
class WorldEventsTest {

    @Test fun catalogIsValidAndUnique() {
        assertTrue(WorldEvents.ALL.isNotEmpty())
        assertEquals(WorldEvents.ALL.size, WorldEvents.ALL.map { it.id }.toSet().size) // unique ids
        assertTrue(WorldEvents.ALL.all { it.id.isNotBlank() && it.name.isNotBlank() && it.desc.isNotBlank() })
        assertNotNull(WorldEvents.byId("radstorm"))
        assertNull(WorldEvents.byId("nope"))
    }

    @Test fun eventForIsDeterministicAndInCatalog() {
        for (day in 1..40) {
            val e = WorldEvents.eventFor(day)
            assertEquals("same day → same event", e, WorldEvents.eventFor(day))
            assertTrue("event must be in the catalog", e in WorldEvents.ALL)
        }
    }

    @Test fun everyEventAppearsWithinACycleWithNoImmediateRepeat() {
        val n = WorldEvents.ALL.size
        val cycle = (1..n).map { WorldEvents.eventFor(it).id }
        assertEquals("each event appears exactly once per cycle", n, cycle.toSet().size)
        // No day repeats the previous day's event.
        for (day in 2..(n * 3)) {
            assertTrue(WorldEvents.eventFor(day) != WorldEvents.eventFor(day - 1))
        }
    }

    @Test fun modifiersAreSane() {
        WorldEvents.ALL.forEach { e ->
            assertTrue("caps modifier in a sane band", e.capsWinPct in -50..50)
            assertTrue("shop modifier in a sane band", e.shopPct in -50..50)
            e.favored.forEach { assertTrue(it in Special.entries) }
        }
        // At least one event favours stats, one moves caps, and one moves shop prices — the mechanic does something.
        assertTrue(WorldEvents.ALL.any { it.favored.isNotEmpty() })
        assertTrue(WorldEvents.ALL.any { it.capsWinPct != 0 })
        assertTrue(WorldEvents.ALL.any { it.shopPct != 0 })
    }

    @Test fun shopPctBendsThePriceConsistently() {
        val item = Items.byId("medkit")!! // value 40
        val c = Character(stats = Special.entries.associateWith { 4 }) // no reputation → base = value
        val base = SpecialGame.shopPrice(item, c, LocationKind.MEDIC, 0)
        assertTrue(SpecialGame.shopPrice(item, c, LocationKind.MEDIC, -20) < base) // Market Fair discount
        assertTrue(SpecialGame.shopPrice(item, c, LocationKind.MEDIC, 15) > base)  // Gloom surcharge
        assertTrue("price never drops below 1", SpecialGame.shopPrice(item, c, LocationKind.MEDIC, -100) >= 1)
    }

    @Test fun negativeAndZeroDaysStayInBounds() {
        // day is clamped by floor-mod, so 0 / negatives never crash or index out of range.
        assertTrue(WorldEvents.eventFor(0) in WorldEvents.ALL)
        assertTrue(WorldEvents.eventFor(-5) in WorldEvents.ALL)
    }
}
