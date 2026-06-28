package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews
import androidx.core.graphics.drawable.toBitmap
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.feature.dial.ReactorDialViewModel
import dev.mascwa.pulse.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Reactor Dial as a home-screen widget: the reactor core ringed by the user's 8 pinned apps (the same
 * [dev.mascwa.pulse.data.settings.AppSettings.reactorDialSlots] the in-app dial uses). Each app launches on
 * tap; an empty slot or the core opens the in-app dial (where apps are assigned). No animation — RemoteViews
 * can't — but it's a real, tappable launcher right on the home screen.
 */
class DialWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val slots = loadSlots(context)
                ids.forEach { id -> render(context, manager, id, slots) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun loadSlots(context: Context): List<String> {
        val app = context.applicationContext as? PulseApplication ?: return ReactorDialViewModel.normalizeSlots(emptyList())
        val s = runCatching { app.container.settingsRepository.current() }.getOrNull()
        return ReactorDialViewModel.normalizeSlots(s?.reactorDialSlots ?: emptyList())
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int, slots: List<String>) {
        val views = RemoteViews(context.packageName, R.layout.widget_dial)
        val pm = context.packageManager
        SLOT_IDS.forEachIndexed { i, viewId ->
            val pkg = slots.getOrNull(i).orEmpty()
            if (pkg.isNotEmpty()) {
                val bmp = runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()
                if (bmp != null) views.setImageViewBitmap(viewId, bmp)
                else views.setImageViewResource(viewId, R.drawable.ic_dial_slot_empty)
                views.setOnClickPendingIntent(viewId, launchIntent(context, pkg, i + 1))
            } else {
                views.setImageViewResource(viewId, R.drawable.ic_dial_slot_empty)
                views.setOnClickPendingIntent(viewId, openDialIntent(context, 100 + i))
            }
        }
        views.setOnClickPendingIntent(R.id.dial_center, openDialIntent(context, 999))
        manager.updateAppWidget(id, views)
    }

    /** A PendingIntent that launches the pinned app, falling back to opening the in-app dial. */
    private fun launchIntent(context: Context, packageName: String, req: Int): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return openDialIntent(context, req)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, req, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** A PendingIntent that opens the in-app Reactor Dial (to assign / browse). */
    private fun openDialIntent(context: Context, req: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, Routes.DIAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, req, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private val SLOT_IDS = intArrayOf(
            R.id.dial_slot_0, R.id.dial_slot_1, R.id.dial_slot_2, R.id.dial_slot_3,
            R.id.dial_slot_4, R.id.dial_slot_5, R.id.dial_slot_6, R.id.dial_slot_7,
        )

        /** Re-render every placed dial widget — call after the pins change. */
        fun refresh(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(android.content.ComponentName(context, DialWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    val intent = Intent(context, DialWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                    }
                    context.sendBroadcast(intent)
                }
            }
        }
    }
}
