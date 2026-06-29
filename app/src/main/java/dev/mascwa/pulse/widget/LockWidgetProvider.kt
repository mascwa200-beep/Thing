package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.DayPart
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.SpaceWeatherExplainers
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.weather.WeatherCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * A transparent, glanceable widget for the lock screen (and home screen) that is deliberately
 * over-stuffed for its size: greeting + date, active objective, full weather (temp · feels · condition ·
 * hi/lo · humidity · AQI), top market mover + breadth/net, space weather, fuel, an economy indicator, a
 * top headline, the lead task, and device power/network. No background plate — every line carries a drop
 * shadow so it stays legible over any wallpaper; lines with no data hide themselves.
 *
 * Reads ONLY on-device cached data (no background GPS, nothing forced). The data sources can still hit
 * the network on a cold cache, so the whole batch runs in PARALLEL and is bounded by a short timeout —
 * the widget always renders inside goAsync's window, even on a slow link or when network is denied
 * per-app (e.g. GrapheneOS). Fully defensive per source; a missing feed only hides its own line.
 *
 * Whether it can be placed on the lock screen specifically depends on the OS/launcher: the AOSP launcher
 * GrapheneOS ships doesn't expose lock-screen widget slots, so there it loads as a home-screen widget.
 */
class LockWidgetProvider : AppWidgetProvider() {

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
        val header: String = "PULSE",
        val objective: String = "",
        val wx: String = "",
        val wx2: String = "",
        val market: String = "",
        val marketColor: Int = 0xFFE9F4FF.toInt(),
        val breadth: String = "",
        val space: String = "",
        val fuel: String = "",
        val econ: String = "",
        val news: String = "",
        val task: String = "",
        val sys: String = "",
    )

    private fun render(context: Context, manager: AppWidgetManager, id: Int, g: Glance) {
        val v = RemoteViews(context.packageName, R.layout.widget_lock)
        line(v, R.id.lock_header, g.header)
        line(v, R.id.lock_objective, g.objective)
        line(v, R.id.lock_wx, g.wx)
        line(v, R.id.lock_wx2, g.wx2)
        line(v, R.id.lock_market, g.market)
        v.setTextColor(R.id.lock_market, g.marketColor)
        line(v, R.id.lock_breadth, g.breadth)
        line(v, R.id.lock_space, g.space)
        line(v, R.id.lock_fuel, g.fuel)
        line(v, R.id.lock_econ, g.econ)
        line(v, R.id.lock_news, g.news)
        line(v, R.id.lock_task, g.task)
        line(v, R.id.lock_sys, g.sys)

        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        v.setOnClickPendingIntent(R.id.widget_lock_root, pi)
        manager.updateAppWidget(id, v)
    }

    /** Set the line's text, or hide it entirely when there's nothing to show. */
    private fun line(v: RemoteViews, id: Int, text: String) {
        if (text.isBlank()) {
            v.setViewVisibility(id, View.GONE)
        } else {
            v.setViewVisibility(id, View.VISIBLE)
            v.setTextViewText(id, text)
        }
    }

    private data class Net(
        val wx: String = "",
        val wx2: String = "",
        val market: String = "",
        val marketColor: Int = 0xFFE9F4FF.toInt(),
        val breadth: String = "",
        val space: String = "",
        val fuel: String = "",
        val econ: String = "",
        val news: String = "",
        val task: String = "",
    )

    private suspend fun load(context: Context): Glance {
        val app = context.applicationContext as? PulseApplication ?: return Glance()
        val c = app.container
        val s = runCatching { c.settingsRepository.current() }.getOrNull()
        val ctx = runCatching { c.deviceContextProvider.snapshot() }.getOrNull()

        // --- Instant fields (no I/O) ---
        val greeting = when (ctx?.dayPart) {
            DayPart.MORNING -> "GOOD MORNING"
            DayPart.AFTERNOON -> "GOOD AFTERNOON"
            DayPart.EVENING -> "GOOD EVENING"
            DayPart.NIGHT -> "GOOD NIGHT"
            null -> "PULSE"
        }
        val date = runCatching { SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()) }.getOrNull().orEmpty()
        val header = if (date.isNotEmpty()) "$greeting · ${date.uppercase()}" else greeting
        val objective = s?.let { st -> st.waypoints.firstOrNull { it.id == st.activeWaypointId }?.label }
            ?.let { "◎ ${it.take(34)}" }.orEmpty()
        val sys = ctx?.let {
            val batt = if (it.batteryPct >= 0) "${it.batteryPct}%" else "—"
            val chg = if (it.isCharging) " ⚡" else ""
            "SYS  PWR $batt$chg  ·  ${it.network.name}"
        }.orEmpty()

        // --- Network-capable fields: parallel + bounded, so a cold cache / slow link / no-network
        //     (GrapheneOS per-app denial) can't blow goAsync's window. On timeout we render what we have. ---
        val n = withTimeoutOrNull(6_000L) {
            coroutineScope {
                val wxJob = async {
                    runCatching {
                        val saved = s?.let { it.savedLocations.getOrNull(it.selectedLocationIndex) ?: it.savedLocations.firstOrNull() }
                        val wd = saved?.let { c.weatherRepository.fetch(it.latitude, it.longitude, it.name, force = false).data }
                        val cur = wd?.current
                        if (wd != null && cur != null) {
                            val u = wd.tempUnitSymbol
                            val feels = cur.apparentTemperature?.let { " · feels ${Formatters.number(it, 0)}$u" }.orEmpty()
                            val wx = "WX  ${Formatters.number(cur.temperature, 0)}$u$feels · ${WeatherCode.describe(cur.weatherCode)}"
                            val day = wd.daily.firstOrNull()
                            val hilo = if (day?.tempMax != null && day.tempMin != null) {
                                "↑${Formatters.number(day.tempMax, 0)}$u ↓${Formatters.number(day.tempMin, 0)}$u"
                            } else ""
                            val rh = cur.humidity?.let { "${it.toInt()}% RH" }.orEmpty()
                            val aqi = wd.airQuality?.usAqi?.let { "AQI ${it.toInt()}" }.orEmpty()
                            wx to listOf(hilo, rh, aqi).filter { it.isNotBlank() }.joinToString("  ·  ")
                        } else "" to ""
                    }.getOrDefault("" to "")
                }
                val mktJob = async {
                    runCatching {
                        val quotes = c.marketsRepository.fetchWatchlist(force = false).data.orEmpty().filter { it.changePercent != null }
                        var m = ""
                        var col = 0xFFE9F4FF.toInt()
                        var br = ""
                        quotes.maxByOrNull { abs(it.changePercent ?: 0.0) }?.let { mover ->
                            val pct = mover.changePercent ?: 0.0
                            m = "MKT  ${mover.label}  ${signed(pct)}%"
                            col = if (pct >= 0) POSITIVE else NEGATIVE
                        }
                        MarketMood.summarize(quotes.mapNotNull { it.changePercent })?.let { mood ->
                            br = "${mood.headline.uppercase()}  ·  NET ${signed(mood.netChangePct)}%  ·  ${mood.up}▲ ${mood.down}▼"
                        }
                        Triple(m, col, br)
                    }.getOrDefault(Triple("", 0xFFE9F4FF.toInt(), ""))
                }
                val spaceJob = async {
                    runCatching {
                        c.spaceWeatherRepository.fetch(force = false).data?.kp?.let { "SPC  ${SpaceWeatherExplainers.kp(it).headline}" }.orEmpty()
                    }.getOrDefault("")
                }
                val fuelJob = async {
                    runCatching {
                        c.fuelRepository.fetch(force = false).data?.benchmarks?.firstOrNull()?.let { b ->
                            val price = b.price?.let { "$${"%.2f".format(it)}" }.orEmpty()
                            val pct = b.changePercent?.let { " ${signed(it)}%" }.orEmpty()
                            "FUEL  ${b.label} $price$pct".trim()
                        }.orEmpty()
                    }.getOrDefault("")
                }
                val econJob = async {
                    runCatching {
                        c.economyRepository.fetchDashboard(force = false).data?.series.orEmpty()
                            .firstOrNull { it.latest != null }?.let { series ->
                                series.latest?.let { pt -> "ECON  ${series.indicatorTitle.take(22)}: ${"%.1f".format(pt.value)} ${series.unit}".trim() }
                            }.orEmpty()
                    }.getOrDefault("")
                }
                val newsJob = async {
                    runCatching {
                        c.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data?.firstOrNull()?.let { a ->
                            "NEWS  ${a.source.uppercase()} · ${a.title}".take(90)
                        }.orEmpty()
                    }.getOrDefault("")
                }
                val taskJob = async {
                    runCatching {
                        val pending = TaskBoard.pending(c.taskStore.all())
                        pending.firstOrNull()?.let {
                            val more = if (pending.size > 1) "   (+${pending.size - 1})" else ""
                            "TASK  ${it.title.take(34)}$more"
                        }.orEmpty()
                    }.getOrDefault("")
                }

                val (wx, wx2) = wxJob.await()
                val (market, marketColor, breadth) = mktJob.await()
                Net(wx, wx2, market, marketColor, breadth, spaceJob.await(), fuelJob.await(), econJob.await(), newsJob.await(), taskJob.await())
            }
        } ?: Net()

        return Glance(
            header = header,
            objective = objective,
            wx = n.wx,
            wx2 = n.wx2,
            market = n.market,
            marketColor = n.marketColor,
            breadth = n.breadth,
            space = n.space,
            fuel = n.fuel,
            econ = n.econ,
            news = n.news,
            task = n.task,
            sys = sys,
        )
    }

    private companion object {
        val POSITIVE = 0xFF46F9A0.toInt()
        val NEGATIVE = 0xFFFF4D6D.toInt()
        fun signed(v: Double): String = (if (v >= 0) "+" else "") + "%.2f".format(v)
    }
}
