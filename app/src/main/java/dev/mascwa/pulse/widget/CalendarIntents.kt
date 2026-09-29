package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings

/**
 * Every tap target the agenda has, in one place.
 *
 * ## Why a list row cannot simply carry its own PendingIntent
 *
 * A collection's rows are drawn by the launcher, and the platform lets them share ONE PendingIntent
 * — the list's template — which each row completes with a fill-in intent. For the fill-in to land,
 * the template has to be MUTABLE, and since Android 14 the platform refuses a mutable PendingIntent
 * that carries an IMPLICIT intent (anything could answer it, with whatever the fill-in added). So
 * the template names our own [CalendarEventActivity] explicitly, and that invisible activity makes
 * the implicit "view this in the calendar app" call itself.
 *
 * Everything that is not a list row — the heading, `+ ADD`, the up-next line — is an ordinary
 * IMMUTABLE PendingIntent, where an implicit intent is allowed and no trampoline is needed. The
 * up-next line still goes through the trampoline, for its fallback: a phone with no calendar app
 * would otherwise swallow the tap in silence.
 *
 * ⚠️ Request codes start at [RC_BASE]. `Intent.filterEquals` ignores extras, so two targets that
 * differ only in extras and share a code would collapse into ONE PendingIntent — the collision
 * already corrected once in NotifId. The board's own codes are 100–104 and the fault card's is 90.
 */
internal object CalendarIntents {

    const val EXTRA_BEGIN = "dev.mascwa.pulse.widget.extra.BEGIN"
    const val EXTRA_END = "dev.mascwa.pulse.widget.extra.END"

    private const val RC_BASE = 120
    private const val RC_DAY = RC_BASE
    private const val RC_ADD = RC_BASE + 1
    private const val RC_APP_DETAILS = RC_BASE + 2
    private const val RC_TEMPLATE = RC_BASE + 3
    const val RC_UP_NEXT_ROW = RC_BASE + 4
    const val RC_UP_NEXT_BOARD = RC_BASE + 5

    private const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** `content://com.android.calendar/time/<ms>` — the calendar app's own "open on this day". */
    fun dayUri(ms: Long): Uri =
        CalendarContract.CONTENT_URI.buildUpon().appendPath("time").also { ContentUris.appendId(it, ms) }.build()

    /** `content://com.android.calendar/events/<id>`. */
    fun eventUri(id: Long): Uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id)

    /** Open the calendar app on the day containing [ms]. */
    fun day(context: Context, ms: Long): PendingIntent =
        PendingIntent.getActivity(
            context, RC_DAY,
            Intent(Intent.ACTION_VIEW, dayUri(ms)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            IMMUTABLE,
        )

    /** Start a new event in the calendar app. */
    fun addEvent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_ADD,
            Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            IMMUTABLE,
        )

    /**
     * This app's own page in system settings, where the calendar permission can be granted. A widget
     * cannot ask for a permission itself; this is the one place the user can give it.
     */
    fun openAppDetails(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_APP_DETAILS,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            IMMUTABLE,
        )

    /**
     * The list's one template: explicit, MUTABLE, and with no data of its own, so each row's fill-in
     * supplies the URI. `Intent.fillIn` only writes data into a template whose data is null.
     */
    fun template(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_TEMPLATE,
            Intent(context, CalendarEventActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** A day heading's fill-in: open the calendar on that day. */
    fun dayFillIn(ms: Long): Intent = Intent().setData(dayUri(ms))

    /**
     * An event row's fill-in. The instance's own times ride along, because a recurring event has one
     * id for every occurrence and the calendar app opens the right one only when told which.
     */
    fun eventFillIn(id: Long, beginMs: Long, endMs: Long): Intent =
        Intent().setData(eventUri(id)).putExtra(EXTRA_BEGIN, beginMs).putExtra(EXTRA_END, endMs)

    /** One event, outside a list — the up-next line. Explicit and immutable, through the trampoline. */
    fun event(context: Context, id: Long, beginMs: Long, endMs: Long, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, CalendarEventActivity::class.java)
                .setData(eventUri(id))
                .putExtra(EXTRA_BEGIN, beginMs)
                .putExtra(EXTRA_END, endMs)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            IMMUTABLE,
        )
}
