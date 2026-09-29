package dev.mascwa.pulse.widget

import dev.mascwa.pulse.widget.Agenda.DayKind
import dev.mascwa.pulse.widget.Agenda.Event
import dev.mascwa.pulse.widget.Agenda.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Every instant here is computed by the JDK's own zone rules, never written out by hand, and the two
 * DST day lengths were cross-checked with python `zoneinfo` before these tests were written:
 * Europe/London 25 Oct 2026 is 25 h, 29 Mar 2026 is 23 h; America/Detroit 1 Nov 2026 is 25 h.
 */
class AgendaTest {

    private val detroit = ZoneId.of("America/Detroit")
    private val london = ZoneId.of("Europe/London")

    private fun offsetOf(zone: ZoneId): (Long) -> Long =
        { ms -> zone.rules.getOffset(Instant.ofEpochMilli(ms)).totalSeconds * 1000L }

    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** An all-day instance as the provider stores it: UTC midnight to UTC midnight, end exclusive. */
    private fun allDay(id: Long, title: String, first: LocalDate, lastInclusive: LocalDate) = Event(
        id = id, title = title,
        startMs = first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endMs = lastInclusive.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        allDay = true,
    )

    private fun timed(id: Long, title: String, start: Long, end: Long, location: String = "", calendar: String = "") =
        Event(id = id, title = title, startMs = start, endMs = end, allDay = false, location = location, calendarName = calendar)

    private fun epochDay(d: LocalDate) = d.toEpochDay()

    private fun d(h: Int, mi: Int = 0, day: Int = 29, mo: Int = 9) = at(detroit, 2026, mo, day, h, mi)

    /**
     * The two formatters as the widget builds them: the clock in the device zone, the day from an
     * epoch day's UTC midnight in UTC.
     */
    private val fmt = Agenda.Format(
        clock = { ms ->
            Instant.ofEpochMilli(ms).atZone(detroit).format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        },
        day = { epochDay ->
            LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)).uppercase()
        },
    )

    private fun plan(events: List<Event>, now: Long, horizon: Int = 14, max: Int = 60) =
        Agenda.plan(events, now, offsetOf(detroit), horizonDays = horizon, maxItems = max)

    // ---- the all-day UTC trap ----------------------------------------------------------------------

    @Test
    fun `an all-day event lands on its own date for somebody west of Greenwich`() {
        // 7:31 on 29 September in Detroit (UTC-4). The provider's all-day row for the 29th begins
        // at 29 Sep 00:00Z, which is 8 pm on the 28th locally — read through the zone it would be
        // yesterday's, and already over.
        val now = at(detroit, 2026, 9, 29, 7, 31)
        val today = allDay(1, "Holiday", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 29))
        val tomorrow = allDay(2, "Market", LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30))
        val plan = Agenda.plan(listOf(today, tomorrow), now, offsetOf(detroit), horizonDays = 14, maxItems = 60)

        assertEquals(2, plan.days.size)
        assertEquals(epochDay(LocalDate.of(2026, 9, 29)), plan.days[0].epochDay)
        assertEquals(DayKind.TODAY, plan.days[0].kind)
        assertEquals("Holiday", plan.days[0].entries.single().event.title)
        assertEquals(DayKind.TOMORROW, plan.days[1].kind)
        assertEquals("Market", plan.days[1].entries.single().event.title)
    }

    @Test
    fun `a one-day all-day event is still today at eleven at night`() {
        // By then the UTC end of the row (30 Sep 00:00Z = 8 pm local) has long passed. It ends by
        // DATE, not by instant.
        val now = at(detroit, 2026, 9, 29, 23, 0)
        val e = allDay(1, "Holiday", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 29))
        assertFalse(Agenda.ended(e, now, offsetOf(detroit)))
        assertEquals(1, Agenda.plan(listOf(e), now, offsetOf(detroit), 14, 60).shown)
    }

    @Test
    fun `a festival already under way shows once, today, with how far in and when it ends`() {
        val now = at(detroit, 2026, 9, 29, 7, 31)
        val fest = allDay(7, "ArtPrize", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 3))
        val plan = Agenda.plan(listOf(fest), now, offsetOf(detroit), 14, 60)

        val e = plan.days.single().entries.single()
        assertEquals(epochDay(LocalDate.of(2026, 9, 29)), e.day)
        assertEquals(DayKind.TODAY, plan.days.single().kind)
        assertEquals(14, e.dayIndex)   // 16 Sep is day 1, so 29 Sep is day 14
        assertEquals(18, e.daySpan)    // 16 Sep .. 3 Oct inclusive
        assertEquals(epochDay(LocalDate.of(2026, 10, 3)), e.lastDay)
        assertEquals(State.LATER, e.state)
    }

    @Test
    fun `a timed event is keyed on its local date, not its UTC one`() {
        // 10 pm on the 29th in Detroit is 02:00Z on the 30th. It belongs to the 29th.
        val late = timed(1, "Late call", d(22), d(23))
        val plan = plan(listOf(late), d(7, 31))
        assertEquals(epochDay(LocalDate.of(2026, 9, 29)), plan.days.single().epochDay)
        assertEquals(DayKind.TODAY, plan.days.single().kind)
    }

    // ---- NOW, NEXT, LATER --------------------------------------------------------------------------

    @Test
    fun `what is under way is NOW, the soonest start still ahead is the only NEXT, the rest are LATER`() {
        val now = d(10)
        val inIt = timed(1, "Inspections", d(9), d(11))
        val next = timed(2, "Training", d(12), d(13))
        val later = timed(3, "Review", d(15), d(16))
        val tomorrow = timed(4, "Standup", d(9, day = 30), d(10, day = 30))
        val holiday = allDay(5, "Holiday", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 29))
        val plan = plan(listOf(later, tomorrow, holiday, next, inIt), now)

        val byTitle = plan.all.associateBy { it.event.title }
        assertEquals(State.NOW, byTitle.getValue("Inspections").state)
        assertEquals(State.NEXT, byTitle.getValue("Training").state)
        assertEquals(State.LATER, byTitle.getValue("Review").state)
        assertEquals(State.LATER, byTitle.getValue("Standup").state)
        assertEquals(State.LATER, byTitle.getValue("Holiday").state)
        assertEquals(1, plan.all.count { it.state == State.NEXT })
        // All-day first within a day, then by start.
        assertEquals(listOf("Holiday", "Inspections", "Training", "Review"), plan.days[0].entries.map { it.event.title })
    }

    @Test
    fun `finished events are dropped, and an event started yesterday that is still running is today`() {
        val now = d(10)
        val over = timed(1, "Breakfast", d(7), d(8))
        val overnight = timed(2, "Night shift", d(20, day = 28), d(12))
        val plan = plan(listOf(over, overnight), now)

        val e = plan.all.single()
        assertEquals("Night shift", e.event.title)
        assertEquals(epochDay(LocalDate.of(2026, 9, 29)), e.day)
        assertEquals(State.NOW, e.state)
        assertEquals(2, e.dayIndex)
        assertEquals(2, e.daySpan)
    }

    @Test
    fun `overlapping timed events clash, back-to-back ones do not`() {
        val a = timed(1, "A", d(9), d(10))
        val b = timed(2, "B", d(9, 30), d(10, 30))
        val c = timed(3, "C", d(10, 30), d(11))
        val plan = plan(listOf(a, b, c), d(7))
        val byTitle = plan.all.associateBy { it.event.title }
        assertTrue(byTitle.getValue("A").clash)
        assertTrue(byTitle.getValue("B").clash)
        assertFalse(byTitle.getValue("C").clash)
    }

    @Test
    fun `the horizon includes its last day and nothing past it`() {
        // Today is the 29th, so fourteen days runs to 12 Oct inclusive.
        val lastIn = timed(1, "In", d(9, day = 12, mo = 10), d(10, day = 12, mo = 10))
        val firstOut = timed(2, "Out", d(9, day = 13, mo = 10), d(10, day = 13, mo = 10))
        val plan = plan(listOf(lastIn, firstOut), d(7), horizon = 14)
        assertEquals(listOf("In"), plan.all.map { it.event.title })
    }

    @Test
    fun `the cap keeps the soonest entries and counts what it left out`() {
        val events = (0 until 5).map { i -> timed(i.toLong(), "E$i", d(9 + i), d(9 + i, 30)) }
        val plan = plan(events.reversed(), d(7), max = 3)
        assertEquals(3, plan.shown)
        assertEquals(2, plan.omitted)
        assertEquals(listOf("E0", "E1", "E2"), plan.days.flatMap { it.entries }.map { it.event.title })
        assertEquals(5, plan.all.size)
    }

    // ---- up next -----------------------------------------------------------------------------------

    @Test
    fun `up next is what you are in the middle of, the one ending soonest, else what comes next`() {
        val now = d(10)
        val long = timed(1, "Long", d(9), d(12))
        val short = timed(2, "Short", d(9, 30), d(10, 30))
        val soon = timed(3, "Soon", d(11), d(12))
        assertEquals("Short", Agenda.upNext(plan(listOf(long, short, soon), now))?.event?.title)
        assertEquals("Soon", Agenda.upNext(plan(listOf(soon), now))?.event?.title)
        assertNull(Agenda.upNext(plan(emptyList(), now)))
    }

    @Test
    fun `up next cannot be hidden by the cap`() {
        // Three all-day events sort first and fill a cap of three; the timed NEXT is past it.
        val holidays = (1..3).map { allDay(it.toLong(), "H$it", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 29)) }
        val next = timed(9, "Training", d(11), d(12))
        val plan = plan(holidays + next, d(10), max = 3)
        assertTrue(plan.days.flatMap { it.entries }.none { it.event.title == "Training" })
        assertEquals("Training", Agenda.upNext(plan)?.event?.title)
    }

    // ---- boundaries --------------------------------------------------------------------------------

    @Test
    fun `the next boundary is the soonest future start or end, else local midnight`() {
        val now = d(10)
        val inIt = timed(1, "In", d(9), d(11))
        val next = timed(2, "Next", d(12), d(13))
        val holiday = allDay(3, "Holiday", LocalDate.of(2026, 9, 29), LocalDate.of(2026, 9, 29))
        assertEquals(d(11), Agenda.nextBoundaryMs(listOf(next, inIt, holiday), now, offsetOf(detroit)))
        assertEquals(d(0, day = 30), Agenda.nextBoundaryMs(listOf(holiday), now, offsetOf(detroit)))
    }

    @Test
    fun `midnight is found on the 25-hour day and the 23-hour day`() {
        // London falls back at 02:00 BST on 25 Oct 2026, so that day is 25 h long; the next midnight
        // after half past midnight is 26 Oct 00:00 GMT, which the offset NOW (BST) would misplace.
        val fallBackMorning = at(london, 2026, 10, 25, 0, 30)
        assertEquals(
            LocalDate.of(2026, 10, 26).atStartOfDay(london).toInstant().toEpochMilli(),
            Agenda.nextBoundaryMs(emptyList(), fallBackMorning, offsetOf(london)),
        )
        // And springs forward at 01:00 GMT on 29 Mar 2026: a 23 h day, next midnight in BST.
        val springMorning = at(london, 2026, 3, 29, 0, 30)
        assertEquals(
            LocalDate.of(2026, 3, 30).atStartOfDay(london).toInstant().toEpochMilli(),
            Agenda.nextBoundaryMs(emptyList(), springMorning, offsetOf(london)),
        )
        // The start of each transition day itself, too.
        for (day in listOf(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 3, 29), LocalDate.of(2026, 11, 1))) {
            val zone = if (day.monthValue == 11) detroit else london
            assertEquals(
                day.atStartOfDay(zone).toInstant().toEpochMilli(),
                Agenda.startOfLocalDay(day.toEpochDay(), offsetOf(zone)),
            )
        }
    }

    @Test
    fun `a day starts at its own midnight where the clock changes near midnight, or at it`() {
        // Found by sweeping every zone, not by thinking of these: in each case one of the two
        // candidates in startOfLocalDay is an hour out. The expected value is the JDK's own first
        // instant of the date, which for a day that has no 00:00 is the instant after the gap.
        val cases = listOf(
            ZoneId.of("Pacific/Auckland") to LocalDate.of(2026, 4, 5),   // needs the refined offset
            ZoneId.of("Australia/Sydney") to LocalDate.of(2026, 10, 4),  // needs the refined offset
            ZoneId.of("Asia/Beirut") to LocalDate.of(2026, 3, 29),       // needs the refined offset
            ZoneId.of("America/Havana") to LocalDate.of(2026, 3, 8),     // no 00:00: needs the day test
            ZoneId.of("America/Santiago") to LocalDate.of(2026, 9, 6),   // no 00:00: needs the day test
        )
        for ((zone, day) in cases) {
            assertEquals(
                "$zone $day",
                day.atStartOfDay(zone).toInstant().toEpochMilli(),
                Agenda.startOfLocalDay(day.toEpochDay(), offsetOf(zone)),
            )
        }
    }

    @Test
    fun `a local time before 1970 is the day before, not the day after`() {
        // 02:00Z on 1 Jan 1970, three hours west of Greenwich, is 11 pm on 31 Dec 1969: day -1.
        assertEquals(-1L, Agenda.localDay(2 * 3_600_000L, { -3 * 3_600_000L }))
    }

    // ---- words -------------------------------------------------------------------------------------

    @Test
    fun `a day heading says which day, the date, and how many`() {
        val plan = plan(
            listOf(
                timed(1, "A", d(9), d(10)), timed(2, "B", d(11), d(12)),
                timed(3, "C", d(9, day = 30), d(10, day = 30)),
                timed(4, "D", d(9, day = 2, mo = 10), d(10, day = 2, mo = 10)),
            ),
            d(7),
        )
        assertEquals(
            listOf("TODAY · TUE 29 SEP · 2 EVENTS", "TOMORROW · WED 30 SEP · 1 EVENT", "FRI 2 OCT · 1 EVENT"),
            plan.days.map { Agenda.dayHeading(it, fmt) },
        )
        assertEquals("TODAY · TUE 29 SEP · NOTHING LEFT", Agenda.clearTodayHeading(plan.today, fmt))
    }

    @Test
    fun `a festival under way says how far in and on which day it ends`() {
        val fest = allDay(7, "ArtPrize", LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 3))
        val e = plan(listOf(fest), d(7, 31)).all.single()
        assertEquals("ALL DAY  ArtPrize", Agenda.titleLine(e, fmt))
        assertEquals("day 14 of 18 · until SAT 3 OCT", Agenda.detailLine(e, fmt, withCalendar = false))
    }

    @Test
    fun `an overnight event names the day it ends, so it cannot read as ending before it began`() {
        val shift = timed(1, "Night shift", d(22), d(2, day = 30))
        val e = plan(listOf(shift), d(7, 31)).all.single()
        assertEquals("10:00 PM  Night shift", Agenda.titleLine(e, fmt))
        assertEquals("until WED 30 SEP 2:00 AM", Agenda.detailLine(e, fmt, withCalendar = false))
    }

    @Test
    fun `a same-day event gives only the clock it ends at, and a zero-length moment gives nothing`() {
        val meeting = plan(listOf(timed(1, "Meeting", d(9), d(11))), d(7)).all.single()
        assertEquals("until 11:00 AM", Agenda.detailLine(meeting, fmt, withCalendar = false))
        val moment = plan(listOf(timed(2, "Reminder", d(9), d(9))), d(7)).all.single()
        assertNull(Agenda.detailLine(moment, fmt, withCalendar = false))
    }

    @Test
    fun `the detail carries the place, the calendar only when asked, and a clash`() {
        val a = timed(1, "Training", d(9), d(11), location = "Gym", calendar = "Work")
        val b = timed(2, "Call", d(10), d(10, 30), calendar = "Home")
        val e = plan(listOf(a, b), d(7)).all.first { it.event.title == "Training" }
        assertEquals("until 11:00 AM · Gym · Work · CLASH", Agenda.detailLine(e, fmt, withCalendar = true))
        assertEquals("until 11:00 AM · Gym · CLASH", Agenda.detailLine(e, fmt, withCalendar = false))
    }

    @Test
    fun `one calendar is never named`() {
        val one = plan(listOf(timed(1, "A", d(9), d(10), calendar = "Work"), timed(2, "B", d(11), d(12), calendar = "Work")), d(7))
        assertEquals(emptySet<String>(), Agenda.otherCalendars(one))
        val blank = plan(listOf(timed(1, "A", d(9), d(10), calendar = "Work"), timed(2, "B", d(11), d(12), calendar = " ")), d(7))
        assertEquals(emptySet<String>(), Agenda.otherCalendars(blank))
        // No named calendar at all — a provider that reports none, or an empty fortnight. There is no
        // main calendar to pick, and picking one from nothing must not throw.
        val unnamed = plan(listOf(timed(1, "A", d(9), d(10)), timed(2, "B", d(11), d(12))), d(7))
        assertEquals(emptySet<String>(), Agenda.otherCalendars(unnamed))
        assertEquals(emptySet<String>(), Agenda.otherCalendars(plan(emptyList(), d(7))))
    }

    @Test
    fun `the main calendar goes unnamed and the exceptions are named`() {
        // The screenshot's shape: the owner's own calendar on nearly every event, one holiday elsewhere.
        val p = plan(
            listOf(
                timed(1, "Training", d(9), d(10), calendar = "me@example.com"),
                timed(2, "Dinner", d(17), d(18), calendar = "me@example.com"),
                timed(3, "Lights out", d(22), d(23), calendar = "me@example.com"),
                timed(4, "Equinox", d(12), d(13), calendar = "Holidays"),
            ),
            d(7),
        )
        assertEquals(setOf("Holidays"), Agenda.otherCalendars(p))
    }

    @Test
    fun `a tie is broken by name so the choice cannot flip between renders`() {
        // "Home" sorts first, so it is the main one and "Work" is the exception — whichever of the two
        // comes first in the day. The plan orders by time, so it is the TIMES that are swapped here.
        val workFirst = plan(listOf(timed(1, "A", d(9), d(10), calendar = "Work"), timed(2, "B", d(11), d(12), calendar = "Home")), d(7))
        assertEquals(setOf("Work"), Agenda.otherCalendars(workFirst))
        val homeFirst = plan(listOf(timed(1, "A", d(11), d(12), calendar = "Work"), timed(2, "B", d(9), d(10), calendar = "Home")), d(7))
        assertEquals(setOf("Work"), Agenda.otherCalendars(homeFirst))
    }

    @Test
    fun `an untitled event is called untitled rather than drawn as a bare time`() {
        val e = plan(listOf(timed(1, "   ", d(9), d(10))), d(7)).all.single()
        assertEquals("9:00 AM  (no title)", Agenda.titleLine(e, fmt))
    }

    @Test
    fun `the headline says NOW and when it ends, or NEXT and which day`() {
        val inspections = timed(1, "Inspections", d(7), d(7, 55), location = "Room 204")
        val training = timed(2, "Training", d(8), d(9), location = "Gym")
        val now = d(7, 31)

        val p1 = plan(listOf(inspections, training), now)
        assertEquals("NOW · Inspections · until 7:55 AM · Room 204", Agenda.upNextLine(Agenda.upNext(p1)!!, p1.today, fmt))

        val p2 = plan(listOf(training), now)
        assertEquals("NEXT 8:00 AM · Training · Gym", Agenda.upNextLine(Agenda.upNext(p2)!!, p2.today, fmt))

        val p3 = plan(listOf(timed(3, "Standup", d(9, day = 30), d(10, day = 30))), now)
        assertEquals("NEXT TOMORROW 9:00 AM · Standup", Agenda.upNextLine(Agenda.upNext(p3)!!, p3.today, fmt))

        val p4 = plan(listOf(timed(4, "Standup", d(9, day = 2, mo = 10), d(10, day = 2, mo = 10))), now)
        assertEquals("NEXT FRI 2 OCT 9:00 AM · Standup", Agenda.upNextLine(Agenda.upNext(p4)!!, p4.today, fmt))
    }
}
