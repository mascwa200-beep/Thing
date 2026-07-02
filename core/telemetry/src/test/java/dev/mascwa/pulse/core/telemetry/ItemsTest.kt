package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemsTest {

    @Test fun catalogGrewAndIdsAreUnique() {
        assertTrue("catalog should have expanded", Items.ALL.size >= 37)
        assertEquals("item ids must be unique", Items.ALL.size, Items.ALL.map { it.id }.toSet().size)
        assertTrue(Items.ALL.all { it.id.isNotBlank() && it.name.isNotBlank() })
    }

    @Test fun byIdResolvesEveryItemAndRejectsUnknown() {
        Items.ALL.forEach { assertEquals(it, Items.byId(it.id)) }
        assertNull(Items.byId("no_such_item"))
    }

    @Test fun everyShopStockIdIsARealItem() {
        LocationKind.entries.forEach { kind ->
            GameLocations.stock(kind).forEach { id ->
                assertNotNull("shop stock id '$id' (for $kind) must resolve to a real item", Items.byId(id))
            }
        }
    }

    @Test fun everyStatHasPassiveGear() {
        // The LUCK gear gap is now filled — every S.P.E.C.I.A.L. attribute has at least one GEAR item.
        Special.entries.forEach { s ->
            assertTrue("no GEAR grants $s", Items.ofKind(ItemKind.GEAR).any { it.statBonus == s })
        }
    }

    @Test fun higherTierItemsPresent() {
        assertEquals(60, Items.byId("surgeon_kit")?.healAmt)
        assertEquals(2, Items.byId("power_gauntlet")?.statBonusAmt)
        assertEquals(ItemKind.GEAR, Items.byId("fortune_idol")?.kind)
        assertEquals(4, Items.byId("titan_serum")?.statBonusAmt)
    }
}
