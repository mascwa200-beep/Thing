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

    @Test fun weatherPrepNeedsToBeHeadingOut() {
        // Rain but nothing indicates you're heading out → no nudge.
        assertNull(Oracle.divine(base().copy(precipChancePct = 80)).byId("weather_rain"))
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
            movement = 0.0f,
        )
        val ins = Oracle.divine(s).byId("focus_task")
        assertNotNull(ins)
        assertTrue(ins!!.title.contains("File the report"))
        // Moving around → not a focus moment.
        assertNull(Oracle.divine(s.copy(movement = 0.5f)).byId("focus_task"))
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
            emergencyHeadline = "X", batteryPct = 10,
            movers = listOf(OracleMover("A", 9.0)), kpIndex = 8.0, storageFreePct = 2,
        )
        assertTrue(Oracle.divine(s, limit = 3).size <= 3)
    }

    // ---- Sensorium-fed rules ----

    @Test fun stormFrontFiresOnlyOnThePlungingSignal() {
        val calm = base()
        assertTrue(Oracle.divine(calm).none { it.id == "storm_front" })
        val plunging = base().copy(pressureFallingFast = true)
        val insight = Oracle.divine(plunging).first { it.id == "storm_front" }
        assertEquals(Urgency.IMPORTANT, insight.urgency)
        assertTrue(insight.sources.contains("sensorium"))
    }

    @Test fun envAnomalySurfacesTheLearnedNormalDeviation() {
        val s = base().copy(envAnomaly = "noise unusually loud for 03:00 on a weekday")
        val insight = Oracle.divine(s).first { it.id == "env_anomaly" }
        assertEquals(Urgency.NOTABLE, insight.urgency)
        assertTrue(insight.detail.contains("unusually loud"))
        assertTrue(Oracle.divine(base()).none { it.id == "env_anomaly" })
    }

    @Test fun windDownCanActuallyFireNowThatAwayFromHomeIsFed() {
        // The rule was dead while awayFromHome was never populated (null != false was always true).
        val s = base(hour = 23).copy(awayFromHome = false)
        assertTrue(Oracle.divine(s).any { it.id == "wind_down" })
        assertTrue(Oracle.divine(base(hour = 23).copy(awayFromHome = null)).none { it.id == "wind_down" })
    }

    // ---- the comfort-core rules -------------------------------------------------------------

    @Test fun heatStressReadsHumidityNotJustTheThermometer() {
        // 32 C at 30% is a hot day and nothing more; the same 32 at 75% is a different problem,
        // and the whole point of the rule is that the thermometer cannot tell them apart.
        val dry = base(hour = 14).copy(tempC = 32.0, humidityPct = 30.0, awayFromHome = true)
        val humid = base(hour = 14).copy(tempC = 32.0, humidityPct = 75.0, awayFromHome = true)
        assertTrue(Oracle.divine(dry).none { it.id == "heat_stress" })
        val hot = Oracle.divine(humid).byId("heat_stress")
        assertNotNull(hot)
        assertTrue(hot!!.detail.contains("Humidity is doing this"))
    }

    @Test fun heatStressStaysQuietIndoorsOutsideTheHoursThatBite() {
        val s = base(hour = 21).copy(tempC = 34.0, humidityPct = 75.0, awayFromHome = false)
        assertTrue(Oracle.divine(s).none { it.id == "heat_stress" })
        // Out of the house at the same moment, it fires.
        assertTrue(Oracle.divine(s.copy(awayFromHome = true)).any { it.id == "heat_stress" })
    }

    @Test fun onlyTheWorstHeatBandEarnsAPush() {
        // Values computed from the shipped regression rather than guessed: 30 C at 60% is an
        // apparent 32.8 (extreme caution), 32 at 75% is 42.3 (danger), and 41 at 70% saturates the
        // chart (extreme danger).
        fun urgencyAt(t: Double, rh: Double) = Oracle
            .divine(base(hour = 14).copy(tempC = t, humidityPct = rh, awayFromHome = true))
            .byId("heat_stress")?.urgency
        assertEquals(Urgency.NOTABLE, urgencyAt(30.0, 60.0))
        assertEquals(Urgency.IMPORTANT, urgencyAt(32.0, 75.0))
        assertEquals(Urgency.URGENT, urgencyAt(41.0, 70.0))
        // Only the top band is worth interrupting someone for.
        val extreme = Oracle.divine(base(hour = 14).copy(tempC = 41.0, humidityPct = 70.0, awayFromHome = true))
        assertTrue(Oracle.pushWorthy(extreme).any { it.id == "heat_stress" })
    }

    @Test fun windChillOnlyFiresWhereTheFormulaIsDefined() {
        // The JAG/TI regression is defined at or below 10 C in moving air. A breezy spring
        // afternoon must not be able to trip this.
        val spring = base().copy(tempC = 16.0, windKmh = 40.0, awayFromHome = true)
        assertTrue(Oracle.divine(spring).none { it.id == "wind_chill" })
        val winter = base().copy(tempC = -8.0, windKmh = 40.0, awayFromHome = true)
        val bite = Oracle.divine(winter).byId("wind_chill")
        assertNotNull(bite)
        assertTrue(bite!!.title.contains("in the wind"))
    }

    @Test fun windChillNeedsYouToActuallyBeGoingOutside() {
        val indoors = base().copy(tempC = -8.0, windKmh = 40.0, awayFromHome = false)
        assertTrue(Oracle.divine(indoors).none { it.id == "wind_chill" })
        val meeting = indoors.copy(
            events = listOf(OracleEvent("Site visit", inMin(90), hasLocation = true)),
        )
        assertTrue(Oracle.divine(meeting).any { it.id == "wind_chill" })
    }

    @Test fun gustsFireOnThePeakNotTheAverage() {
        // 30 km/h mean with 70 gusts is the case worth a word; the same mean with no gust spread
        // is an ordinary windy day.
        val gusty = base().copy(windKmh = 30.0, gustKmh = 70.0)
        assertTrue(Oracle.divine(gusty).any { it.id == "gusts" })
        assertTrue(Oracle.divine(base().copy(windKmh = 30.0, gustKmh = 34.0)).none { it.id == "gusts" })
        // A strong mean with a proportionate gust is still below the actionable bar.
        assertTrue(Oracle.divine(base().copy(windKmh = 40.0, gustKmh = 50.0)).none { it.id == "gusts" })
    }

    @Test fun theColdNightWarningArrivesWhileItIsStillUseful() {
        // Everything it asks for -- cover the plants, allow time for the windscreen -- happens
        // before bed, so a dawn firing would be too late to act on.
        assertTrue(Oracle.divine(base(hour = 20).copy(overnightLowC = 1.0)).any { it.id == "cold_night" })
        assertTrue(Oracle.divine(base(hour = 7).copy(overnightLowC = 1.0)).none { it.id == "cold_night" })
        assertTrue(Oracle.divine(base(hour = 20).copy(overnightLowC = 9.0)).none { it.id == "cold_night" })
    }

    @Test fun theColdNightWarningDoesNotClaimFrostItCannotKnow() {
        // frostPossible() needs the dew point and wind AT THE TIME the frost would form; the
        // snapshot carries this evening's. So the wording states the low and says frost is
        // possible -- it must not assert that frost will happen.
        val text = Oracle.divine(base(hour = 20).copy(overnightLowC = 0.0)).byId("cold_night")!!
        assertTrue(text.detail.contains("Frost is possible"))
        assertTrue(!text.title.contains("Frost"))
    }

    @Test fun fogFiresWhenTheAirIsNearSaturationAndOnlyAroundDawn() {
        val saturated = base(hour = 6).copy(tempC = 9.0, dewPointC = 8.0)
        assertTrue(Oracle.divine(saturated).any { it.id == "fog" })
        // Same air in the afternoon: the rule is about when fog forms, not when it is possible.
        assertTrue(Oracle.divine(saturated.copy(hourOfDay = 15)).none { it.id == "fog" })
        // A dry morning.
        assertTrue(Oracle.divine(base(hour = 6).copy(tempC = 9.0, dewPointC = 1.0)).none { it.id == "fog" })
    }

    // ---- study --------------------------------------------------------------------------------------

    @Test fun aRealReviewQueueIsOfferedWhenYouAreSettledAndAwake() {
        val settled = base(hour = 14).copy(reviewsDue = 6)
        assertTrue(Oracle.divine(settled).any { it.id == "study_due" })
        // One or two due is not news — the app is not worth opening for it.
        assertTrue(Oracle.divine(base(hour = 14).copy(reviewsDue = 2)).none { it.id == "study_due" })
        // Mid-walk is not a moment to answer questions.
        assertTrue(Oracle.divine(settled.copy(movement = 0.4f)).none { it.id == "study_due" })
        // Nor is the middle of the night.
        assertTrue(Oracle.divine(settled.copy(hourOfDay = 2)).none { it.id == "study_due" })
    }

    /**
     * ⚠️ The rule that most easily becomes nagging, so each gate is asserted separately. Raising a
     * streak "at risk" at nine in the morning would be inventing urgency about something with fourteen
     * hours left to happen.
     */
    @Test fun aStreakIsOnlyAtRiskWhenItIsBothWorthSavingAndNearlyTooLate() {
        val evening = base(hour = 20).copy(studyStreakDays = 9, studiedToday = false)
        assertTrue(Oracle.divine(evening).any { it.id == "study_streak" })
        // Already done today — nothing is at risk.
        assertTrue(Oracle.divine(evening.copy(studiedToday = true)).none { it.id == "study_streak" })
        // Morning: hours left, no urgency to manufacture.
        assertTrue(Oracle.divine(evening.copy(hourOfDay = 9)).none { it.id == "study_streak" })
        // A streak of two is not worth a prompt.
        assertTrue(Oracle.divine(evening.copy(studyStreakDays = 2)).none { it.id == "study_streak" })
        // Past bedtime, sleep beats the guilt prompt.
        assertTrue(Oracle.divine(evening.copy(hourOfDay = 23)).none { it.id == "study_streak" })
    }

    @Test fun theSubjectGoingWorstIsNamedButNeverInterrupts() {
        val weak = base().copy(shakyGuideTitle = "Knots & Cordage", shakyGuideDetail = "3 of 11 right")
        val hit = Oracle.divine(weak).byId("study_weak")
        assertNotNull(hit)
        assertTrue(hit!!.title.contains("Knots & Cordage"))
        assertEquals(Urgency.AMBIENT, hit.urgency)
        // Ambient never warrants a push — this is worth knowing, not worth interrupting for.
        assertTrue(Oracle.pushWorthy(listOf(hit)).isEmpty())
    }

    @Test fun everyNewRuleStaysSilentOnAnEmptySnapshot() {
        // The whole contract of this engine: a missing signal means the rule does not fire, never
        // that it fires with a made-up default.
        assertTrue(Oracle.divine(base()).isEmpty())
        assertTrue(Oracle.divine(base(hour = 20)).isEmpty())
        assertTrue(Oracle.divine(base(hour = 6)).isEmpty())
        // Someone who has never studied must hear nothing at all from the study domain — including at
        // the evening hour the streak rule watches, and while settled, when the queue rule is looking.
        assertTrue(Oracle.divine(base(hour = 21).copy(movement = 0f)).isEmpty())
    }

}
