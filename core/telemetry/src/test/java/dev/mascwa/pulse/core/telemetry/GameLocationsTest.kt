package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the [GameLocations] core — category mapping, shop stock, NPC names, and conversations. */
class GameLocationsTest {

    private fun char(): Character = Character(stats = Special.entries.associateWith { 4 })

    @Test fun osmCategoriesMapToKinds() {
        assertEquals(LocationKind.TRADER, GameLocations.kindFor("supermarket"))
        assertEquals(LocationKind.TRADER, GameLocations.kindFor("convenience"))
        assertEquals(LocationKind.MEDIC, GameLocations.kindFor("pharmacy"))
        assertEquals(LocationKind.MEDIC, GameLocations.kindFor("hospital"))
        assertEquals(LocationKind.FIXER, GameLocations.kindFor("hardware"))
        assertEquals(LocationKind.FIXER, GameLocations.kindFor("electronics"))
        assertEquals(LocationKind.BARKEEP, GameLocations.kindFor("bar"))
        assertEquals(LocationKind.BARKEEP, GameLocations.kindFor("cafe"))
        assertEquals(LocationKind.OUTPOST, GameLocations.kindFor("fuel"))
        assertEquals(LocationKind.TRADER, GameLocations.kindFor("florist")) // unknown → default
    }

    @Test fun everyShopStockIdResolvesAndIsNonEmpty() {
        LocationKind.entries.forEach { kind ->
            val stock = GameLocations.stock(kind)
            assertTrue("$kind stock should be non-empty", stock.isNotEmpty())
            stock.forEach { id -> assertNotNull("Unknown stock item id '$id' for $kind", Items.byId(id)) }
        }
    }

    @Test fun npcNameIsStableAndHandlesNegativeSeeds() {
        // Deterministic for a given seed.
        assertEquals(GameLocations.npcName(LocationKind.TRADER, 3), GameLocations.npcName(LocationKind.TRADER, 3))
        // A negative seed (e.g. a hashCode) never crashes and stays in range.
        LocationKind.entries.forEach { kind ->
            val name = GameLocations.npcName(kind, -12345)
            assertTrue(name.isNotBlank())
        }
    }

    @Test fun greetingIsNonBlank() {
        LocationKind.entries.forEach { kind ->
            assertTrue(GameLocations.greeting(kind, "Test").isNotBlank())
        }
    }

    @Test fun conversationIsRepeatableCharismaEncounter() {
        val enc = GameLocations.conversation(LocationKind.TRADER, "Mick")
        assertTrue(enc.repeatable)
        assertEquals(1, enc.choices.size)
        assertEquals(Special.CHARISMA, enc.choices[0].stat)
    }

    @Test fun conversationSuccessGrantsReward() {
        // CHARISMA 4 + roll 7 = 11 >= DC 10 → success, not a crit (needs >= 16).
        val enc = GameLocations.conversation(LocationKind.TRADER, "Mick")
        val r = SpecialGame.resolve(char(), enc, 0, roll = 7)
        assertTrue(r.success)
        assertEquals(25 + 14, r.character.caps) // trader tip: +14 caps
        assertEquals(10, r.character.xp)         // +10 xp (no level-up)
    }

    @Test fun medicConversationDropsAnItem() {
        val enc = GameLocations.conversation(LocationKind.MEDIC, "Doc")
        val r = SpecialGame.resolve(char(), enc, 0, roll = 7)
        assertTrue(r.success)
        assertEquals(1, r.character.inventory["bandage"]) // freebie dressing
    }

    @Test fun actionsForOffersShopAndTalk() {
        val loc = GameLocation("poi_1", "Corner Store", LocationKind.TRADER, 40.0, -74.0)
        val actions = GameLocations.actionsFor(loc)
        assertEquals(2, actions.size)
        assertTrue(actions.any { it.second is NpcAction.Shop })
        assertTrue(actions.any { it.second is NpcAction.Talk })
        val shop = actions.map { it.second }.filterIsInstance<NpcAction.Shop>().first()
        assertTrue(shop.stock.isNotEmpty())
    }
}
