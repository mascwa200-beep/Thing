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

    @Test fun theWeatherRowSaysWhatTheTemperatureDoesNotOnlyWhatItReads() {
        // The board posts a temperature every time. severeWeather covers storm codes; a 32 C day at
        // 75% humidity carries no storm code and is the more dangerous of the two.
        val s = BriefSignals(
            nowMs = 1_700_000_000_000L,
            tempNow = 32.0, tempUnit = "°C", conditionText = "Clear",
            tempC = 32.0, humidityPct = 75.0, windKmh = 5.0,
        )
        val row = UnifiedBriefComposer.compose(s)!!.rows.first { it.kind == BriefRowKind.WEATHER }
        assertTrue(row.text.contains("Feels 42°C — danger"))
        // And on an ordinary day the row is exactly what it was before this existed.
        val mild = s.copy(tempNow = 18.0, tempC = 18.0, humidityPct = 50.0, windKmh = 8.0)
        val mildRow = UnifiedBriefComposer.compose(mild)!!.rows.first { it.kind == BriefRowKind.WEATHER }
        assertTrue(!mildRow.text.contains("Feels"))
    }

    @Test fun aBlobWithoutTheCanonicalFieldsStillRendersTheRow() {
        // A cache written by a build before those fields existed decodes with them null, and the
        // row must degrade to its old content rather than disappear.
        val s = BriefSignals(nowMs = 1_700_000_000_000L, tempNow = 21.0, conditionText = "Cloudy")
        val row = UnifiedBriefComposer.compose(s)!!.rows.first { it.kind == BriefRowKind.WEATHER }
        assertTrue(row.text.contains("21°C now"))
        assertTrue(row.text.contains("Cloudy"))
    }

    // ---- ADVISORY: the Oracle's call to action, back on the board ----

    @Test fun advisoryRendersLastAndFitsWithoutDisplacingAnything() {
        // routine() has no ALERT, so its four rows plus an advisory come to exactly five — the
        // layout's capacity. Nothing is displaced; the advisory simply sits last.
        val b = UnifiedBriefComposer.compose(
            routine().copy(advisory = "Leave in 10 min — 4.2 km and rain at 08:40"),
        )!!
        assertEquals(
            listOf(
                BriefRowKind.NEWS, BriefRowKind.MARKETS, BriefRowKind.WEATHER,
                BriefRowKind.AGENDA, BriefRowKind.ADVISORY,
            ),
            b.rows.map { it.kind },
        )
        assertEquals("Leave in 10 min — 4.2 km and rain at 08:40", b.row(BriefRowKind.ADVISORY)!!.text)
    }

    @Test fun aSixthRowTakesMarketsSeatRatherThanOverflowing() {
        // ALERT + the four routine rows + ADVISORY is six. The layout has exactly five slots and the
        // renderer takes the first five — overflow would silently drop ADVISORY, which is last.
        val b = UnifiedBriefComposer.compose(
            routine().copy(reminderNow = "call the dentist", advisory = "Charge now — 12% left"),
        )!!
        assertEquals(
            listOf(
                BriefRowKind.ALERT, BriefRowKind.NEWS, BriefRowKind.WEATHER,
                BriefRowKind.AGENDA, BriefRowKind.ADVISORY,
            ),
            b.rows.map { it.kind },
        )
        assertNull(b.row(BriefRowKind.MARKETS))
    }

    @Test fun noAdvisoryMeansNoSixthRow() {
        // The everyday shape is five rows; the caller passes an advisory only when one is earned.
        assertNull(UnifiedBriefComposer.compose(routine())!!.row(BriefRowKind.ADVISORY))
        assertNull(UnifiedBriefComposer.compose(routine().copy(advisory = "   "))!!.row(BriefRowKind.ADVISORY))
    }

    @Test fun anAdvisoryOutranksTheNewsForTheCollapsedLine() {
        // The bar for passing one is high, so if it is here it beats reporting what merely happened.
        val b = UnifiedBriefComposer.compose(routine().copy(advisory = "Charge now — 12% and 3 stops today"))!!
        assertEquals("Charge now — 12% and 3 stops today", b.headline)
    }

    @Test fun anAlertStillOutranksAnAdvisory() {
        val b = UnifiedBriefComposer.compose(
            routine().copy(reminderNow = "call the dentist", advisory = "Charge now — 12% left"),
        )!!
        assertEquals("Reminder — call the dentist", b.headline)
        assertNotNull(b.row(BriefRowKind.ADVISORY))
    }

    @Test fun anAdvisoryAloneStillComposesABoard() {
        val b = UnifiedBriefComposer.compose(
            BriefSignals(nowMs = NOW, advisory = "Aurora likely tonight — look north after 22:00"),
        )!!
        assertEquals(listOf(BriefRowKind.ADVISORY), b.rows.map { it.kind })
        assertEquals("Aurora likely tonight — look north after 22:00", b.headline)
    }

    @Test fun anAdvisoryNeverRaisesTheAlertCondition() {
        // A suggestion, however well reasoned, is not an emergency. What buzzes is the notice chain.
        val b = UnifiedBriefComposer.compose(routine().copy(advisory = "Leave now — traffic on your route"))!!
        assertEquals(BriefUrgency.ROUTINE, b.urgency)
        assertNull(b.urgencyKey)
    }

    @Test fun aLongAdvisoryIsCappedWithAnEllipsis() {
        // ADVISORY_CAP is 110; cap() keeps 109 chars then appends the ellipsis, so 110 total.
        val long = "x".repeat(200)
        val text = UnifiedBriefComposer.compose(routine().copy(advisory = long))!!
            .row(BriefRowKind.ADVISORY)!!.text
        assertEquals(110, text.length)
        assertTrue(text.endsWith("…"))
    }
}
