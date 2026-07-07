package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeedSensingTest {

    private fun ev(a: RealActivity, conf: Float) = ActivityEvidence(a, conf, 0L)

    @Test fun everyCreditableActivityMapsToADistinctSensibleNeed() {
        assertEquals(NeedKind.HYGIENE, NeedSensing.needFor(RealActivity.SHOWER))
        assertEquals(NeedKind.HYGIENE, NeedSensing.needFor(RealActivity.HANDWASH))
        assertEquals(NeedKind.BRUSHING, NeedSensing.needFor(RealActivity.TOOTHBRUSH)) // distinct from generic hygiene
        assertEquals(NeedKind.NOURISHMENT, NeedSensing.needFor(RealActivity.EATING))
        assertEquals(NeedKind.HYDRATION, NeedSensing.needFor(RealActivity.DRINKING))
        // A bathroom trip isn't a tracked need.
        assertEquals(null, NeedSensing.needFor(RealActivity.TOILET))
    }

    @Test fun sensedNeedsCreditsOnlyConfidentEvidence() {
        val evidence = listOf(
            ev(RealActivity.DRINKING, 0.8f),        // confident → credited
            ev(RealActivity.EATING, 0.2f),          // too weak → dropped
            ev(RealActivity.TOOTHBRUSH, 0.65f),     // confident → credited
        )
        assertEquals(setOf(NeedKind.HYDRATION, NeedKind.BRUSHING), NeedSensing.sensedNeeds(evidence))
    }

    @Test fun sensedNeedsDedupesAndIgnoresNonNeeds() {
        val evidence = listOf(
            ev(RealActivity.SHOWER, 0.9f),
            ev(RealActivity.HANDWASH, 0.7f),   // both → HYGIENE, deduped to one
            ev(RealActivity.TOILET, 0.9f),     // maps to no need
        )
        assertEquals(setOf(NeedKind.HYGIENE), NeedSensing.sensedNeeds(evidence))
    }

    @Test fun sensedNeedsEmptyWhenNothingConfident() {
        assertTrue(NeedSensing.sensedNeeds(listOf(ev(RealActivity.DRINKING, 0.1f))).isEmpty())
        assertTrue(NeedSensing.sensedNeeds(emptyList()).isEmpty())
    }

    @Test fun creditableSetIsTheNonNullMappings() {
        assertTrue(RealActivity.DRINKING in NeedSensing.CREDITABLE)
        assertTrue(RealActivity.TOOTHBRUSH in NeedSensing.CREDITABLE)
        assertFalse(RealActivity.TOILET in NeedSensing.CREDITABLE)
        // Every creditable activity must actually resolve to a need.
        NeedSensing.CREDITABLE.forEach { assertTrue(NeedSensing.needFor(it) != null) }
    }
}
