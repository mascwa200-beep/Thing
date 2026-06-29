package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.weather.WeatherCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A compact, glanceable widget built for the lock screen (and usable on the home screen): the active
 * objective, the current weather, and the top market mover — bright text on a dark plate so it stays
 * legible over any wallpaper. Reads only on-device cached data (no background GPS), never blocks the
 * main thread, and is fully defensive so a missing feed never blanks it. Whether it can be placed on
 * the lock screen specifically depends on the OS/launcher (declared `keyguard`-eligible in its info).
 */
class LockWidgetProvider : AppWidgetProvider() {

    private val positive = 0xFF46F9A0.toInt()
    private val negative = 0xFFFF4D6D.toInt()
    private val ink = 0xFFE6EFFA.toInt()

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val glance = runCatching { load(context) }.getOrDefault(Glance())
                ids.forEach { id -> runCatching { render(context, manager, id, glance) } }
            } finally {
                pending.finish()
            }
        }
    }

    private data class Glance(
        val objective: String = "PULSE",
        val weather: String = "",
        val market: String = "",
        val marketColor: Int = 0xFFE6EFFA.toInt(),
    )

    private fun render(context: Context, manager: AppWidgetManager, id: Int, g: Glance) {
        val views = RemoteViews(context.packageName, R.layout.widget_lock)
        views.setTextViewText(R.id.widget_lock_objective, g.objective)
        views.setTextViewText(R.id.widget_lock_weather, g.weather)
        views.setTextViewText(R.id.widget_lock_market, g.market)
        views.setTextColor(R.id.widget_lock_market, g.marketColor)
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_lock_root, pi)
        manager.updateAppWidget(id, views)
    }

    private suspend fun load(context: Context): Glance {
        val app = context.applicationContext as? PulseApplication ?: return Glance()
        val c = app.container
        val s = runCatching { c.settingsRepository.current() }.getOrNull()

        val objective = s?.let { st -> st.waypoints.firstOrNull { it.id == st.activeWaypointId }?.label }
            ?.let { "◎ $it" } ?: "PULSE"

        val weather = runCatching {
            val saved = s?.let { it.savedLocations.getOrNull(it.selectedLocationIndex) ?: it.savedLocations.firstOrNull() }
            saved?.let { loc ->
                c.weatherRepository.fetch(loc.latitude, loc.longitude, loc.name, force = false).data
            }?.let { wd ->
                wd.current?.let { cur ->
                    "${Formatters.number(cur.temperature, 0)}${wd.tempUnitSymbol} · ${WeatherCode.describe(cur.weatherCode)}"
                }
            }
        }.getOrNull().orEmpty()

        val mover = runCatching {
            c.marketsRepository.fetchWatchlist(force = false).data.orEmpty()
                .filter { it.changePercent != null }
                .maxByOrNull { abs(it.changePercent ?: 0.0) }
        }.getOrNull()
        val pct = mover?.changePercent ?: 0.0
        val market = mover?.let { "${it.label}  ${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%" }.orEmpty()

        return Glance(
            objective = objective,
            weather = weather,
            market = market,
            marketColor = if (market.isEmpty()) ink else if (pct >= 0) positive else negative,
        )
    }
}
