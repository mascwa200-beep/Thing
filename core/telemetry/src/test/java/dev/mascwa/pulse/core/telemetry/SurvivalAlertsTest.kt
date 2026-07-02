package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurvivalAlertsTest {

    @Test fun healthyProfileFiresNothing() {
        assertTrue(SurvivalAlerts.evaluate(LifeProfile(), seed = 0).isEmpty()) // all needs default 100
    }

    @Test fun lowNeedFiresLowAlert() {
        val a = SurvivalAlerts.evaluate(LifeProfile(hydration = 25), seed = 0)
        assertEquals(1, a.size)
        assertEquals(SurvivalNeed.HYDRATION, a[0].need)
        assertEquals(AlertLevel.LOW, a[0].level)
        assertTrue(a[0].title.contains("HYDRATION"))
        assertTrue(a[0].title.contains("25%"))
        assertTrue(a[0].body.isNotBlank())
    }

    @Test fun criticalNeedFiresCriticalAlert() {
        val a = SurvivalAlerts.evaluate(LifeProfile(energy = 10), seed = 0)
        assertEquals(1, a.size)
        assertEquals(SurvivalNeed.ENERGY, a[0].need)
        assertEquals(AlertLevel.CRITICAL, a[0].level)
        assertTrue(a[0].title.contains("CRITICAL"))
    }

    @Test fun multipleNeedsEachFire() {
        val a = SurvivalAlerts.evaluate(
            LifeProfile(hydration = 28, nourishment = 12, energy = 100, hygiene = 20), seed = 3,
        )
        // hydration LOW, nourishment CRITICAL, hygiene LOW → 3 alerts; energy healthy → none.
        assertEquals(3, a.size)
        assertEquals(setOf(SurvivalNeed.HYDRATION, SurvivalNeed.NOURISHMENT, SurvivalNeed.HYGIENE), a.map { it.need }.toSet())
        assertEquals(AlertLevel.CRITICAL, a.first { it.need == SurvivalNeed.NOURISHMENT }.level)
    }

    @Test fun allFourNeedsCanFire() {
        val a = SurvivalAlerts.evaluate(LifeProfile(hydration = 5, nourishment = 5, energy = 5, hygiene = 5), seed = 7)
        assertEquals(4, a.size)
        assertTrue(a.all { it.level == AlertLevel.CRITICAL })
    }

    @Test fun seedIsDeterministicAndRotatesMessages() {
        val p = LifeProfile(hydration = 25)
        // Same seed → same body.
        assertEquals(SurvivalAlerts.evaluate(p, 4)[0].body, SurvivalAlerts.evaluate(p, 4)[0].body)
        // Across the seed space, more than one distinct message is reachable (the catalog rotates).
        val distinct = (0L until 20L).map { SurvivalAlerts.evaluate(p, it)[0].body }.toSet()
        assertTrue(distinct.size > 1)
    }

    @Test fun thresholdsMatchLifeStats() {
        // Exactly at NEED_LOW is still an alert; just above is not.
        assertEquals(1, SurvivalAlerts.evaluate(LifeProfile(hygiene = LifeStats.NEED_LOW), 0).size)
        assertTrue(SurvivalAlerts.evaluate(LifeProfile(hygiene = LifeStats.NEED_LOW + 1), 0).isEmpty())
    }
}
