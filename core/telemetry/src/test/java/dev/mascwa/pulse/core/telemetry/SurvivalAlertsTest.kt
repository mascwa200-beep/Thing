package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // A need at or below the FADING cutoff now warrants a (gentle → dire) check-in; above it, silence.
        assertEquals(1, SurvivalAlerts.evaluate(LifeProfile(hygiene = LifeStats.NEED_FADING), 0).size)
        assertTrue(SurvivalAlerts.evaluate(LifeProfile(hygiene = LifeStats.NEED_FADING + 1), 0).isEmpty())
        // Exactly at NEED_LOW is at least a LOW alert (past the gentle NOTICE band).
        assertEquals(AlertLevel.LOW, SurvivalAlerts.evaluate(LifeProfile(hygiene = LifeStats.NEED_LOW), 0)[0].level)
    }

    @Test fun fourEscalatingBands() {
        assertEquals(AlertLevel.NOTICE, SurvivalAlerts.bandFor(40))
        assertEquals(AlertLevel.NOTICE, SurvivalAlerts.bandFor(45))
        assertEquals(AlertLevel.LOW, SurvivalAlerts.bandFor(30))
        assertEquals(AlertLevel.LOW, SurvivalAlerts.bandFor(25))
        assertEquals(AlertLevel.URGENT, SurvivalAlerts.bandFor(24))
        assertEquals(AlertLevel.URGENT, SurvivalAlerts.bandFor(16))
        assertEquals(AlertLevel.CRITICAL, SurvivalAlerts.bandFor(15))
        assertEquals(AlertLevel.CRITICAL, SurvivalAlerts.bandFor(0))
        assertNull(SurvivalAlerts.bandFor(46))
        assertNull(SurvivalAlerts.bandFor(100))
    }

    @Test fun noticeBandFiresGentlyWithNoStakesYet() {
        val a = SurvivalAlerts.evaluate(LifeProfile(hydration = 40), seed = 0)
        assertEquals(1, a.size)
        assertEquals(AlertLevel.NOTICE, a[0].level)
        assertTrue(a[0].body.isNotBlank())
        // At NOTICE the need isn't taxing any stat yet, so there are no stakes to show.
        assertTrue(a[0].stakes.isBlank())
        assertTrue(a[0].advice.isNotBlank())
    }

    @Test fun pressingBandsCarryStatStakes() {
        // URGENT hydration (20) taxes END and STR → the stakes string names both.
        val a = SurvivalAlerts.alertFor(SurvivalNeed.HYDRATION, 20, seed = 0)!!
        assertEquals(AlertLevel.URGENT, a.level)
        assertTrue(a.stakes.contains("END"))
        assertTrue(a.stakes.contains("STR"))
    }

    @Test fun everyBandHasAWideCatalog() {
        // Each (need, band) rotates through many distinct messages across the seed space.
        SurvivalNeed.entries.forEach { need ->
            listOf(45, 28, 20, 8).forEach { value ->
                val bodies = (0L until 30L).map { SurvivalAlerts.alertFor(need, value, it)!!.body }.toSet()
                assertTrue("${need} @ $value should rotate", bodies.size >= 5)
            }
        }
    }

    @Test fun recoveryIsDeterministicAndThemed() {
        val r = SurvivalAlerts.recovery(SurvivalNeed.HYDRATION, seed = 2)
        assertTrue(r.title.contains("HYDRATION"))
        assertTrue(r.title.contains("RESTORED"))
        assertTrue(r.body.isNotBlank())
        assertEquals(r.body, SurvivalAlerts.recovery(SurvivalNeed.HYDRATION, 2).body)
        // Different needs give different confirmations.
        val e = SurvivalAlerts.recovery(SurvivalNeed.ENERGY, seed = 2)
        assertTrue(e.title.contains("ENERGY"))
    }
}
