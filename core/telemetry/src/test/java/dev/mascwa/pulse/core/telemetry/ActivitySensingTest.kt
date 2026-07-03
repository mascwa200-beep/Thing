package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real-world activity detector + lie-catcher. Proves corroboration raises confidence, a brief water run
 * is a handwash not a shower, and [ActivitySensing.verifyClaim] confirms only what the sensors actually saw.
 */
class ActivitySensingTest {

    private fun snd(vararg s: String) = s.map { PerceptLabel(it, 0.9f) }
    private fun scn(vararg s: String) = s.map { PerceptLabel(it, 0.9f) }

    @Test fun sustainedWaterInBathroomAtHomeReadsAsAConfidentShower() {
        val ev = ActivitySensing.detect(
            soundLabels = snd("Water"), sceneLabels = scn("Bathroom"),
            waterRunningMs = 6 * 60_000L, atHome = true, hourOfDay = 7, nowMs = 1_000,
        )
        val shower = ev.first { it.activity == RealActivity.SHOWER }
        assertTrue("shower confidence should corroborate", shower.confidence >= ActivitySensing.CONFIRM_CONFIDENCE)
        assertEquals(CareNeed.HYGIENE, RealActivity.SHOWER.satisfies)
    }

    @Test fun briefWaterIsAHandwashNotAShower() {
        val ev = ActivitySensing.detect(snd("faucet"), emptyList(), waterRunningMs = 8_000L, atHome = true, hourOfDay = 12, nowMs = 5)
        assertTrue(ev.any { it.activity == RealActivity.HANDWASH })
        assertTrue(ev.none { it.activity == RealActivity.SHOWER })
    }

    @Test fun toiletFlushIsSensed() {
        val ev = ActivitySensing.detect(snd("Toilet flush"), emptyList(), 0, atHome = true, hourOfDay = 8, nowMs = 9)
        assertTrue(ev.any { it.activity == RealActivity.TOILET })
    }

    @Test fun eatingIsMoreConfidentInAKitchenAtMealtime() {
        val kitchen = ActivitySensing.detect(snd("Chewing"), scn("kitchen"), 0, atHome = true, hourOfDay = 12, nowMs = 1)
            .first { it.activity == RealActivity.EATING }.confidence
        val bare = ActivitySensing.detect(snd("Chewing"), emptyList(), 0, atHome = false, hourOfDay = 3, nowMs = 1)
            .first { it.activity == RealActivity.EATING }.confidence
        assertTrue("kitchen+home+mealtime should beat a bare chew", kitchen > bare)
    }

    @Test fun cameraBathroomSceneAloneIsWeakPresence() {
        val ev = ActivitySensing.detect(emptyList(), scn("bathroom"), 0, atHome = true, hourOfDay = 8, nowMs = 2)
        val toilet = ev.first { it.activity == RealActivity.TOILET }
        assertTrue(toilet.confidence < ActivitySensing.CONFIRM_CONFIDENCE)
    }

    @Test fun noSignalsYieldNoEvidence() {
        assertTrue(ActivitySensing.detect(snd("Speech", "Music"), scn("living room"), 0, atHome = true, hourOfDay = 20, nowMs = 1).isEmpty())
    }

    @Test fun verifyClaimConfirmsOnlyWhatSensorsSaw() {
        val log = listOf(
            ActivityEvidence(RealActivity.SHOWER, 0.85f, atMs = 1_000),
            ActivityEvidence(RealActivity.EATING, 0.7f, atMs = 2_000),
        )
        assertEquals(ClaimVerdict.CONFIRMED, ActivitySensing.verifyClaim(RealActivity.SHOWER, log, sinceMs = 0))
        // Claimed but never sensed → caught.
        assertEquals(ClaimVerdict.NONE, ActivitySensing.verifyClaim(RealActivity.TOOTHBRUSH, log, sinceMs = 0))
        // The shower was real, but before the window we're asking about → doesn't count.
        assertEquals(ClaimVerdict.NONE, ActivitySensing.verifyClaim(RealActivity.SHOWER, log, sinceMs = 1_500))
    }

    @Test fun weakEvidenceReadsAsWeakNotConfirmed() {
        val log = listOf(ActivityEvidence(RealActivity.DRINKING, 0.35f, atMs = 100))
        assertEquals(ClaimVerdict.WEAK, ActivitySensing.verifyClaim(RealActivity.DRINKING, log, sinceMs = 0))
    }
}
