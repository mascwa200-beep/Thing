package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePenaltiesTest {

    @Test fun aHealthyOperatorHasNoPenalties() {
        val p = LifeProfile() // all needs default to 100
        assertTrue(PhonePenalties.penalisedNeeds(p, emptySet()).isEmpty())
        assertTrue(PhonePenalties.locksFor(emptySet()).isEmpty())
        assertFalse(PhonePenalties.kioskEngaged(emptySet()))
    }

    @Test fun aCriticalNeedEngagesItsPenalty() {
        val p = LifeProfile(hydration = 10)
        val penalised = PhonePenalties.penalisedNeeds(p, emptySet())
        assertTrue(NeedKind.HYDRATION in penalised)
        assertEquals(setOf(NeedKind.HYDRATION), penalised)
    }

    @Test fun engageThresholdIsInclusiveAtCritical() {
        assertTrue(NeedKind.HYDRATION in PhonePenalties.penalisedNeeds(LifeProfile(hydration = PhonePenalties.ENGAGE_AT), emptySet()))
        // One point above critical, with no prior penalty, does not engage.
        assertFalse(NeedKind.HYDRATION in PhonePenalties.penalisedNeeds(LifeProfile(hydration = PhonePenalties.ENGAGE_AT + 1), emptySet()))
    }

    @Test fun hysteresisHoldsAPenaltyUntilTheNeedRecovers() {
        // Mid-band (between critical and healthy): holds whatever it was.
        val mid = LifeProfile(hydration = 30)
        assertFalse(NeedKind.HYDRATION in PhonePenalties.penalisedNeeds(mid, emptySet()))         // wasn't → stays clear
        assertTrue(NeedKind.HYDRATION in PhonePenalties.penalisedNeeds(mid, setOf(NeedKind.HYDRATION))) // was → holds
        // At healthy it releases even if it was penalised.
        assertFalse(NeedKind.HYDRATION in PhonePenalties.penalisedNeeds(LifeProfile(hydration = PhonePenalties.RELEASE_AT), setOf(NeedKind.HYDRATION)))
    }

    @Test fun locksMapFromPenalisedNeeds() {
        val locks = PhonePenalties.locksFor(setOf(NeedKind.HYDRATION, NeedKind.HYGIENE))
        assertEquals(setOf(PhonePenalties.PhoneLock.PAUSE_DISTRACTIONS, PhonePenalties.PhoneLock.DISABLE_CAMERA), locks)
    }

    @Test fun kioskEngagesWheneverAnythingIsPenalised() {
        assertFalse(PhonePenalties.kioskEngaged(emptySet()))
        assertTrue(PhonePenalties.kioskEngaged(setOf(NeedKind.ENERGY)))
    }

    @Test fun defaultMappingGivesEveryNeedItsOwnDistinctLock() {
        NeedKind.entries.forEach { assertTrue("no lock for $it", PhonePenalties.DEFAULT_MAPPING.containsKey(it)) }
        val locks = PhonePenalties.DEFAULT_MAPPING.values.toList()
        assertEquals("locks should be distinct per need", locks.size, locks.toSet().size)
    }

    @Test fun restoreHintNamesTheCareAction() {
        assertEquals("DRINK to get hydrated", PhonePenalties.restoreHint(NeedKind.HYDRATION))
        assertEquals("FLOSS to get flossed", PhonePenalties.restoreHint(NeedKind.FLOSSING))
    }

    @Test fun multipleNeedsPenaliseIndependently() {
        val p = LifeProfile(hydration = 5, nourishment = 8, energy = 100, hygiene = 100, brushing = 100, flossing = 100)
        val penalised = PhonePenalties.penalisedNeeds(p, emptySet())
        assertEquals(setOf(NeedKind.HYDRATION, NeedKind.NOURISHMENT), penalised)
        assertEquals(setOf(PhonePenalties.PhoneLock.PAUSE_DISTRACTIONS, PhonePenalties.PhoneLock.BLOCK_INSTALLS),
            PhonePenalties.locksFor(penalised))
    }
}
