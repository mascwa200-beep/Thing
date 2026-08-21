package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.DayPart
import dev.mascwa.pulse.core.telemetry.NetworkKind
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** High = solid dark panel; Low = fully transparent (text only, to sit over the wallpaper). */
enum class ComputerWidgetMode { HIGH, LOW }

/**
 * What one placed widget shows, and where tapping it lands.
 *
 * ⚠️ **This used to be four separate `AppWidgetProvider`s** — `JarvisStatusWidget`,
 * `JarvisObjectiveWidget`, `JarvisFindingWidget`, `JarvisBriefWidget` — sharing one layout, one
 * provider-info file and one config activity, and differing in nothing but the two values below.
 * That is a configuration, not four widgets, and treating it as four had already produced a real
 * defect: STATUS and FINDING had drifted to the identical title *and* the identical route, so two
 * of the four were indistinguishable once placed and could only be told apart by their picker
 * labels — which were the stale `J.A.R.V.I.S. …` ones.
 *
 * Collapsing them also removes a fragility that had nothing to do with content: the old code
 * resolved which of the four it was by matching a **fully-qualified class name**, so renaming or
 * moving any leaf class would have silently degraded every placed widget to STATUS.
 */
enum class ComputerWidgetContent(val title: String, val route: String) {
    STATUS("COMPUTER", Routes.JARVIS),
    OBJECTIVE("OBJECTIVE", Routes.NAV),
    FINDING("FINDING", Routes.JARVIS),
    BRIEF("BRIEF", Routes.HOME);

    companion object {
        val DEFAULT = STATUS
    }
}

/**
 * Per-instance choices, made in the config screen when the widget is placed.
 *
 * ⚠️ The SharedPreferences file name and the `mode_` key prefix are **kept exactly as they were**.
 * They are a persisted on-disk contract: renaming them would silently reset every placed widget to
 * HIGH, which is precisely the kind of invisible breakage a tidy-up should never cause. The new
 * `content_` key sits beside the old one in the same file.
 */
object ComputerWidgetPrefs {
    private const val FILE = "jarvis_widgets"

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(c: Context, id: Int): ComputerWidgetMode = runCatching {
        ComputerWidgetMode.valueOf(prefs(c).getString("mode_$id", null) ?: "HIGH")
    }.getOrDefault(ComputerWidgetMode.HIGH)

    /** Defaults to STATUS so an instance whose choice was never written still renders something. */
    fun content(c: Context, id: Int): ComputerWidgetContent = runCatching {
        ComputerWidgetContent.valueOf(
            prefs(c).getString("content_$id", null) ?: ComputerWidgetContent.DEFAULT.name,
        )
    }.getOrDefault(ComputerWidgetContent.DEFAULT)

    fun set(c: Context, id: Int, mode: ComputerWidgetMode, content: ComputerWidgetContent) {
        prefs(c).edit().putString("mode_$id", mode.name).putString("content_$id", content.name).apply()
    }

    fun clear(c: Context, id: Int) {
        prefs(c).edit().remove("mode_$id").remove("content_$id").apply()
    }
}

/**
 * Renders one placed Computer widget. Reads only on-device data — settings, the device snapshot,
 * findings, tasks, cached weather — and never blocks the main thread.
 */
object ComputerWidgetRenderer {

    suspend fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val mode = ComputerWidgetPrefs.mode(context, id)
        val content = ComputerWidgetPrefs.content(context, id)

        // ⚠️ Bounded. The old renderer had no timeout at all while reading the same network-capable
        // repositories, so a cold cache on a slow link could exhaust goAsync's window and the widget
        // would simply never draw — with nothing on screen to say so.
        val (line1, line2) = withTimeoutOrNull(WIDGET_LOAD_TIMEOUT_MS) {
            runCatching { loadContent(context, content) }.getOrNull()
        } ?: ("Tap to open" to "")

        val views = RemoteViews(context.packageName, R.layout.widget_computer)
        // High = panel background; low = none, so the text sits directly on the wallpaper.
        views.setInt(
            R.id.widget_root,
            "setBackgroundResource",
            if (mode == ComputerWidgetMode.HIGH) R.drawable.widget_bg else 0,
        )
        views.setTextViewText(R.id.widget_title, content.title)
        views.setTextColor(R.id.widget_title, ContextCompat.getColor(context, widgetAccentRes()))
        views.setTextViewText(R.id.widget_line1, line1)
        views.setTextViewText(R.id.widget_line2, line2)
        views.setViewVisibility(R.id.widget_line2, if (line2.isBlank()) View.GONE else View.VISIBLE)

        // The hint is written rather than left to the layout, and hidden in transparent mode: over a
        // wallpaper, a permanent "Tap to open" is clutter on a widget chosen for being minimal.
        views.setTextViewText(R.id.widget_hint, "Tap to open")
        views.setViewVisibility(
            R.id.widget_hint,
            if (mode == ComputerWidgetMode.HIGH) View.VISIBLE else View.GONE,
        )

        // ⚠️ Two things keep this PendingIntent distinct, and it needs both.
        //
        // A PendingIntent's identity is its request code plus `Intent.filterEquals`, which compares
        // action, categories, component, data, identifier, package and type — and NOT the extras.
        // The request code is the widget id, so two placed instances never collide with each other.
        // But widget ids are host-assigned small integers, and `NotifId` uses its own ids as request
        // codes for the same reason, so the two number spaces could in principle meet. Declaring an
        // action puts this outside every extras-free `Intent(MainActivity)` in the app regardless of
        // the number — the same thing `AppShortcuts` does, and for the same reason.
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, content.route)
        }
        val pi = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        manager.updateAppWidget(id, views)
    }

    private suspend fun loadContent(
        context: Context,
        content: ComputerWidgetContent,
    ): Pair<String, String> {
        val app = context.applicationContext as? PulseApplication ?: return "Tap to open" to ""
        val c = app.container
        val s = runCatching { c.settingsRepository.current() }.getOrNull()
        return when (content) {
            ComputerWidgetContent.STATUS -> {
                val dev = runCatching { c.deviceContextProvider.snapshot() }.getOrNull()
                val status = dev?.let {
                    buildString {
                        if (it.batteryPct >= 0) append("Battery ${it.batteryPct}%")
                        append(if (isNotEmpty()) " · " else "").append(prettyNetwork(it.network))
                    }
                }.orEmpty()
                greeting(dev?.dayPart) to status
            }

            ComputerWidgetContent.OBJECTIVE -> {
                val wp = s?.let { st -> st.waypoints.firstOrNull { it.id == st.activeWaypointId } }
                if (wp != null) {
                    "◎ ${wp.label}" to (
                        wp.note?.takeIf { it.isNotBlank() }
                            ?: "Tracking · ${wp.kind.name.lowercase()}"
                        )
                } else {
                    "No active objective" to "Set one on the map"
                }
            }

            ComputerWidgetContent.FINDING -> {
                // ⚠️ The load's result is checked. It used to be discarded and the flow read
                // afterwards regardless, so a failed load rendered as the cheerful empty state —
                // "Nothing to report yet" when the truth was that nothing had been read.
                val loaded = runCatching { c.findingStore.load() }.isSuccess
                val f = c.findingStore.findingsFlow.value
                    .let { list -> list.firstOrNull { !it.seen } ?: list.firstOrNull() }
                when {
                    f != null -> f.headline to "I came across this — tap to discuss"
                    loaded -> "Nothing to report yet" to "I'll bring you what I find"
                    else -> "Findings unavailable" to "Tap to open"
                }
            }

            ComputerWidgetContent.BRIEF -> {
                val weather = runCatching {
                    val wd = resolveWeather(c, s)
                    wd?.current?.let { cur ->
                        "${Formatters.number(cur.temperature, 0)}${wd.tempUnitSymbol} · " +
                            WeatherCode.describe(cur.weatherCode)
                    }
                }.getOrNull().orEmpty()
                val task = runCatching { TaskBoard.focus(c.taskStore.all()) }.getOrNull().orEmpty()
                weather.ifBlank { "Your brief" } to task
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
}

/** The one Computer widget. What it shows is a per-instance choice, not a separate provider. */
class ComputerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { id ->
                    runCatching { ComputerWidgetRenderer.render(context, manager, id) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Drop the per-instance choices with the instance, so the prefs file cannot grow forever. */
    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { ComputerWidgetPrefs.clear(context, it) }
    }
}
