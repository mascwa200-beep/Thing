package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedBriefTest {

    private val NOW = 1_700_000_000_000L
    private fun inMin(m: Int) = NOW + m * 60_000L

    private fun UnifiedBrief.row(kind: BriefRowKind): BriefRow? = rows.firstOrNull { it.kind == kind }

    // A comfortably-populated everyday snapshot with no urgency anywhere.
    private fun routine() = BriefSignals(
        nowMs = NOW,
        topHeadline = "Parliament passes the budget", topSource = "Reuters",
        movers = listOf(OracleMover("NVDA", 4.8), OracleMover("TSLA", -3.2), OracleMover("AAPL", 3.1)),
        moveThresholdPct = 3.0,
        tempNow = 72.4, tempUnit = "°F", conditionText = "Cloudy", tempHi = 78.0, tempLo = 61.0,
        nextEventTitle = "Dentist", nextEventStartMs = inMin(100),
        pendingTaskCount = 3, pendingReminderCount = 2,
    )

    @Test fun emptySnapshotComposesToNull() {
        assertNull(UnifiedBriefComposer.compose(BriefSignals(nowMs = NOW)))
    }

    @Test fun routineBoardHasFourRowsInFixedOrderAndNoAlert() {
        val b = UnifiedBriefComposer.compose(routine())!!
        assertEquals(
            listOf(BriefRowKind.NEWS, BriefRowKind.MARKETS, BriefRowKind.WEATHER, BriefRowKind.AGENDA),
            b.rows.map { it.kind },
        )
        assertEquals(BriefUrgency.ROUTINE, b.urgency)
        assertNull(b.urgencyKey)
    }

    @Test fun collapsedHeadlineIsTheNewsWhenNoAlert() {
        val b = UnifiedBriefComposer.compose(routine())!!
        assertEquals("Parliament passes the budget — Reuters", b.headline)
    }

    @Test fun tempChipAlwaysPresentWithCurrentTemperature() {
        assertEquals("72°F", UnifiedBriefComposer.compose(routine())!!.tempLabel)
        assertNull(UnifiedBriefComposer.compose(routine().copy(tempNow = null))!!.tempLabel)
    }

    @Test fun weatherRowReadsPlainly() {
        val b = UnifiedBriefComposer.compose(routine())!!
        assertEquals("72°F now · Cloudy · High 78 / Low 61", b.row(BriefRowKind.WEATHER)!!.text)
    }

    @Test fun weatherRowAppendsRainUvAuroraSevereOnlyWhenNotable() {
        val s = routine().copy(precipPct = 40, uvIndex = 8.4, kpIndex = 6.0, severeWeather = true)
        val text = UnifiedBriefComposer.compose(s)!!.row(BriefRowKind.WEATHER)!!.text
        assertTrue(text.contains("Rain 40%"))
        assertTrue(text.contains("UV 8"))
        assertTrue(text.contains("Aurora watch, Kp 6"))
        assertTrue(text.contains("Severe weather"))
        // Below thresholds none of them render.
        val quiet = UnifiedBriefComposer.compose(routine().copy(precipPct = 10, uvIndex = 4.0, kpIndex = 2.0))!!
        assertEquals("72°F now · Cloudy · High 78 / Low 61", quiet.row(BriefRowKind.WEATHER)!!.text)
    }

    @Test fun marketsRowNamesTopTwoAndCountsTheRest() {
        val b = UnifiedBriefComposer.compose(routine())!!
        assertEquals("NVDA +4.8% · TSLA -3.2% (+1 more)", b.row(BriefRowKind.MARKETS)!!.text)
    }

    @Test fun marketsFallsBackToSingleBiggestUnderThreshold() {
        val s = routine().copy(movers = listOf(OracleMover("Gold", 1.4), OracleMover("Oil", -0.6)))
        assertEquals("Gold +1.4%", UnifiedBriefComposer.compose(s)!!.row(BriefRowKind.MARKETS)!!.text)
        // Nothing at even 1% → the row is omitted entirely.
        val flat = routine().copy(movers = listOf(OracleMover("Oil", -0.6)))
        assertNull(UnifiedBriefComposer.compose(flat)!!.row(BriefRowKind.MARKETS))
    }

    @Test fun agendaRowJoinsEventTasksReminders() {
        val b = UnifiedBriefComposer.compose(routine())!!
        assertEquals("Dentist in 1h 40m · 3 tasks open · 2 reminders set", b.row(BriefRowKind.AGENDA)!!.text)
    }

    @Test fun agendaLeadsWithTopTaskWhenNoEvent() {
        val s = routine().copy(
            nextEventTitle = null, nextEventStartMs = null,
            topTask = "Buy a torque wrench", pendingTaskCount = 1, pendingReminderCount = 0,
        )
        assertEquals("Buy a torque wrench · 1 task open", UnifiedBriefComposer.compose(s)!!.row(BriefRowKind.AGENDA)!!.text)
    }

    @Test fun reminderDueOutranksEverythingAsYellowAlert() {
        val s = routine().copy(
            reminderNow = "take the pizza out of the oven",
            emergencyHeadline = "Major earthquake strikes the coast", emergencyMajor = true,
        )
        val b = UnifiedBriefComposer.compose(s)!!
        assertEquals("Reminder — take the pizza out of the oven", b.row(BriefRowKind.ALERT)!!.text)
        assertEquals(b.row(BriefRowKind.ALERT)!!.text, b.headline)
        assertEquals(BriefUrgency.YELLOW, b.urgency)
        assertTrue(b.urgencyKey!!.startsWith("rem:"))
        // The un-consumed emergency still shows as the NEWS row.
        assertEquals("Major earthquake strikes the coast", b.row(BriefRowKind.NEWS)!!.text)
    }

    @Test fun majorEmergencyIsRedAlertAndNewsFallsBackToTopStory() {
        val s = routine().copy(emergencyHeadline = "Major earthquake strikes the coast", emergencyMajor = true)
        val b = UnifiedBriefComposer.compose(s)!!
        assertEquals(BriefUrgency.RED, b.urgency)
        assertTrue(b.urgencyKey!!.startsWith("news:"))
        assertEquals("Major earthquake strikes the coast", b.row(BriefRowKind.ALERT)!!.text)
        // NEWS doesn't duplicate the alert — it carries the ordinary top story instead.
        assertEquals("Parliament passes the budget — Reuters", b.row(BriefRowKind.NEWS)!!.text)
    }

    @Test fun nonMajorEmergencyIsJustTheNewsRowNotAnAlert() {
        val s = routine().copy(emergencyHeadline = "Wildfire smoke drifts over the valley", emergencyMajor = false)
        val b = UnifiedBriefComposer.compose(s)!!
        assertNull(b.row(BriefRowKind.ALERT))
        assertEquals(BriefUrgency.ROUTINE, b.urgency)
        assertEquals("Wildfire smoke drifts over the valley", b.row(BriefRowKind.NEWS)!!.text)
    }

    @Test fun criticalSecurityBeatsEmergencyNews() {
        val s = routine().copy(
            securityNotice = "Bootloader is unlocked", securityCritical = true,
            emergencyHeadline = "Major earthquake strikes the coast", emergencyMajor = true,
        )
        val b = UnifiedBriefComposer.compose(s)!!
        assertEquals("Bootloader is unlocked", b.row(BriefRowKind.ALERT)!!.text)
        assertEquals(BriefUrgency.RED, b.urgency)
        assertTrue(b.urgencyKey!!.startsWith("sec:"))
    }

    @Test fun safetyNoticeIsYellowWithStableKey() {
        val s = routine().copy(safetyNotice = "Earthquake 12 km away", safetyKey = "usgs:abc123")
        val b = UnifiedBriefComposer.compose(s)!!
        assertEquals(BriefUrgency.YELLOW, b.urgency)
        assertEquals("safety:usgs:abc123", b.urgencyKey)
    }

    @Test fun opsNoticeIsAlertRowButStaysRoutine() {
        val s = routine().copy(opsNotice = "Update ready — build 970")
        val b = UnifiedBriefComposer.compose(s)!!
        assertEquals("Update ready — build 970", b.row(BriefRowKind.ALERT)!!.text)
        assertEquals(BriefUrgency.ROUTINE, b.urgency)
        assertNull(b.urgencyKey)
    }

    @Test fun rowTogglesHideRows() {
        val s = routine().copy(showNews = false, showMarkets = false, showWeather = false, showAgenda = false)
        assertNull(UnifiedBriefComposer.compose(s)) // nothing left → null → caller cancels
        val onlyWeather = routine().copy(showNews = false, showMarkets = false, showAgenda = false)
        val b = UnifiedBriefComposer.compose(onlyWeather)!!
        assertEquals(listOf(BriefRowKind.WEATHER), b.rows.map { it.kind })
        assertEquals(b.row(BriefRowKind.WEATHER)!!.text, b.headline)
    }

    @Test fun headlineAndNewsAreCapped() {
        val long = "A".repeat(300)
        val b = UnifiedBriefComposer.compose(routine().copy(topHeadline = long, topSource = null))!!
        assertTrue(b.headline.length <= 90)
        assertTrue(b.row(BriefRowKind.NEWS)!!.text.length <= 80)
        assertTrue(b.headline.endsWith("…"))
    }

    @Test fun sameUrgentItemProducesTheSameKey() {
        val s = routine().copy(emergencyHeadline = "Major earthquake strikes the coast", emergencyMajor = true)
        val a = UnifiedBriefComposer.compose(s)!!
        val b = UnifiedBriefComposer.compose(s)!!
        assertEquals(a.urgencyKey, b.urgencyKey)
        assertFalse(a.urgencyKey.isNullOrBlank())
    }

    @Test fun eventStartingNowSaysNow() {
        val s = routine().copy(nextEventStartMs = NOW, pendingTaskCount = 0, pendingReminderCount = 0)
        assertEquals("Dentist now", UnifiedBriefComposer.compose(s)!!.row(BriefRowKind.AGENDA)!!.text)
    }
}
