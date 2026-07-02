package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarQuestsTest {

    private val now = 1_000_000_000_000L
    private fun ev(id: Long, startInMs: Long, durMs: Long = 3_600_000L, title: String = "Event $id") =
        CalEvent(id, title, now + startInMs, now + startInMs + durMs)

    @Test fun composeKeepsUpcomingWithinHorizonSoonestFirst() {
        val events = listOf(
            ev(1, 3 * 3_600_000L),          // in 3h
            ev(2, 30 * 60_000L),            // in 30m
            ev(3, -10 * 3_600_000L),        // ended 9h ago → dropped
            ev(4, 60L * 3_600_000L),        // in 60h (> 48h horizon) → dropped
        )
        val out = CalendarQuests.compose(events, now, max = 5)
        assertEquals(listOf(2L, 1L), out.map { it.id }) // soonest first, past + beyond-horizon dropped
    }

    @Test fun composeCapsToMax() {
        val events = (1L..6L).map { ev(it, it * 3_600_000L) }
        assertEquals(3, CalendarQuests.compose(events, now, max = 3).size)
    }

    @Test fun runningEventIsKeptAndUnderWay() {
        val running = ev(9, -30 * 60_000L, durMs = 2 * 3_600_000L) // started 30m ago, ends in 90m
        val out = CalendarQuests.compose(listOf(running), now)
        assertEquals(1, out.size)
        assertEquals("under way", out[0].countdown)
        assertTrue(out[0].imminent)
    }

    @Test fun imminentFlagWithinTheHour() {
        assertTrue(CalendarQuests.compose(listOf(ev(1, 40 * 60_000L)), now)[0].imminent)  // 40m
        assertFalse(CalendarQuests.compose(listOf(ev(2, 3 * 3_600_000L)), now)[0].imminent) // 3h
    }

    @Test fun countdownFormats() {
        assertEquals("under way", CalendarQuests.describeCountdown(-1))
        assertEquals("now", CalendarQuests.describeCountdown(30_000L))          // 30s
        assertEquals("in 45m", CalendarQuests.describeCountdown(45 * 60_000L))
        assertEquals("in 2h 15m", CalendarQuests.describeCountdown((2 * 60 + 15) * 60_000L))
        assertEquals("in 3h", CalendarQuests.describeCountdown(3 * 3_600_000L))
        assertEquals("in 2d 3h", CalendarQuests.describeCountdown((2 * 24 + 3) * 3_600_000L))
    }

    @Test fun briefingReflectsUrgency() {
        assertTrue(CalendarQuests.compose(listOf(ev(1, 3 * 3_600_000L, title = "Dentist")), now)[0]
            .briefing.contains("On the docket"))
        assertTrue(CalendarQuests.compose(listOf(ev(2, 20 * 60_000L, title = "Standup")), now)[0]
            .briefing.contains("Imminent"))
    }

    @Test fun blankTitleFallsBack() {
        val out = CalendarQuests.compose(listOf(ev(1, 3_600_000L, title = "")), now)
        assertEquals("Untitled", out[0].title)
    }
}
