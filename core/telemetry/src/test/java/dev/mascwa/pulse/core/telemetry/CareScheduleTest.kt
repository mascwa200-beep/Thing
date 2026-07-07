package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CareScheduleTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val T = 1_700_000_000_000L // a fixed "now"

    private fun ctx(
        hour: Int = 12,
        last: Map<CareCheckin, Long> = emptyMap(),
        lastMeal: Long = 0L,
        needs: LifeProfile = LifeProfile(),
    ) = CareContext(T, hour, last, lastMeal, needs)

    @Test fun waterIsDueOnItsRhythmDuringWakingHours() {
        val c = ctx(last = mapOf(CareCheckin.WATER to T - 3 * HOUR))
        assertTrue(CareCheckin.WATER in CareSchedule.due(c))
        // Quiet in the small hours (3am) — no lock-screen takeover while asleep.
        assertFalse(CareCheckin.WATER in CareSchedule.due(c.copy(hourOfDay = 3)))
        // Throttled if just asked.
        assertFalse(CareCheckin.WATER in CareSchedule.due(c, asked = mapOf(CareCheckin.WATER to T - 10 * MIN)))
    }

    @Test fun eatingMakesBrushComeDueAfterTheWait() {
        // Brushed an hour ago (so the twice-daily rule is inactive), then ate 40 min ago → brush is due again.
        val ate40 = ctx(last = mapOf(CareCheckin.BRUSH to T - HOUR), lastMeal = T - 40 * MIN)
        assertTrue(CareCheckin.BRUSH in CareSchedule.due(ate40))
        // But only 10 min after eating (< the 30-min wait) it's not yet due.
        val ate10 = ctx(last = mapOf(CareCheckin.BRUSH to T - HOUR), lastMeal = T - 10 * MIN)
        assertFalse(CareCheckin.BRUSH in CareSchedule.due(ate10))
    }

    @Test fun flossingFollowsABrush() {
        // Brushed 20 min ago, haven't flossed since → floss is due (right after the brush).
        val c = ctx(last = mapOf(CareCheckin.BRUSH to T - 20 * MIN, CareCheckin.FLOSS to T - 5 * DAY))
        assertTrue(CareCheckin.FLOSS in CareSchedule.due(c))
        // If you already flossed after that brush, it's not due from the brush.
        val flossed = ctx(last = mapOf(CareCheckin.BRUSH to T - 20 * MIN, CareCheckin.FLOSS to T - 5 * MIN))
        assertFalse(CareCheckin.FLOSS in CareSchedule.due(flossed))
    }

    @Test fun oralAndWaterOutrankTheRest() {
        val c = ctx(last = mapOf(CareCheckin.WATER to T - 3 * HOUR, CareCheckin.EAT to T - 6 * HOUR))
        val due = CareSchedule.due(c)
        assertTrue(CareCheckin.WATER in due && CareCheckin.EAT in due)
        assertTrue("water outranks eat", due.indexOf(CareCheckin.WATER) < due.indexOf(CareCheckin.EAT))
    }

    @Test fun aCriticalNeedJumpsTheQueue() {
        // Water + eat both due, but nourishment is critical → EAT escalates above water and is asked first.
        val c = ctx(
            last = mapOf(CareCheckin.WATER to T - 3 * HOUR, CareCheckin.EAT to T - 6 * HOUR),
            needs = LifeProfile(nourishment = 8),
        )
        assertEquals(CareCheckin.EAT, CareSchedule.next(c))
    }

    @Test fun restOnlyWhenEnergyIsLow() {
        assertFalse(CareCheckin.REST in CareSchedule.due(ctx()))
        assertTrue(CareCheckin.REST in CareSchedule.due(ctx(needs = LifeProfile(energy = 20))))
    }

    @Test fun nextIsNullWhenNothingIsDue() {
        // Everything freshly done, full needs, mid-morning → nothing due.
        val allFresh = CareCheckin.entries.associateWith { T - 1 * MIN }
        assertEquals(null, CareSchedule.next(ctx(last = allFresh)))
    }

    // ── verification (ensure you actually did it) ──

    private fun ev(a: RealActivity, conf: Float, at: Long = T - 2 * MIN) = ActivityEvidence(a, conf, at)

    @Test fun claimIsConfirmedWhenSensorsSawIt() {
        val evidence = listOf(ev(RealActivity.TOOTHBRUSH, 0.9f))
        assertEquals(
            ClaimVerdict.CONFIRMED,
            CareSchedule.verifyClaim(CareCheckin.BRUSH, evidence, T - CareSchedule.VERIFY_WINDOW_MS, sensingActive = true),
        )
        assertTrue(CareSchedule.credits(ClaimVerdict.CONFIRMED))
    }

    @Test fun claimIsBouncedWhenActiveSensorsSawNothing() {
        // Sensing is on, the window has no brushing evidence → a caught claim (NONE), which does not credit.
        val evidence = listOf(ev(RealActivity.EATING, 0.9f))
        val verdict = CareSchedule.verifyClaim(
            CareCheckin.BRUSH, evidence, T - CareSchedule.VERIFY_WINDOW_MS, sensingActive = true,
        )
        assertEquals(ClaimVerdict.NONE, verdict)
        assertFalse(CareSchedule.credits(verdict))
    }

    @Test fun claimIsTrustedWhenSensingIsOff() {
        // Camera/mic weren't watching → never a false accusation; provisional trust (WEAK, credits).
        val verdict = CareSchedule.verifyClaim(
            CareCheckin.BRUSH, emptyList(), T - CareSchedule.VERIFY_WINDOW_MS, sensingActive = false,
        )
        assertEquals(ClaimVerdict.WEAK, verdict)
        assertTrue(CareSchedule.credits(verdict))
    }

    @Test fun unsensableCheckinsAreAlwaysTrusted() {
        // Flossing/resting have no reliable sensor → trusted even with sensing on and no evidence.
        assertTrue(CareSchedule.verifyActivities(CareCheckin.FLOSS).isEmpty())
        val verdict = CareSchedule.verifyClaim(
            CareCheckin.FLOSS, emptyList(), T - CareSchedule.VERIFY_WINDOW_MS, sensingActive = true,
        )
        assertEquals(ClaimVerdict.WEAK, verdict)
        assertTrue(CareSchedule.credits(verdict))
    }

    @Test fun washIsCorroboratedByEitherShowerOrHandwash() {
        val since = T - CareSchedule.VERIFY_WINDOW_MS
        assertEquals(
            ClaimVerdict.CONFIRMED,
            CareSchedule.verifyClaim(CareCheckin.WASH, listOf(ev(RealActivity.HANDWASH, 0.8f)), since, true),
        )
        assertEquals(
            ClaimVerdict.CONFIRMED,
            CareSchedule.verifyClaim(CareCheckin.WASH, listOf(ev(RealActivity.SHOWER, 0.8f)), since, true),
        )
    }
}
