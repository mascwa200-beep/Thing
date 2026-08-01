package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimalHabitatsTest {

    private fun animalsAt(lat: Double, lon: Double) =
        AnimalHabitats.habitatFor(lat, lon).animals.map { it.id }

    @Test fun continentBoxesClassifyMajorCities() {
        assertEquals(Continent.NORTH_AMERICA, AnimalHabitats.continentFor(40.7, -74.0))   // New York
        assertEquals(Continent.SOUTH_AMERICA, AnimalHabitats.continentFor(-23.5, -46.6))  // São Paulo
        assertEquals(Continent.EUROPE, AnimalHabitats.continentFor(51.5, -0.1))           // London
        assertEquals(Continent.AFRICA, AnimalHabitats.continentFor(-1.3, 36.8))           // Nairobi
        assertEquals(Continent.ASIA, AnimalHabitats.continentFor(35.7, 139.7))            // Tokyo
        assertEquals(Continent.OCEANIA, AnimalHabitats.continentFor(-33.9, 151.2))        // Sydney
        assertEquals(Continent.ANTARCTICA, AnimalHabitats.continentFor(-75.0, 0.0))
    }

    @Test fun biomeHeuristicsReadKnownRegions() {
        assertEquals(Biome.DESERT, AnimalHabitats.biomeFor(23.0, 13.0))           // Sahara
        assertEquals(Biome.TROPICAL_RAINFOREST, AnimalHabitats.biomeFor(-3.0, -60.0)) // Amazon
        assertEquals(Biome.POLAR, AnimalHabitats.biomeFor(80.0, -40.0))           // High Arctic
        assertEquals(Biome.SAVANNA, AnimalHabitats.biomeFor(-1.3, 36.8))          // equatorial East Africa
        assertEquals(Biome.BOREAL_FOREST, AnimalHabitats.biomeFor(60.0, 100.0))   // Siberia
    }

    @Test fun africanSavannaHasLionsAndElephants() {
        val ids = animalsAt(-1.3, 36.8) // Nairobi region
        assertTrue("lion" in ids)
        assertTrue("elephant" in ids)
    }

    @Test fun northAmericanForestHasABear() {
        val ids = animalsAt(44.0, -72.0) // New England temperate forest
        assertTrue(ids.any { it.contains("bear") })
    }

    @Test fun australiaHasItsSnakes() {
        val ids = animalsAt(-33.9, 151.2) // Sydney
        assertTrue("eastern_brown_snake" in ids || "funnel_web_spider" in ids)
    }

    @Test fun hostilesLeadTheOrdering() {
        val habitat = AnimalHabitats.habitatFor(-1.3, 36.8)
        // The list is sorted hostiles-first then by danger — the top entry must be a hostile if any exist.
        if (habitat.hostiles.isNotEmpty()) assertTrue(habitat.animals.first().hostile)
    }

    @Test fun everyFaunaIdResolvesInTheCatalog() {
        // Walk every continent×biome combination; every referenced animal id must exist (typo guard).
        for (c in Continent.entries) for (b in Biome.entries) {
            for (id in AnimalCatalog.faunaFor(b, c)) {
                assertTrue("missing animal id: $id", AnimalCatalog.ANIMALS.containsKey(id))
            }
        }
    }

    @Test fun everyAnimalHasSaneFields() {
        for (a in AnimalCatalog.ANIMALS.values) {
            assertTrue("danger range: ${a.id}", a.danger in 0..4)
            assertTrue("identify blank: ${a.id}", a.identify.isNotBlank())
            assertTrue("action blank: ${a.id}", a.ifEncountered.isNotBlank())
        }
    }

    @Test fun classificationIsDeterministic() {
        assertEquals(AnimalHabitats.habitatFor(48.85, 2.35), AnimalHabitats.habitatFor(48.85, 2.35))
    }

    @Test fun dangerLevelReflectsThePeakThreat() {
        // Africa savanna (lions etc.) should read SEVERE; a calm region shouldn't.
        assertEquals(DangerLevel.SEVERE, AnimalHabitats.habitatFor(-1.3, 36.8).dangerLevel())
    }
}
