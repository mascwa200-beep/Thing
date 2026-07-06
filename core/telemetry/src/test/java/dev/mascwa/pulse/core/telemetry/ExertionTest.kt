package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExertionTest {

    @Test fun easyFightIsCheap() {
        // DC 8, no gesture effort → base thirst + base fatigue, no food cost.
        assertEquals(ExertCost(hydration = 2, energy = 1, nourishment = 0), Exertion.engageCost(8, 0f))
    }

    @Test fun harderFightsCostMore() {
        // DC 14 (a HARD fight), flat-out gesture → a difficulty band + the intensity energy + food.
        assertEquals(ExertCost(hydration = 3, energy = 4, nourishment = 1), Exertion.engageCost(14, 1f))
    }

    @Test fun intensityScalesEnergyOnly() {
        assertEquals(ExertCost(2, 1, 0), Exertion.engageCost(8, 0f))
        assertEquals(ExertCost(2, 2, 0), Exertion.engageCost(8, 0.5f))
        assertEquals(ExertCost(2, 3, 0), Exertion.engageCost(8, 1f))
    }

    @Test fun difficultyBandStacks() {
        // DC 20 → band 2 (+2 hyd, +2 en) + half-intensity (+1 en) + hard food.
        assertEquals(ExertCost(hydration = 4, energy = 4, nourishment = 1), Exertion.engageCost(20, 0.5f))
    }

    @Test fun intensityIsClampedAndOnlyEnergy() {
        // Out-of-range intensity clamps; never adds hydration/nourishment.
        assertEquals(Exertion.engageCost(8, 1f), Exertion.engageCost(8, 5f))
    }

    @Test fun ventureAndScavengeConstants() {
        assertEquals(ExertCost(energy = 1), Exertion.ventureCost())
        assertEquals(ExertCost(hydration = 3, energy = 4), Exertion.scavengeCost())
    }

    @Test fun walkAKilometreCostsWholePoints() {
        val (cost, carry) = Exertion.travelCost(TransportMode.WALK, 500.0, ExertCarry())
        assertEquals(ExertCost(hydration = 3, energy = 3), cost)
        assertEquals(ExertCarry(), carry)
    }

    @Test fun travelCarryAccumulatesSubUnitSteps() {
        // 100 m walked → 0.6 of a point, no whole point yet, remainder carried.
        val (c1, carry1) = Exertion.travelCost(TransportMode.WALK, 100.0, ExertCarry())
        assertTrue(c1.isEmpty)
        assertEquals(0.6, carry1.hyd, 1e-9)
        // Another 100 m on top of the carry → exactly one point, remainder 0.2.
        val (c2, carry2) = Exertion.travelCost(TransportMode.WALK, 100.0, carry1)
        assertEquals(ExertCost(hydration = 1, energy = 1), c2)
        assertEquals(0.2, carry2.hyd, 1e-9)
    }

    @Test fun runningAlsoBurnsFood() {
        val (cost, _) = Exertion.travelCost(TransportMode.RUN, 1000.0, ExertCarry())
        assertEquals(ExertCost(hydration = 12, energy = 15, nourishment = 4), cost)
    }

    @Test fun drivingAndStandingStillCostNothing() {
        assertTrue(Exertion.travelCost(TransportMode.DRIVE, 5000.0, ExertCarry()).first.isEmpty)
        assertTrue(Exertion.travelCost(TransportMode.STILL, 5000.0, ExertCarry()).first.isEmpty)
    }

    @Test fun nonPositiveDistanceIsANoOp() {
        val carry = ExertCarry(hyd = 0.4)
        val (cost, out) = Exertion.travelCost(TransportMode.WALK, -50.0, carry)
        assertTrue(cost.isEmpty)
        assertEquals(carry, out)
    }

    @Test fun applyDrainsNeedsClampedAndLeavesHygiene() {
        val p = LifeProfile(hydration = 100, energy = 100, nourishment = 100, hygiene = 77)
        val after = Exertion.apply(p, ExertCost(3, 4, 1))
        assertEquals(97, after.hydration)
        assertEquals(96, after.energy)
        assertEquals(99, after.nourishment)
        assertEquals(77, after.hygiene) // exertion never touches hygiene
    }

    @Test fun applyClampsAtZero() {
        val after = Exertion.apply(LifeProfile(hydration = 2, energy = 1), ExertCost(9, 9, 0))
        assertEquals(0, after.hydration)
        assertEquals(0, after.energy)
    }

    @Test fun exertionNeverBreaksBlankNeutrality() {
        // Draining a fresh (all-100) profile a little leaves it healthy → still no life effects.
        val after = Exertion.apply(LifeProfile(), Exertion.engageCost(10, 0.5f))
        assertTrue(LifeStats.effects(after).isEmpty())
    }

    @Test fun costPlusAndFlags() {
        assertEquals(ExertCost(5, 5, 1), ExertCost(2, 1, 0) + ExertCost(3, 4, 1))
        assertTrue(ExertCost().isEmpty)
        assertFalse(ExertCost(1, 0, 0).isEmpty)
        assertEquals("−3 HYD −4 EN", ExertCost(3, 4, 0).describe())
        assertEquals("", ExertCost().describe())
    }
}
