package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.R

/**
 * A home-screen widget that auto-cycles a live feed (markets · fuel · economy/inflation · news).
 * Backed by [FeedRemoteViewsService]; the AdapterViewFlipper auto-advances every few seconds, and
 * each row now taps through to the screen it came from.
 *
 * This provider does no I/O — it builds RemoteViews and hands the rows to the service — which is why
 * it is the one widget with no `goAsync()`.
 */
class FeedWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            runCatching {
                val views = RemoteViews(context.packageName, R.layout.widget_feed)

                val serviceIntent = Intent(context, FeedRemoteViewsService::class.java).apply {
                    // Unique per widget id so the host keeps each instance's adapter distinct.
                    //
                    // ⚠️ The `pulsefeed` scheme is old branding and stays. It is a persisted adapter
                    // key held in the host's own state, so renaming it would orphan every placed
                    // widget for no visible gain — it is never shown to anyone.
                    data = Uri.parse("pulsefeed://widget/$id")
                }
                views.setRemoteAdapter(R.id.widget_feed_flipper, serviceIntent)
                views.setEmptyView(R.id.widget_feed_flipper, R.id.widget_feed_empty)
                views.setTextColor(
                    R.id.widget_feed_header,
                    ContextCompat.getColor(context, widgetAccentRes()),
                )

                // ⚠️ **MUTABLE, and it has to be.** A template exists precisely so each row's
                // fill-in intent can complete it; an immutable one discards the fill-in entirely.
                // This was IMMUTABLE, which was harmless only because the fill-in was an empty
                // `Intent()` — so every row in the feed did the same thing, and any future attempt
                // to carry a destination would have silently done nothing. Now the rows carry their
                // own route (see FeedRemoteViewsService.getViewAt) and it works.
                //
                // The template targets an explicit component with an explicit action, so a fill-in
                // can only ever add extras to a fixed destination inside this app.
                val open = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val template = PendingIntent.getActivity(
                    context, id, open,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                views.setPendingIntentTemplate(R.id.widget_feed_flipper, template)

                manager.updateAppWidget(id, views)
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_feed_flipper)
            }
        }
    }
}
