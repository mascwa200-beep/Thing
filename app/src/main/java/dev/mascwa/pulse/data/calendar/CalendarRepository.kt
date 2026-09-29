package dev.mascwa.pulse.data.calendar

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

/**
 * A single real device-calendar event, distilled for on-device use (e.g. ORACLE's calendar signal).
 *
 * The last three fields are defaulted so every existing caller compiles unchanged; only the widget's
 * agenda reads them. [colorArgb] is null when the provider has no colour for the event — 0 is a real
 * (transparent black) colour and must not stand in for "none".
 */
data class CalEvent(
    val id: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String = "",
    val colorArgb: Int? = null,
    val calendarName: String = "",
)

/**
 * Reads the device calendar (upcoming events only) → [CalEvent]s. Permission-gated (READ_CALENDAR) and fully
 * defensive — no permission / no provider / any failure yields an empty list. ON-DEVICE ONLY: the event title
 * + times are used locally and are never persisted off-device or transmitted.
 */
class CalendarRepository(private val context: Context) {

    /**
     * Whether the calendar may be read at all.
     *
     * ⚠️ Public because [upcoming] cannot answer it. That returns an empty list for *three*
     * different situations — permission refused, provider unavailable, and a genuinely clear diary —
     * and a caller that renders all three the same way tells someone their week is free when the app
     * was never allowed to look. Ask this first when the difference matters, rather than restating
     * the permission check at the call site and having two copies of one rule.
     */
    fun canRead(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Upcoming instances in `[nowMs, nowMs + horizonMs]` via the Instances table (which expands recurring
     * events), soonest first, capped at [max]. Empty if READ_CALENDAR isn't granted or anything fails.
     */
    fun upcoming(nowMs: Long, horizonMs: Long = 48L * 3_600_000L, max: Int = 20): List<CalEvent> =
        query(nowMs, nowMs + horizonMs, max)

    /**
     * Every instance overlapping `[fromMs, toMs)`, soonest first, capped at [max] — the widget's
     * fortnight. The same query as [upcoming]; only the window differs.
     */
    fun agenda(fromMs: Long, toMs: Long, max: Int): List<CalEvent> = query(fromMs, toMs, max)

    /**
     * The one query both windows share.
     *
     * ⚠️ The selection is what the calendar app itself shows, and it applies to EVERY caller — the
     * Oracle, the day-ahead planner, the brief and the Sensorium as well as the widget:
     *  - `VISIBLE = 1`: a calendar the user has unticked in their calendar app is not their diary.
     *  - not DECLINED: an invitation they turned down is not something they are going to.
     * ⚠️ The attendee test is written null-safe on purpose. An event you organised, or one with no
     * attendee list, has no status at all, and in SQL `NULL != 2` is NULL — which a WHERE treats as
     * false, so the plain comparison would silently drop every event you made yourself.
     */
    private fun query(fromMs: Long, toMs: Long, max: Int): List<CalEvent> {
        if (!canRead() || max <= 0) return emptyList()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, fromMs)
        ContentUris.appendId(builder, toMs)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val selection = "${CalendarContract.Instances.VISIBLE} = 1 AND (" +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR " +
            "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != " +
            "${CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED})"
        val out = mutableListOf<CalEvent>()
        runCatching {
            context.contentResolver.query(
                builder.build(), projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cur ->
                while (cur.moveToNext() && out.size < max) {
                    out += CalEvent(
                        id = cur.getLong(0),
                        title = cur.getString(1) ?: "",
                        startMs = cur.getLong(2),
                        endMs = cur.getLong(3),
                        allDay = cur.getInt(4) == 1,
                        location = cur.getString(5) ?: "",
                        colorArgb = if (cur.isNull(6)) null else cur.getInt(6),
                        calendarName = cur.getString(7) ?: "",
                    )
                }
            }
        }
        return out
    }
}
