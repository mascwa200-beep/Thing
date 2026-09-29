package dev.mascwa.pulse.widget

/**
 * The widget's fortnight: which events to draw, on which day, in which order, and in what words.
 *
 * ## Why this is a pure object
 *
 * Everything here is arithmetic over instants, and every mistake in it is invisible until a
 * particular person is in a particular zone on a particular night — the kind of defect only a test
 * can hold. So it takes no clock and no zone: `nowMs` is passed in, and the zone arrives as a
 * function, `offsetAt(ms)` (`TimeZone.getOffset` on the device), which is what makes every day
 * boundary here correct across a clock change. Same pattern as the day-ahead core. No Android
 * import, so an ordinary JVM test exercises it — the `WidgetDiagnostics` precedent.
 *
 * ⚠️ It lives in `:app`, not `:core:telemetry`, deliberately. Only the phone's widget reads a
 * calendar, and a `core/telemetry` change fires four workflows — republishing the desktop MSI, the
 * nutrition app and the star map for a class none of them can call.
 *
 * ## The trap this file exists for
 *
 * ⚠️ **The calendar provider stores an all-day instance as UTC midnight to UTC midnight.** Read
 * through the device zone, the 29th's all-day row begins at 8 pm on the 28th for anyone at UTC−4 —
 * so every all-day event lands on the day before, and a one-day event reads as over by 8 pm. An
 * all-day instance is therefore keyed on the UTC DATE of its start and ends by date, never by
 * instant. A timed event is keyed on the local date of its start.
 */
object Agenda {

    const val DAY_MS = 86_400_000L

    /** One calendar instance, as the widget needs it. [colorArgb] is null when the provider has none. */
    data class Event(
        val id: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean,
        val location: String = "",
        val calendarName: String = "",
        val colorArgb: Int? = null,
    )

    /** NOW: under way. NEXT: the one soonest timed start still ahead. LATER: everything else. */
    enum class State { NOW, NEXT, LATER }

    enum class DayKind { TODAY, TOMORROW, LATER }

    /**
     * One event where the list draws it.
     *
     * @property day the epoch day it is listed under — its first day, or today for something already
     *   under way, so a festival that began a fortnight ago appears once, today.
     * @property dayIndex which day of its span [day] is, from 1.
     * @property daySpan how many days it covers.
     * @property lastDay the epoch day it ends on.
     * @property clash a timed event overlapping another timed event that day. Back-to-back is not a clash.
     */
    data class Entry(
        val day: Long,
        val event: Event,
        val state: State,
        val dayIndex: Int,
        val daySpan: Int,
        val lastDay: Long,
        val clash: Boolean,
    )

    data class Day(val epochDay: Long, val kind: DayKind, val entries: List<Entry>)

    /**
     * @property today the epoch day of `nowMs`, locally.
     * @property days the days that have something in them, within the cap.
     * @property shown how many entries [days] holds.
     * @property omitted how many the cap left out — the widget says so rather than stopping silently.
     * @property all every entry, uncapped. Whatever the headline needs is read from here, so the cap
     *   can never hide it.
     */
    data class Plan(
        val today: Long,
        val days: List<Day>,
        val shown: Int,
        val omitted: Int,
        val all: List<Entry>,
    )

    /**
     * The local epoch day of an instant.
     *
     * ⚠️ `floorDiv`, not `/`. Division truncates toward zero, so a local time before 1970 — a
     * negative number — would be filed under the day AFTER it.
     */
    fun localDay(ms: Long, offsetAt: (Long) -> Long): Long = Math.floorDiv(ms + offsetAt(ms), DAY_MS)

    /**
     * The first instant of a local epoch day.
     *
     * ⚠️ The offset has to be the one in force AT that midnight, and neither instant to hand is
     * guaranteed to have it. So two candidates: [guess], local midnight read with the offset at
     * UTC midnight, and [refined], read with the offset at the guess — and the answer is the
     * EARLIEST of them that actually falls on the day. Measured with python `zoneinfo` over every
     * zone for 2025–2027 (545,310 zone-days), each half is load-bearing and the rule is exact:
     *  - [refined] is needed where a clock changes between local and UTC midnight — Auckland,
     *    Sydney, Santiago and Beirut on their change days; [guess] alone is an hour out there.
     *  - The day test is needed where the clock springs forward AT midnight (Havana, Santiago in
     *    September): local 00:00 does not exist, the day begins at 01:00, and [refined] alone lands
     *    in the day before — 9 of those 545,310 days, which is how a rule looks right until it is not.
     */
    fun startOfLocalDay(epochDay: Long, offsetAt: (Long) -> Long): Long {
        val utcMidnight = epochDay * DAY_MS
        val guess = utcMidnight - offsetAt(utcMidnight)
        val refined = utcMidnight - offsetAt(guess)
        return listOf(refined, guess).filter { localDay(it, offsetAt) == epochDay }.minOrNull() ?: refined
    }

    /** The epoch day an event starts on. See the file's note for why all-day is keyed on UTC. */
    private fun firstDayOf(e: Event, offsetAt: (Long) -> Long): Long =
        if (e.allDay) Math.floorDiv(e.startMs, DAY_MS) else localDay(e.startMs, offsetAt)

    /**
     * The epoch day an event ends on. The provider's end is exclusive, so the last day is the one
     * holding the millisecond before it — an event ending at midnight ended the day before.
     */
    private fun lastDayOf(e: Event, offsetAt: (Long) -> Long): Long = when {
        e.endMs <= e.startMs -> firstDayOf(e, offsetAt)
        e.allDay -> Math.floorDiv(e.endMs - 1, DAY_MS)
        else -> localDay(e.endMs - 1, offsetAt)
    }

    /**
     * Whether an event is over. An all-day event ends by DATE: the UTC instant its row ends at has
     * passed by 8 pm for somebody at UTC−4, and it is still their holiday at eleven.
     */
    fun ended(e: Event, nowMs: Long, offsetAt: (Long) -> Long): Boolean =
        if (e.allDay) lastDayOf(e, offsetAt) < localDay(nowMs, offsetAt) else e.endMs <= nowMs

    private class Placed(val day: Long, val e: Event, val first: Long, val last: Long)

    /**
     * Lay out [events] over [horizonDays] days from today, keeping at most [maxItems] entries.
     *
     * Within a day, all-day events come first, then by start. The cap keeps the soonest entries and
     * counts the rest in [Plan.omitted].
     */
    fun plan(
        events: List<Event>,
        nowMs: Long,
        offsetAt: (Long) -> Long,
        horizonDays: Int = 14,
        maxItems: Int = 60,
    ): Plan {
        val today = localDay(nowMs, offsetAt)
        val lastVisible = today + maxOf(horizonDays, 1) - 1
        val placed = events
            .filterNot { ended(it, nowMs, offsetAt) }
            .map { e ->
                val first = firstDayOf(e, offsetAt)
                val last = maxOf(lastDayOf(e, offsetAt), first)
                // Something already under way is listed today, once — not on the day it began,
                // which is off the top of the list.
                Placed(day = maxOf(first, today), e = e, first = first, last = last)
            }
            .filter { it.day in today..lastVisible }
            .sortedWith(
                compareBy<Placed>({ it.day }, { !it.e.allDay }, { it.e.startMs }, { it.e.endMs }, { it.e.title }),
            )

        // Exactly one NEXT: the soonest timed start still ahead. Ties go to whichever sorts first.
        val next = placed.filter { !it.e.allDay && it.e.startMs > nowMs }.minByOrNull { it.e.startMs }

        val entries = placed.map { p ->
            val state = when {
                p.e.allDay -> State.LATER
                p.e.startMs <= nowMs -> State.NOW
                p === next -> State.NEXT
                else -> State.LATER
            }
            val clash = !p.e.allDay && p.e.endMs > p.e.startMs && placed.any { o ->
                o !== p && o.day == p.day && !o.e.allDay && o.e.endMs > o.e.startMs &&
                    o.e.startMs < p.e.endMs && p.e.startMs < o.e.endMs
            }
            p.day to Entry(
                day = p.day,
                event = p.e,
                state = state,
                dayIndex = (p.day - p.first + 1).toInt(),
                daySpan = (p.last - p.first + 1).toInt(),
                lastDay = p.last,
                clash = clash,
            )
        }

        val cap = maxOf(maxItems, 0)
        val shown = entries.take(cap)
        val days = shown.groupBy({ it.first }, { it.second }).map { (day, list) ->
            val kind = when (day) {
                today -> DayKind.TODAY
                today + 1 -> DayKind.TOMORROW
                else -> DayKind.LATER
            }
            Day(day, kind, list)
        }
        return Plan(
            today = today,
            days = days,
            shown = shown.size,
            omitted = entries.size - shown.size,
            all = entries.map { it.second },
        )
    }

    /**
     * The one entry worth a headline: what you are in the middle of, or else what comes next.
     *
     * Among several things in progress, the one ending soonest — it is the one about to demand
     * something of you. Read from [Plan.all], not the capped days, so the cap can never hide it.
     */
    fun upNext(plan: Plan): Entry? =
        plan.all.filter { it.state == State.NOW }.minByOrNull { it.event.endMs }
            ?: plan.all.firstOrNull { it.state == State.NEXT }

    // ---- words -------------------------------------------------------------------------------------

    /**
     * The two formatters the platform supplies. The core never formats a date itself.
     *
     * @property clock an instant → "8:00 AM", in the device zone and the app's own 12/24-hour choice.
     * @property day an epoch day → "TUE 29 SEP". ⚠️ The CALLER must format the day's UTC midnight in
     *   UTC: an epoch day is a date, and formatting its UTC midnight in a zone west of Greenwich
     *   prints the day before — the all-day trap again, one step later.
     */
    class Format(val clock: (Long) -> String, val day: (Long) -> String)

    /** "TODAY · TUE 29 SEP · 5 EVENTS". */
    fun dayHeading(day: Day, fmt: Format): String {
        val prefix = when (day.kind) {
            DayKind.TODAY -> "TODAY · "
            DayKind.TOMORROW -> "TOMORROW · "
            DayKind.LATER -> ""
        }
        val n = day.entries.size
        return "$prefix${fmt.day(day.epochDay)} · $n ${if (n == 1) "EVENT" else "EVENTS"}"
    }

    /** The heading for a today with nothing left in it, so the list does not open on another day unexplained. */
    fun clearTodayHeading(today: Long, fmt: Format): String = "TODAY · ${fmt.day(today)} · NOTHING LEFT"

    /** "8:00 AM  Training" / "NOW  Inspections" / "ALL DAY  Holiday". */
    fun titleLine(e: Entry, fmt: Format): String {
        val lead = when {
            e.event.allDay -> "ALL DAY"
            e.state == State.NOW -> "NOW"
            else -> fmt.clock(e.event.startMs)
        }
        return "$lead  ${titleOf(e.event)}"
    }

    /**
     * "until 11:00 AM · Gym · Work · CLASH", or null when there is nothing to add.
     *
     * The end is dated when it falls on another day — "until WED 30 SEP 2:00 AM" — because an
     * overnight shift that says only "until 2:00 AM" reads as ending before it began. The calendar's
     * name is included only when [withCalendar]: with one calendar it is the same word on every row.
     */
    fun detailLine(e: Entry, fmt: Format, withCalendar: Boolean): String? {
        val parts = mutableListOf<String>()
        until(e, fmt)?.let(parts::add)
        e.event.location.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        if (withCalendar) e.event.calendarName.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        if (e.clash) parts += "CLASH"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /**
     * The headline: "NOW · Inspections · until 7:55 AM · Room 204" or "NEXT 8:00 AM · Training · Gym",
     * with the day named when the next thing is not today — "NEXT TOMORROW 9:00 AM · Standup".
     */
    fun upNextLine(e: Entry, today: Long, fmt: Format): String {
        val head = when {
            e.state == State.NOW -> "NOW · ${titleOf(e.event)}"
            e.day == today -> "NEXT ${fmt.clock(e.event.startMs)} · ${titleOf(e.event)}"
            e.day == today + 1 -> "NEXT TOMORROW ${fmt.clock(e.event.startMs)} · ${titleOf(e.event)}"
            else -> "NEXT ${fmt.day(e.day)} ${fmt.clock(e.event.startMs)} · ${titleOf(e.event)}"
        }
        val parts = mutableListOf(head)
        if (e.state == State.NOW) until(e, fmt)?.let(parts::add)
        e.event.location.trim().takeIf { it.isNotEmpty() }?.let(parts::add)
        return parts.joinToString(" · ")
    }

    /** Whether the calendars differ enough to be worth naming on each row. */
    fun showsCalendars(plan: Plan): Boolean =
        plan.all.map { it.event.calendarName.trim() }.filter { it.isNotEmpty() }.distinct().size >= 2

    private fun titleOf(e: Event): String = e.title.trim().ifEmpty { "(no title)" }

    private fun until(e: Entry, fmt: Format): String? = when {
        e.event.allDay ->
            if (e.daySpan > 1) "day ${e.dayIndex} of ${e.daySpan} · until ${fmt.day(e.lastDay)}" else null
        e.event.endMs <= e.event.startMs -> null
        e.lastDay == e.day -> "until ${fmt.clock(e.event.endMs)}"
        else -> "until ${fmt.day(e.lastDay)} ${fmt.clock(e.event.endMs)}"
    }

    /**
     * When the agenda next changes by itself: the soonest future start or end of a timed event, or
     * the next local midnight, whichever is first.
     *
     * Midnight is always a candidate because TODAY and TOMORROW change meaning then, and an all-day
     * event ends by date rather than by instant.
     */
    fun nextBoundaryMs(events: List<Event>, nowMs: Long, offsetAt: (Long) -> Long): Long {
        val midnight = startOfLocalDay(localDay(nowMs, offsetAt) + 1, offsetAt)
        var best = midnight
        for (e in events) {
            if (e.allDay) continue
            if (e.startMs in (nowMs + 1) until best) best = e.startMs
            if (e.endMs in (nowMs + 1) until best) best = e.endMs
        }
        return best
    }
}
