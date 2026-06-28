package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.R

/**
 * A home-screen widget that auto-cycles a live Pulse feed (markets · fuel · economy/inflation · news).
 * Backed by [FeedRemoteViewsService]; the AdapterViewFlipper auto-advances every few seconds. The whole
 * widget is one click target into the app. Data refreshes on the widget's update period; the flip is
 * purely visual over whatever rows are loaded.
 */
class FeedWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_feed)

            val serviceIntent = Intent(context, FeedRemoteViewsService::class.java).apply {
                // Unique per widget id so the host keeps each instance's adapter distinct.
                data = Uri.parse("pulsefeed://widget/$id")
            }
            views.setRemoteAdapter(R.id.widget_feed_flipper, serviceIntent)
            views.setEmptyView(R.id.widget_feed_flipper, R.id.widget_feed_empty)

            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val template = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setPendingIntentTemplate(R.id.widget_feed_flipper, template)

            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_feed_flipper)
        }
    }
}
