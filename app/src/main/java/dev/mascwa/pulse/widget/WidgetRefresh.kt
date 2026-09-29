package dev.mascwa.pulse.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The broadcast that makes every placed widget redraw now, or null when none is placed.
 *
 * ⚠️ Extracted from `RefreshWorker.refreshWidgets()` rather than written a second time. Three things
 * now need it — the worker, the calendar-change trigger and the boundary alarm — and
 * `AppWidgetProvider.onReceive` does NOTHING with an update broadcast that carries no ids, so a copy
 * that forgot the extra would compile, send, and redraw nothing.
 *
 * Kept out of `WidgetCommon.kt` on purpose: that file reaches the app container, and this one
 * touches only the platform, so it can be type-checked on its own.
 */
fun widgetUpdateIntent(context: Context): Intent? {
    val component = ComponentName(context, LockWidgetProvider::class.java)
    val ids = runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(component) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    return Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
        setComponent(component)
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
    }
}

/** Ask every placed widget to redraw. A no-op when none is placed. */
fun requestWidgetUpdate(context: Context) {
    runCatching { widgetUpdateIntent(context)?.let(context::sendBroadcast) }
}

/**
 * One alarm, at the next moment the agenda changes on its own: an event starting, one ending, or
 * local midnight turning TOMORROW into TODAY.
 *
 * Without it the widget flips NOW and NEXT whenever the next periodic refresh happens to land —
 * fifteen minutes late at best, and the picker allows four hours. A NEXT line still showing a
 * meeting that started twenty minutes ago is exactly the kind of confident wrongness this widget
 * spent a whole arc removing.
 *
 * ⚠️ **`RTC`, never `RTC_WAKEUP`.** A sleeping phone's home screen is not being looked at, so waking
 * the CPU to redraw it would spend battery on nothing; a non-waking alarm is delivered when the
 * device next wakes, which is the first moment anyone could see the result. The screen being on is
 * what matters, and it is on exactly when the widget is being read.
 *
 * ⚠️ Exact when the platform allows it and a ten-minute window when it does not. `USE_EXACT_ALARM`
 * (declared in the manifest) is granted at install from API 33; on API 31–32 it does not exist, and
 * there `canScheduleExactAlarms()` is false and a window keeps the behaviour sane rather than broken.
 * The check is also what `setExact` itself requires: calling it without the grant throws.
 */
internal object WidgetBoundaryAlarm {

    /**
     * Distinct from every activity PendingIntent the widget builds. A broadcast PendingIntent could
     * not collide with those anyway — the type is part of its identity — but reading one number in
     * two places is how that assumption stops being checked.
     */
    private const val REQUEST_CODE = 130

    /** How late a non-exact delivery may be. Long enough for the platform to batch, short enough to read as on time. */
    const val WINDOW_MS = 10 * 60_000L

    /**
     * Arm the alarm for [atMs], replacing any earlier one; null (or nothing placed) cancels it.
     *
     * One request code and `FLAG_UPDATE_CURRENT` mean there is only ever ONE of these outstanding —
     * each render re-arms it for its own next boundary, so a stale one can never fire twice.
     */
    fun schedule(context: Context, atMs: Long?) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val update = widgetUpdateIntent(context)
            if (atMs == null || update == null) {
                cancel(context)
                return
            }
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, update,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (am.canScheduleExactAlarms()) {
                am.setExact(AlarmManager.RTC, atMs, pi)
            } else {
                am.setWindow(AlarmManager.RTC, atMs, WINDOW_MS, pi)
            }
        }
    }

    /** Take the alarm down — the last widget was removed, or there is no boundary worth waking for. */
    fun cancel(context: Context) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            // ⚠️ FLAG_NO_CREATE: asking only whether one EXISTS. Without it, cancelling would first
            // create a PendingIntent to cancel, which works but leaves the lookup doing the opposite
            // of what it reads as.
            val existing = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(ComponentName(context, LockWidgetProvider::class.java)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) ?: return
            am.cancel(existing)
            existing.cancel()
        }
    }
}
