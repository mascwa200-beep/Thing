package dev.mascwa.pulse.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.widget.RemoteViews
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.DayPart
import dev.mascwa.pulse.core.telemetry.NetworkKind
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** High = solid dark panel; Low = fully transparent (text only, to sit over the wallpaper). */
enum class JarvisWidgetMode { HIGH, LOW }

/** Per-widget-instance mode, chosen in the config screen when the widget is placed. */
object JarvisWidgetPrefs {
    private const val FILE = "jarvis_widgets"
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun mode(c: Context, id: Int): JarvisWidgetMode =
        runCatching { JarvisWidgetMode.valueOf(prefs(c).getString("mode_$id", null) ?: "HIGH") }.getOrDefault(JarvisWidgetMode.HIGH)
    fun setMode(c: Context, id: Int, mode: JarvisWidgetMode) = prefs(c).edit().putString("mode_$id", mode.name).apply()
    fun clear(c: Context, id: Int) = prefs(c).edit().remove("mode_$id").apply()
}

/** The four Computer widgets. [route] is where a tap lands in the app. */
enum class JarvisWidgetType(val title: String, val route: String) {
    STATUS("COMPUTER", "jarvis"),
    OBJECTIVE("OBJECTIVE", "nav"),
    FINDING("COMPUTER", "jarvis"),
    BRIEF("BRIEF", "home");

    companion object {
        fun forProvider(className: String?): JarvisWidgetType? = when (className) {
            JarvisStatusWidget::class.java.name -> STATUS
            JarvisObjectiveWidget::class.java.name -> OBJECTIVE
            JarvisFindingWidget::class.java.name -> FINDING
            JarvisBriefWidget::class.java.name -> BRIEF
            else -> null
        }
    }
}

/**
 * Shared renderer for all four J.A.R.V.I.S. widgets. One layout ([R.layout.widget_jarvis]); the widget
 * TYPE picks the content + tap route, and the per-instance MODE picks the look — high-contrast solid panel
 * vs. fully transparent (text only). Reads only on-device data (settings, device snapshot, findings,
 * tasks, cached weather); never blocks the main thread.
 */
object JarvisWidgetRenderer {

    suspend fun render(context: Context, manager: AppWidgetManager, id: Int, type: JarvisWidgetType) {
        val mode = JarvisWidgetPrefs.mode(context, id)
        val (line1, line2) = runCatching { loadContent(context, type) }.getOrDefault("Tap to open" to "")
        val views = RemoteViews(context.packageName, R.layout.widget_jarvis)
        // Mode: high = panel background; low = no background (transparent, text only).
        views.setInt(R.id.widget_root, "setBackgroundResource", if (mode == JarvisWidgetMode.HIGH) R.drawable.widget_bg else 0)
        views.setTextViewText(R.id.widget_title, type.title)
        views.setTextViewText(R.id.widget_line1, line1)
        views.setTextViewText(R.id.widget_line2, line2)
        views.setViewVisibility(R.id.widget_line2, if (line2.isBlank()) android.view.View.GONE else android.view.View.VISIBLE)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, type.route)
        }
        val pi = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        manager.updateAppWidget(id, views)
    }

    private suspend fun loadContent(context: Context, type: JarvisWidgetType): Pair<String, String> {
        val app = context.applicationContext as? PulseApplication ?: return "Tap to open" to ""
        val c = app.container
        val s = runCatching { c.settingsRepository.current() }.getOrNull()
        return when (type) {
            JarvisWidgetType.STATUS -> {
                val dev = runCatching { c.deviceContextProvider.snapshot() }.getOrNull()
                val greet = greeting(dev?.dayPart)
                val status = dev?.let {
                    buildString {
                        if (it.batteryPct >= 0) append("Battery ${it.batteryPct}%")
                        append(if (isNotEmpty()) " · " else "").append(prettyNetwork(it.network))
                    }
                }.orEmpty()
                greet to status
            }
            JarvisWidgetType.OBJECTIVE -> {
                val wp = s?.let { st -> st.waypoints.firstOrNull { it.id == st.activeWaypointId } }
                if (wp != null) "◎ ${wp.label}" to (wp.note?.takeIf { it.isNotBlank() } ?: "Tracking · ${wp.kind.name.lowercase()}")
                else "No active objective" to "Set one on the map"
            }
            JarvisWidgetType.FINDING -> {
                runCatching { c.findingStore.load() }
                val f = c.findingStore.findingsFlow.value.let { list -> list.firstOrNull { !it.seen } ?: list.firstOrNull() }
                if (f != null) f.headline to "I came across this — tap to discuss"
                else "Nothing to report yet" to "I'll bring you what I find"
            }
            JarvisWidgetType.BRIEF -> {
                val weather = runCatching {
                    val wd = resolveWeather(c, s)
                    wd?.current?.let { cur -> "${Formatters.number(cur.temperature, 0)}${wd.tempUnitSymbol} · ${WeatherCode.describe(cur.weatherCode)}" }
                }.getOrNull().orEmpty()
                val task = runCatching { TaskBoard.focus(c.taskStore.all()) }.getOrNull().orEmpty()
                (weather.ifBlank { "Your brief" }) to task
            }
        }
    }

    private fun greeting(part: DayPart?): String = when (part) {
        DayPart.MORNING -> "Good morning."
        DayPart.AFTERNOON -> "Good afternoon."
        DayPart.EVENING -> "Good evening."
        DayPart.NIGHT -> "Burning the midnight oil?"
        null -> "At your service."
    }

    private fun prettyNetwork(n: NetworkKind): String = when (n) {
        NetworkKind.WIFI -> "Wi-Fi"
        NetworkKind.CELLULAR -> "Cellular"
        NetworkKind.ETHERNET -> "Ethernet"
        NetworkKind.VPN -> "VPN"
        NetworkKind.OFFLINE -> "Offline"
        NetworkKind.OTHER -> "Online"
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

/** Base for the four J.A.R.V.I.S. widget providers — each differs only by its [type]. */
abstract class JarvisBaseWidget(private val type: JarvisWidgetType) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { id -> runCatching { JarvisWidgetRenderer.render(context, manager, id, type) } }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { JarvisWidgetPrefs.clear(context, it) }
    }
}

class JarvisStatusWidget : JarvisBaseWidget(JarvisWidgetType.STATUS)
class JarvisObjectiveWidget : JarvisBaseWidget(JarvisWidgetType.OBJECTIVE)
class JarvisFindingWidget : JarvisBaseWidget(JarvisWidgetType.FINDING)
class JarvisBriefWidget : JarvisBaseWidget(JarvisWidgetType.BRIEF)
