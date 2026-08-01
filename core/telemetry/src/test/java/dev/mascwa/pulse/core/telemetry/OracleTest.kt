package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OracleTest {

    private val NOW = 1_700_000_000_000L
    private fun inMin(m: Int) = NOW + m * 60_000L

    private fun base(hour: Int = 12) = OracleSignals(nowMs = NOW, hourOfDay = hour, minuteOfDay = hour * 60)

    private fun List<Insight>.byId(id: String) = firstOrNull { it.id == id }

    @Test fun quietWhenNothingFiring() {
        val out = Oracle.divine(base())
        assertTrue(out.isEmpty())
        assertTrue(Oracle.briefing(out).contains("quiet"))
        assertNull(Oracle.focus(base()))
    }

    @Test fun emergencyIsCriticalAndRanksTop() {
        val s = base().copy(
            emergencyHeadline = "Major earthquake strikes the coast",
            batteryPct = 10, // also fires chargeNow
        )
        val out = Oracle.divine(s)
        val emg = out.byId("emergency")
        assertNotNull(emg)
        assertEquals(Urgency.CRITICAL, emg!!.urgency)
        assertEquals("emergency", out.first().id) // outranks everything
        assertTrue(Oracle.pushWorthy(out).any { it.id == "emergency" })
    }

    @Test fun leaveNowFiresWithTravelAndUrgency() {
        val s = base().copy(events = listOf(OracleEvent("Dentist", inMin(20), hasLocation = true, distanceM = 1000.0)))
        val ins = Oracle.divine(s).byId("leave_${"Dentist".hashCode()}")
        assertNotNull(ins)
        assertEquals(Urgency.URGENT, ins!!.urgency) // ~3 min to leave
        assertTrue(ins.title.startsWith("Leave"))
        assertEquals("nav", ins.actionRoute)
    }

    @Test fun leaveNowAddsUmbrellaWhenRain() {
        val s = base().copy(
            events = listOf(OracleEvent("Meeting", inMin(30), hasLocation = true, distanceM = 800.0)),
            precipChancePct = 70,
        )
        val ins = Oracle.divine(s).byId("leave_${"Meeting".hashCode()}")
        assertNotNull(ins)
        assertTrue(ins!!.detail.contains("umbrella"))
        assertTrue("weather" in ins.sources)
    }

    @Test fun chargeNowFiresOnLowBattery() {
        val ins = Oracle.divine(base(hour = 8).copy(batteryPct = 14)).byId("charge_low")
        assertNotNull(ins)
        assertEquals(Urgency.URGENT, ins!!.urgency) // <=15
        assertTrue(ins.title.contains("14%"))
    }

    @Test fun chargeNowSilentWhenChargingOrFull() {
        assertNull(Oracle.divine(base().copy(batteryPct = 14, charging = true)).byId("charge_low"))
        assertNull(Oracle.divine(base().copy(batteryPct = 80)).byId("charge_low"))
    }

    @Test fun hydrateEscalatesWithHeatAndMotion() {
        val calm = Oracle.divine(base().copy(needs = mapOf("HYDRATION" to 28))).byId("need_hydration")
        assertNotNull(calm)
        assertEquals(Urgency.NOTABLE, calm!!.urgency)
        val hotMoving = Oracle.divine(
            base().copy(needs = mapOf("HYDRATION" to 28), tempC = 32.0, movement = 0.2f),
        ).byId("need_hydration")
        assertEquals(Urgency.IMPORTANT, hotMoving!!.urgency)
        assertTrue(hotMoving.detail.contains("hot"))
    }

    @Test fun restFiresOnLowEnergy() {
        val ins = Oracle.divine(base(hour = 23).copy(needs = mapOf("ENERGY" to 10))).byId("need_energy")
        assertNotNull(ins)
        assertEquals(Urgency.URGENT, ins!!.urgency)
    }

    @Test fun weatherPrepNeedsToBeHeadingOut() {
        // Rain but stuck indoors with no outing → no nudge.
        assertNull(Oracle.divine(base().copy(precipChancePct = 80, setting = Setting.INDOOR)).byId("weather_rain"))
        // Rain + an event with a location soon → fires.
        val ins = Oracle.divine(
            base().copy(precipChancePct = 80, events = listOf(OracleEvent("Lunch", inMin(90), hasLocation = true, distanceM = 500.0))),
        ).byId("weather_rain")
        assertNotNull(ins)
    }

    @Test fun marketMoverPrefersWatchlist() {
        val s = base().copy(
            movers = listOf(
                OracleMover("Broad Index", 7.0, onWatchlist = false),
                OracleMover("Your Stock", 4.0, onWatchlist = true),
            ),
        )
        val ins = Oracle.divine(s).firstOrNull { it.id.startsWith("market_") }
        assertNotNull(ins)
        assertTrue(ins!!.title.contains("Your Stock")) // watchlist wins over the bigger broad mover
        assertTrue("profile" in ins.sources)
    }

    @Test fun auroraFiresAtNightOnHighKp() {
        val ins = Oracle.divine(base(hour = 23).copy(kpIndex = 7.0, precipChancePct = 10)).byId("aurora")
        assertNotNull(ins)
        assertEquals(Urgency.IMPORTANT, ins!!.urgency)
        assertTrue(ins.detail.contains("clear"))
        // Daytime with no astronomy interest → silent.
        assertNull(Oracle.divine(base(hour = 12).copy(kpIndex = 7.0)).byId("aurora"))
    }

    @Test fun focusMomentWhenSettledAndFree() {
        val s = base(hour = 14).copy(
            pendingTasks = listOf("File the report"),
            movement = 0.0f, setting = Setting.INDOOR, social = Social.ALONE,
        )
        val ins = Oracle.divine(s).byId("focus_task")
        assertNotNull(ins)
        assertTrue(ins!!.title.contains("File the report"))
        // In a vehicle → not a focus moment.
        assertNull(Oracle.divine(s.copy(setting = Setting.VEHICLE)).byId("focus_task"))
    }

    @Test fun divineRanksByUrgencyThenScore() {
        val s = base(hour = 9).copy(
            emergencyHeadline = "Breaking crisis",
            events = listOf(OracleEvent("Standup", inMin(18), hasLocation = true, distanceM = 900.0)),
            batteryPct = 12,
            habitualRoute = "markets", habitualLabel = "Markets",
        )
        val out = Oracle.divine(s)
        assertEquals("emergency", out.first().id)
        // The ambient habit prefetch must rank below the urgent items.
        val habitIdx = out.indexOfFirst { it.id == "habit_markets" }
        val chargeIdx = out.indexOfFirst { it.id == "charge_low" }
        assertTrue(habitIdx == -1 || chargeIdx == -1 || chargeIdx < habitIdx)
    }

    @Test fun pushWorthyOnlyUrgentOrAbove() {
        val s = base().copy(
            emergencyHeadline = "Disaster",             // CRITICAL
            habitualRoute = "news", habitualLabel = "News", // AMBIENT
        )
        val push = Oracle.pushWorthy(Oracle.divine(s))
        assertTrue(push.all { it.urgency.weight >= Urgency.URGENT.weight })
        assertTrue(push.any { it.id == "emergency" })
    }

    @Test fun limitCapsTheSurface() {
        val s = base(hour = 8).copy(
            emergencyHeadline = "X", batteryPct = 10, needs = mapOf("HYDRATION" to 10, "NOURISHMENT" to 10, "ENERGY" to 10),
            movers = listOf(OracleMover("A", 9.0)), kpIndex = 8.0, storageFreePct = 2,
        )
        assertTrue(Oracle.divine(s, limit = 3).size <= 3)
    }

    // ---- World Pulse (the live world feed) ----

    @Test fun worldPulseQuietWhenNothingWorthSaying() {
        assertNull(Oracle.worldPulse(base()))
    }

    @Test fun worldPulseLeadsWithEmergencyAndFusesSignals() {
        val s = base(hour = 20).copy(
            emergencyHeadline = "Storm makes landfall",
            movers = listOf(OracleMover("Oil", 3.4, onWatchlist = true)),
            kpIndex = 6.0,
            stepsToday = 8200,
        )
        val p = Oracle.worldPulse(s)
        assertNotNull(p)
        // The breaking headline leads (its emoji is stripped from the headline itself).
        assertTrue(p!!.headline.contains("Storm makes landfall"))
        // The feed body fuses multiple independent domains.
        assertTrue(p.lines.any { it.contains("Oil") && it.contains("yours") })
        assertTrue(p.lines.any { it.contains("Kp") })
        assertTrue(p.lines.any { it.contains("steps") })
        assertTrue(p.lines.size <= 5)
    }

    @Test fun worldPulseSurfacesLowNeedAndUpcomingEvent() {
        val s = base(hour = 13).copy(
            needs = mapOf("HYDRATION" to 20),
            events = listOf(OracleEvent("Team sync", inMin(45))),
        )
        val p = Oracle.worldPulse(s)
        assertNotNull(p)
        assertTrue(p!!.lines.any { it.contains("Hydration", ignoreCase = true) })
        assertTrue(p.lines.any { it.contains("Team sync") })
    }

    @Test fun worldPulseFallsBackToTopInsight() {
        // No global facts, but a real actionable insight exists → the pulse still has something to say.
        val s = base(hour = 8).copy(batteryPct = 12)
        val p = Oracle.worldPulse(s)
        assertNotNull(p)
        assertTrue(p!!.lines.isNotEmpty())
    }
}
