package dev.mascwa.pulse.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Redraws the widget when the calendar changes — an event added in the calendar app, one moved, an
 * invitation synced down from the server.
 *
 * Without it a new event reached the widget on the next periodic refresh, which the settings picker
 * can put four hours away. The agenda is the part of the widget people act on, and "I added it and
 * the widget does not show it" reads as the widget being broken.
 *
 * ⚠️ **A content-URI trigger fires ONCE.** WorkManager registers the observer through JobScheduler
 * for a single run, so the worker enqueues its successor as the last thing it does, with
 * `APPEND_OR_REPLACE`: the successor waits behind this run and is armed the moment it succeeds.
 * `KEEP` there would see this very run still in progress and keep IT — leaving nothing behind once
 * it finishes, which is a trigger that works exactly once and then never again. `REPLACE` would
 * cancel the run doing the work.
 *
 * ⚠️ Only while a widget is placed and the calendar can be read. Observing a provider the app may
 * not read is not something to rely on, and a trigger for a widget nobody placed is a job the
 * system runs for nothing on every sync. [ensure] is called from each widget update, so granting
 * the permission later, or placing the widget again, re-arms it within one refresh.
 */
class CalendarChangeWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        requestWidgetUpdate(applicationContext)
        rearm(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "widget_calendar_change"

        /**
         * How long the calendar must be quiet before the widget redraws. A sync writes many rows in
         * a burst; redrawing per row would rebuild the whole board a dozen times for one change.
         */
        private const val SETTLE_SECONDS = 15L

        /** And the longest a steady trickle of changes may hold the redraw back. */
        private const val MAX_WAIT_SECONDS = 120L

        /** Arm the trigger if it is not armed already. Cheap — called from every widget update. */
        fun ensure(context: Context) = rearm(context, ExistingWorkPolicy.KEEP)

        /** Take the trigger down: the last widget was removed. */
        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME) }
        }

        private fun rearm(context: Context, policy: ExistingWorkPolicy) {
            runCatching {
                if (widgetUpdateIntent(context) == null || !canReadCalendar(context)) {
                    cancel(context)
                    return
                }
                val constraints = Constraints.Builder()
                    // Descendants TRUE: an edit lands on an event or instance URI underneath the
                    // authority, never on the bare authority itself.
                    .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                    .setTriggerContentUpdateDelay(SETTLE_SECONDS, TimeUnit.SECONDS)
                    .setTriggerContentMaxDelay(MAX_WAIT_SECONDS, TimeUnit.SECONDS)
                    .build()
                val request = OneTimeWorkRequestBuilder<CalendarChangeWorker>()
                    .setConstraints(constraints)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, policy, request)
            }
        }

        private fun canReadCalendar(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }
}
