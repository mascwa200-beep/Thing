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
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A glanceable home-screen widget (classic RemoteViews — no extra dependency): the active objective
 * and the current weather, tapping through to the J.A.R.V.I.S. console. Reads only on-device data
 * (cached weather + the persisted active waypoint); never blocks the main thread.
 */
class PulseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (weatherLine, objectiveLine) = runCatching { loadData(context) }.getOrDefault("" to "Tap to open")
                ids.forEach { id -> render(context, manager, id, weatherLine, objectiveLine) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun render(context: Context, manager: AppWidgetManager, id: Int, weather: String, objective: String) {
        val views = RemoteViews(context.packageName, R.layout.widget_pulse)
        views.setTextViewText(R.id.widget_weather, weather)
        views.setTextViewText(R.id.widget_objective, objective)
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        manager.updateAppWidget(id, views)
    }

    private suspend fun loadData(context: Context): Pair<String, String> {
        val app = context.applicationContext as? PulseApplication ?: return "" to "Tap to open"
        val c = app.container
        val s = runCatching { c.settingsRepository.current() }.getOrNull()
        val objective = s?.let { st -> st.waypoints.firstOrNull { it.id == st.activeWaypointId }?.label }
            ?.let { "◎ $it" } ?: "No active objective"
        val weather = runCatching {
            val wd = resolveWeather(c, s)
            wd?.current?.let { cur ->
                "${Formatters.number(cur.temperature, 0)}${wd.tempUnitSymbol} · ${WeatherCode.describe(cur.weatherCode)}"
            }
        }.getOrNull() ?: ""
        return weather to objective
    }

    /** Prefer a saved location (no background GPS); fall back to device location only if allowed. */
    private suspend fun resolveWeather(c: AppContainer, s: AppSettings?): WeatherData? {
        s ?: return null
        val saved = s.savedLocations.getOrNull(s.selectedLocationIndex) ?: s.savedLocations.firstOrNull()
        return when {
            saved != null -> c.weatherRepository.fetch(saved.latitude, saved.longitude, saved.name, force = false).data
            s.useDeviceLocation -> c.locationProvider.current()
                ?.let { c.weatherRepository.fetch(it.latitude, it.longitude, it.name, force = false).data }
            else -> null
        }
    }
}
