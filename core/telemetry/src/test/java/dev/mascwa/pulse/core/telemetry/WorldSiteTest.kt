package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the geo-gated [WorldSites] model. */
class WorldSiteTest {

    @Test fun typeForClassifiesKnownCategories() {
        assertEquals(SiteType.SETTLEMENT, WorldSites.typeFor("place=town"))
        assertEquals(SiteType.MEDIC, WorldSites.typeFor("pharmacy"))
        assertEquals(SiteType.FIXER, WorldSites.typeFor("hardware"))
        assertEquals(SiteType.BARKEEP, WorldSites.typeFor("pub"))
        assertEquals(SiteType.OUTPOST, WorldSites.typeFor("fuel"))
        assertEquals(SiteType.TRADER, WorldSites.typeFor("supermarket"))
        assertEquals(SiteType.TRIBE, WorldSites.typeFor("leisure=park"))
        assertEquals(SiteType.GANG_CAMP, WorldSites.typeFor("landuse=industrial"))
        assertEquals(SiteType.MONSTER_DEN, WorldSites.typeFor("natural=water"))
        assertEquals(SiteType.VAULT, WorldSites.typeFor("military=bunker"))
        assertEquals(SiteType.RUINS, WorldSites.typeFor("historic=ruins"))
        assertEquals(SiteType.RUINS, WorldSites.typeFor("something_unknown")) // default
    }

    @Test fun nameForIsDeterministicAndNonBlankForEveryType() {
        SiteType.entries.forEach { type ->
            for (seed in listOf(0, 1, 42, 12345, -7, -99999)) {
                val name = WorldSites.nameFor(type, seed)
                assertTrue("blank name for $type/$seed", name.isNotBlank())
                assertEquals("same seed must give the same name", name, WorldSites.nameFor(type, seed))
            }
        }
    }

    @Test fun vaultNamesStayInRange() {
        for (seed in -1000..1000) {
            val name = WorldSites.nameFor(SiteType.VAULT, seed)
            val n = name.removePrefix("Vault ").toInt()
            assertTrue("Vault number $n out of range", n in 10..99)
        }
    }

    @Test fun siteForIsStable() {
        val a = WorldSites.siteFor("poi_42", 40.0, -73.0, "landuse=industrial")
        val b = WorldSites.siteFor("poi_42", 40.0, -73.0, "landuse=industrial")
        assertEquals(a, b) // same inputs → same site (name doesn't churn between scans)
        assertEquals(SiteType.GANG_CAMP, a.type)
        assertEquals(40.0, a.lat, 0.0)
        assertTrue(a.name.isNotBlank())
    }

    @Test fun threatAndHostilityAreConsistent() {
        // Hostile sites are the dangerous tiers; safe/shop sites are threat 1 and never hostile.
        assertTrue(SiteType.MONSTER_DEN.threat > SiteType.SETTLEMENT.threat)
        assertTrue(SiteType.VAULT.threat >= SiteType.GANG_CAMP.threat)
        SiteType.entries.filter { it.hostile }.forEach { assertTrue("$it should be dangerous", it.threat >= 3) }
        listOf(SiteType.SETTLEMENT, SiteType.TRADER, SiteType.MEDIC, SiteType.FIXER, SiteType.BARKEEP, SiteType.OUTPOST)
            .forEach {
                assertFalse("$it must not be hostile", it.hostile)
                assertEquals(1, it.threat)
                assertNotNull("$it must run a shop", it.shopKind)
            }
    }

    @Test fun favoredStatsOnlyForEncounterSites() {
        // Hostile + exploration sites bias toward stats; pure trade stops don't.
        assertTrue(WorldSites.favoredStats(SiteType.GANG_CAMP).isNotEmpty())
        assertTrue(WorldSites.favoredStats(SiteType.MONSTER_DEN).contains(Special.ENDURANCE))
        assertTrue(WorldSites.favoredStats(SiteType.TRADER).isEmpty())
        assertTrue(WorldSites.spawnsEncounter(SiteType.GANG_CAMP))
        assertFalse(WorldSites.spawnsEncounter(SiteType.TRADER))
    }

    @Test fun introMentionsTheSiteName() {
        val site = WorldSites.siteFor("poi_x", 1.0, 2.0, "vault")
        assertTrue(WorldSites.intro(site).contains(site.name))
    }
}
