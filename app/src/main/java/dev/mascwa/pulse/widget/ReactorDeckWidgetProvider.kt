package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import dev.mascwa.pulse.R
import dev.mascwa.pulse.feature.dial.DialLaunchTrampolineActivity

/**
 * The Reactor Deck widget — a StackView "rotary" of every installed app. Swipe to spin the deck; each card
 * peels from / returns to the stack (the single section), tap to launch. Backed by [DeckRemoteViewsService].
 * Distinct from the in-app dial and the 3x3 grid widget.
 */
class ReactorDeckWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_deck)

            val service = Intent(context, DeckRemoteViewsService::class.java).apply {
                data = Uri.parse("pulsedeck://widget/$id")
            }
            views.setRemoteAdapter(R.id.deck_stack, service)
            views.setEmptyView(R.id.deck_stack, R.id.deck_empty)

            // Template + per-card fill-in: tapping a card launches its app via the trampoline. MUTABLE so the
            // fill-in package extra is merged in at click time.
            val template = PendingIntent.getActivity(
                context, 0,
                Intent(context, DialLaunchTrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setPendingIntentTemplate(R.id.deck_stack, template)

            manager.updateAppWidget(id, views)
            manager.notifyAppWidgetViewDataChanged(id, R.id.deck_stack)
        }
    }
}
