package dev.mascwa.pulse.widget

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import dev.mascwa.pulse.MainActivity

/**
 * An invisible hop between a widget row and the calendar app. See [CalendarIntents] for why a list
 * row cannot open the calendar directly.
 *
 * ⚠️ It accepts ONLY a `content://com.android.calendar/…` URI and forwards only the two times it
 * knows. Anything else opens LCARS. The activity is unexported, so nothing outside this app can
 * reach it — but the template it serves is mutable by design, and an activity that forwarded
 * whatever it was handed would be a general-purpose launcher sitting behind one.
 *
 * ⚠️ A phone with no calendar app has nothing to answer `ACTION_VIEW` on these URIs. The tap then
 * opens LCARS rather than doing nothing, because a tap that does nothing reads as a broken widget.
 */
class CalendarEventActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(intent)
        finish()
    }

    private fun forward(from: Intent?) {
        val uri = from?.data
        if (from == null || uri == null || !isCalendarUri(uri)) {
            openApp()
            return
        }
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (from.hasExtra(CalendarIntents.EXTRA_BEGIN)) {
            view.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, from.getLongExtra(CalendarIntents.EXTRA_BEGIN, 0L))
        }
        if (from.hasExtra(CalendarIntents.EXTRA_END)) {
            view.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, from.getLongExtra(CalendarIntents.EXTRA_END, 0L))
        }
        try {
            startActivity(view)
        } catch (_: ActivityNotFoundException) {
            openApp()
        }
    }

    private fun isCalendarUri(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == CalendarContract.AUTHORITY

    private fun openApp() {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
