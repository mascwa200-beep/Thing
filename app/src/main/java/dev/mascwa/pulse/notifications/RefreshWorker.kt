package dev.mascwa.pulse.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherData
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Periodic background job that powers push notifications: breaking news,
 * market/price threshold alerts, severe-weather alerts and a daily digest.
 * Keyless data sources; dedup state persisted in the disk cache.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val app get() = applicationContext as PulseApplication
    private val container get() = app.container

    override suspend fun doWork(): Result {
        val settings = runCatching { container.settingsRepository.current() }.getOrNull()
            ?: return Result.success()
        val prefs = settings.notifications
        if (!prefs.masterEnabled) return Result.success()

        val cal = Calendar.getInstance()
        val today = dayKey(cal)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        if (inQuietHours(prefs.quietHoursEnabled, prefs.quietStartHour, prefs.quietEndHour, hour)) {
            return Result.success()
        }

        var state = readState()
        val notifier = container.notifier

        // --- Breaking news ---
        if (prefs.breakingNews) {
            runCatching {
                val top = container.newsRepository.fetchCategory(NewsCategory.TOP, force = true).data
                val currentUrls = top.take(20).map { it.url }
                val firstRun = state.seenTopUrls.isEmpty()
                val fresh = top.filter { it.url !in state.seenTopUrls }
                if (!firstRun && fresh.isNotEmpty()) {
                    val lead = fresh.first()
                    val extra = if (fresh.size > 1) " (+${fresh.size - 1} more)" else ""
                    notifier.notifyBreaking(
                        id = 1001,
                        title = "Breaking" + (lead.source.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        body = lead.title + extra,
                    )
                }
                state = state.copy(seenTopUrls = currentUrls)
            }
        }

        // --- Market / price alerts ---
        if (prefs.marketAlerts) {
            runCatching {
                val alreadyToday = if (state.marketAlertDay == today) {
                    state.marketAlertedSymbols.toMutableSet()
                } else mutableSetOf()
                val quotes = container.marketsRepository.fetchAll(force = true).data
                quotes.forEach { q ->
                    val pct = q.changePercent ?: return@forEach
                    if (abs(pct) >= prefs.marketMovePercent && q.id !in alreadyToday) {
                        val dir = if (pct >= 0) "▲" else "▼"
                        notifier.notifyMarket(
                            id = 2000 + (q.id.hashCode() and 0xFFF),
                            title = "${q.label} $dir ${Formatters.signedPercent(pct)}",
                            body = "Now ${Formatters.number(q.price, 2)} ${q.currency}".trim(),
                        )
                        alreadyToday += q.id
                    }
                }
                state = state.copy(marketAlertDay = today, marketAlertedSymbols = alreadyToday.toList())
            }
        }

        // --- Severe weather alerts ---
        var weather: WeatherData? = null
        if (prefs.weatherAlerts && state.weatherAlertDay != today) {
            runCatching {
                weather = resolveWeather(settings)
                weather?.daily?.firstOrNull()?.let { day ->
                    val severe = WeatherCode.isSevere(day.weatherCode)
                    val wet = (day.precipProbabilityMax ?: 0) >= 70
                    if (severe || wet) {
                        notifier.notifyWeather(
                            id = 3001,
                            title = "${weather!!.locationName}: ${WeatherCode.describe(day.weatherCode)}",
                            body = buildString {
                                append("High ${Formatters.number(day.tempMax, 0)}${weather!!.tempUnitSymbol}")
                                append(" · Low ${Formatters.number(day.tempMin, 0)}${weather!!.tempUnitSymbol}")
                                day.precipProbabilityMax?.let { append(" · ${it}% precip") }
                            },
                        )
                        state = state.copy(weatherAlertDay = today)
                    }
                }
            }
        }

        // --- Space weather (geomagnetic storm) ---
        if (prefs.spaceAlerts && state.spaceAlertDay != today) {
            runCatching {
                val sw = container.spaceWeatherRepository.fetch(force = true).data
                val kp = sw.kp
                if (kp != null && kp >= 5.0) {
                    notifier.notifySpace(
                        id = 5001,
                        title = "Geomagnetic storm: ${sw.stormLevel}",
                        body = "Planetary Kp ${"%.1f".format(kp)}. Aurora: ${sw.auroraChance}.",
                    )
                    state = state.copy(spaceAlertDay = today)
                }
            }
        }

        // --- Hazardous near-Earth object ---
        if (prefs.spaceAlerts && state.neoAlertDay != today) {
            runCatching {
                val orbital = container.orbitalRepository.fetch(null, null, force = true).data
                val hazard = orbital.neos.firstOrNull { it.hazardous }
                if (hazard != null) {
                    notifier.notifySpace(
                        id = 5002,
                        title = "Close approach: ${orbital.neoHazardousCount} flagged object(s)",
                        body = "${hazard.name.removeSurrounding("(", ")")} passes today" +
                            (hazard.missDistanceKm?.let { " · ${Formatters.compact(it)} km" } ?: ""),
                    )
                    state = state.copy(neoAlertDay = today)
                }
            }
        }

        // --- Daily digest ---
        if (prefs.dailyDigest && hour >= prefs.digestHour && state.lastDigestDay != today) {
            runCatching {
                val lines = buildDigestLines(settings, weather)
                if (lines.isNotEmpty()) {
                    notifier.notifyDigest(
                        id = 4001,
                        title = "Your daily Pulse",
                        body = lines.first(),
                        lines = lines,
                    )
                    state = state.copy(lastDigestDay = today)
                }
            }
        }

        writeState(state)
        return Result.success()
    }

    private suspend fun resolveWeather(settings: AppSettings): WeatherData? {
        // Prefer device location when enabled & permitted; else the selected save.
        if (settings.useDeviceLocation) {
            container.locationProvider.current()?.let { loc ->
                return container.weatherRepository.fetch(loc.latitude, loc.longitude, loc.name, true).data
            }
        }
        val saved = settings.savedLocations.getOrNull(settings.selectedLocationIndex)
            ?: settings.savedLocations.firstOrNull() ?: return null
        return container.weatherRepository.fetch(saved.latitude, saved.longitude, saved.name, true).data
    }

    private suspend fun buildDigestLines(settings: AppSettings, weather: WeatherData?): List<String> {
        val lines = mutableListOf<String>()
        runCatching {
            val top = container.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data
            top.firstOrNull()?.let { lines += "📰 ${it.title}" }
        }
        runCatching {
            val quotes = container.marketsRepository.fetchAll(force = false).data
            val mover = quotes.maxByOrNull { abs(it.changePercent ?: 0.0) }
            mover?.let { lines += "📈 ${it.label} ${Formatters.signedPercent(it.changePercent)}" }
        }
        val w = weather ?: runCatching { resolveWeather(settings) }.getOrNull()
        w?.let { wd ->
            wd.daily.firstOrNull()?.let { d ->
                lines += "🌤️ ${wd.locationName}: ${WeatherCode.describe(d.weatherCode)}, " +
                    "${Formatters.number(d.tempMax, 0)}/${Formatters.number(d.tempMin, 0)}${wd.tempUnitSymbol}"
            }
        }
        runCatching {
            val econ = container.economyRepository.fetchDashboard(force = false).data
            val infl = econ.series.firstOrNull { it.indicatorId == "FP.CPI.TOTL.ZG" }?.latest
            infl?.let { lines += "💹 Inflation (${it.year}): ${Formatters.percent(it.value)}" }
        }
        return lines
    }

    private suspend fun readState(): NotifyState =
        container.diskCache.readAny("notify_state", NotifyState.serializer())?.value ?: NotifyState()

    private suspend fun writeState(state: NotifyState) =
        container.diskCache.write("notify_state", state, NotifyState.serializer())

    private fun dayKey(cal: Calendar): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)

    private fun inQuietHours(enabled: Boolean, start: Int, end: Int, hour: Int): Boolean {
        if (!enabled) return false
        return if (start <= end) hour in start until end
        else hour >= start || hour < end // overnight window
    }

    companion object {
        const val UNIQUE_NAME = "pulse_periodic_refresh"
    }
}
