package dev.mascwa.pulse.data.calendar

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

/** A single real device-calendar event, distilled for on-device use (e.g. ORACLE's calendar signal). */
data class CalEvent(
    val id: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
)

/**
 * Reads the device calendar (upcoming events only) → [CalEvent]s. Permission-gated (READ_CALENDAR) and fully
 * defensive — no permission / no provider / any failure yields an empty list. ON-DEVICE ONLY: the event title
 * + times are used locally and are never persisted off-device or transmitted.
 */
class CalendarRepository(private val context: Context) {

    /**
     * Upcoming instances in `[nowMs, nowMs + horizonMs]` via the Instances table (which expands recurring
     * events), soonest first, capped at [max]. Empty if READ_CALENDAR isn't granted or anything fails.
     */
    fun upcoming(nowMs: Long, horizonMs: Long = 48L * 3_600_000L, max: Int = 20): List<CalEvent> {
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, nowMs)
        ContentUris.appendId(builder, nowMs + horizonMs)
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<CalEvent>()
        runCatching {
            context.contentResolver.query(
                builder.build(), projection, null, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cur ->
                while (cur.moveToNext() && out.size < max) {
                    out += CalEvent(
                        id = cur.getLong(0),
                        title = cur.getString(1) ?: "",
                        startMs = cur.getLong(2),
                        endMs = cur.getLong(3),
                        allDay = cur.getInt(4) == 1,
                    )
                }
            }
        }
        return out
    }
}
