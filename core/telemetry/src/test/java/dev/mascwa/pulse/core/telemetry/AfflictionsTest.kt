package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfflictionsTest {

    private val H = 3_600_000L // one hour in ms

    /** Contract dehydration by leaving hydration critical for the full onset window. */
    private fun dehydrated(): AfflictionState =
        Afflictions.advance(AfflictionState(), LifeProfile(hydration = 10), Afflictions.ONSET_MS)

    @Test fun freshStateIsHealthyAndNeutral() {
        val s = AfflictionState()
        assertTrue(s.active.isEmpty())
        assertTrue(Afflictions.effects(s).isEmpty())
        Special.entries.forEach { assertEquals(0, Afflictions.statBonus(s, it)) }
        assertEquals("", Afflictions.summary(s))
    }

    @Test fun sustainedCriticalContractsTheAffliction() {
        val s = dehydrated()
        assertTrue(Affliction.DEHYDRATION in s.active)
        assertEquals(1f, Afflictions.incubation(s, Affliction.DEHYDRATION), 0.001f)
    }

    @Test fun justUnderOnsetHasNotTakenHold() {
        val s = Afflictions.advance(AfflictionState(), LifeProfile(hydration = 10), 5 * H)
        assertFalse(Affliction.DEHYDRATION in s.active)
        // ~5/6 of the way there.
        assertEquals(5f / 6f, Afflictions.incubation(s, Affliction.DEHYDRATION), 0.02f)
        assertTrue(Affliction.DEHYDRATION in Afflictions.incubating(s))
    }

    @Test fun incubationAccumulatesAcrossTicks() {
        var s = Afflictions.advance(AfflictionState(), LifeProfile(hydration = 10), 3 * H)
        assertFalse(Affliction.DEHYDRATION in s.active)
        s = Afflictions.advance(s, LifeProfile(hydration = 10), 3 * H)
        assertTrue(Affliction.DEHYDRATION in s.active)
    }

    @Test fun healthyNeedCuresOverTheCureWindow() {
        val sick = dehydrated()
        assertTrue(Affliction.DEHYDRATION in sick.active)
        val cured = Afflictions.advance(sick, LifeProfile(hydration = 100), Afflictions.CURE_MS)
        assertFalse(Affliction.DEHYDRATION in cured.active)
        assertTrue(cured.active.isEmpty())
    }

    @Test fun healingPausesInTheHoldBand() {
        val sick = dehydrated()
        // Hydration back above critical but not yet healthy (16..59) → the sickness just holds.
        val held = Afflictions.advance(sick, LifeProfile(hydration = 40), 5 * H)
        assertTrue(Affliction.DEHYDRATION in held.active)
        assertEquals(0f, Afflictions.cureProgress(held, Affliction.DEHYDRATION), 0.001f)
    }

    @Test fun partialCureThenRelapseRefills() {
        val sick = dehydrated()
        val half = Afflictions.advance(sick, LifeProfile(hydration = 100), 5 * H) // ~half the cure
        assertTrue(Affliction.DEHYDRATION in half.active) // hysteresis: still active until fully drained
        assertEquals(0.5f, Afflictions.cureProgress(half, Affliction.DEHYDRATION), 0.02f)
        val relapsed = Afflictions.advance(half, LifeProfile(hydration = 10), 5 * H)
        assertTrue(Affliction.DEHYDRATION in relapsed.active)
        assertEquals(1f, Afflictions.incubation(relapsed, Affliction.DEHYDRATION), 0.001f)
    }

    @Test fun activeAfflictionTaxesTheRightStats() {
        val s = dehydrated()
        assertEquals(-2, Afflictions.statBonus(s, Special.ENDURANCE))
        assertEquals(-1, Afflictions.statBonus(s, Special.PERCEPTION))
        assertEquals(0, Afflictions.statBonus(s, Special.LUCK))
    }

    @Test fun multipleAfflictionsStack() {
        val s = Afflictions.advance(
            AfflictionState(), LifeProfile(hydration = 5, energy = 5), Afflictions.ONSET_MS,
        )
        assertTrue(Affliction.DEHYDRATION in s.active)
        assertTrue(Affliction.EXHAUSTION in s.active)
        // PER is hit by both dehydration (−1) and exhaustion (−1).
        assertEquals(-2, Afflictions.statBonus(s, Special.PERCEPTION))
        assertTrue(Afflictions.summary(s).contains("Dehydration"))
        assertTrue(Afflictions.summary(s).contains("Exhaustion"))
    }

    @Test fun contractedAndCuredDiffs() {
        val fresh = AfflictionState()
        val sick = dehydrated()
        assertEquals(listOf(Affliction.DEHYDRATION), Afflictions.newlyContracted(fresh, sick))
        assertTrue(Afflictions.newlyCured(fresh, sick).isEmpty())
        val cured = Afflictions.advance(sick, LifeProfile(hydration = 100), Afflictions.CURE_MS)
        assertEquals(listOf(Affliction.DEHYDRATION), Afflictions.newlyCured(sick, cured))
    }

    @Test fun forNeedMapsEveryNeed() {
        // Every NeedKind must map to exactly one affliction (forNeed throws otherwise).
        NeedKind.entries.forEach { Affliction.forNeed(it) }
        assertEquals(Affliction.DEHYDRATION, Affliction.forNeed(NeedKind.HYDRATION))
        assertEquals(Affliction.MALNUTRITION, Affliction.forNeed(NeedKind.NOURISHMENT))
        assertEquals(Affliction.EXHAUSTION, Affliction.forNeed(NeedKind.ENERGY))
        assertEquals(Affliction.INFECTION, Affliction.forNeed(NeedKind.HYGIENE))
        assertEquals(Affliction.TOOTH_DECAY, Affliction.forNeed(NeedKind.BRUSHING))
        assertEquals(Affliction.GUM_DISEASE, Affliction.forNeed(NeedKind.FLOSSING))
    }

    @Test fun zeroElapsedIsNoOp() {
        val sick = dehydrated()
        assertEquals(sick, Afflictions.advance(sick, LifeProfile(hydration = 100), 0L))
    }
}
