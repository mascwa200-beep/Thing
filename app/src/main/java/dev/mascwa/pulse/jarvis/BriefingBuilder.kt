package dev.mascwa.pulse.jarvis

import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.news.NewsRepository
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.objectives.CalendarObjectivesRepository
import dev.mascwa.pulse.data.objectives.WaypointStore
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.data.weather.WeatherRepository
import kotlinx.coroutines.flow.firstOrNull
import java.util.Calendar
import kotlin.math.abs

/**
 * Assembles J.A.R.V.I.S.'s spoken "brief" from live, on-device data: weather, today's objectives
 * (calendar + tracked waypoint), the top headline and the day's biggest market move. Every section is
 * best-effort — a failing source is simply skipped, so the brief always returns something.
 */
class BriefingBuilder(
    private val weather: WeatherRepository,
    private val news: NewsRepository,
    private val markets: MarketsRepository,
    private val calendar: CalendarObjectivesRepository,
    private val waypoints: WaypointStore,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
) {
    suspend fun build(): String {
        val s = runCatching { settings.current() }.getOrNull()
        val parts = mutableListOf(greeting())

        runCatching {
            val wd = resolveWeather(s)
            wd?.daily?.firstOrNull()?.let { d ->
                val unit = wd.tempUnitSymbol
                val rain = d.precipProbabilityMax?.let { ", $it% chance of rain" } ?: ""
                parts += "Weather: ${WeatherCode.describe(d.weatherCode)}, high " +
                    "${Formatters.number(d.tempMax, 0)}$unit, low ${Formatters.number(d.tempMin, 0)}$unit$rain."
            }
        }

        runCatching {
            val active = waypoints.active.firstOrNull()
            val manual = waypoints.waypoints.firstOrNull()?.size ?: 0
            val cal = runCatching { calendar.upcoming(1) }.getOrDefault(emptyList()).size
            if (active != null) parts += "You're tracking \"${active.label}\"."
            val total = manual + cal
            if (total > 0) parts += "$total objective${if (total == 1) "" else "s"} on the board."
        }

        runCatching {
            news.fetchCategory(NewsCategory.TOP, force = false).data.firstOrNull()?.let {
                parts += "Top story: ${it.title}."
            }
        }

        runCatching {
            val quotes = markets.fetchAll(force = false).data
            quotes.maxByOrNull { abs(it.changePercent ?: 0.0) }?.let {
                parts += "Markets: ${it.label} ${Formatters.signedPercent(it.changePercent)}."
            }
        }

        if (parts.size == 1) parts += "Nothing notable to report — all quiet."
        return parts.joinToString(" ")
    }

    private fun greeting(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val tod = when {
            h < 12 -> "Good morning"
            h < 18 -> "Good afternoon"
            else -> "Good evening"
        }
        return "$tod. Here's your briefing."
    }

    private suspend fun resolveWeather(s: AppSettings?): WeatherData? {
        if (s == null) return null
        if (s.useDeviceLocation) {
            location.current()?.let { return weather.fetch(it.latitude, it.longitude, it.name, force = false).data }
        }
        val saved = s.savedLocations.getOrNull(s.selectedLocationIndex) ?: s.savedLocations.firstOrNull() ?: return null
        return weather.fetch(saved.latitude, saved.longitude, saved.name, force = false).data
    }
}
