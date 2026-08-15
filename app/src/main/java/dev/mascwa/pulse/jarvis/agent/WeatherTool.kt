package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.AirQualityGuide
import dev.mascwa.pulse.core.telemetry.WeatherComfort
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.data.weather.WeatherRepository

/**
 * Lets J.A.R.V.I.S. answer "what's the weather?" from live data, reusing the same location resolution the
 * daily brief uses: an optional place name, else the device location, else the saved location. Read-only.
 */
class WeatherTool(
    private val weather: WeatherRepository,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
) : JarvisTool {
    override val name = "weather"
    override val usage = "weather [place] — current conditions + today's outlook (blank = your location)"

    override suspend fun run(arg: String): String {
        val place = arg.trim()
        val wd = runCatching { resolve(place) }.getOrNull()
            ?: return if (place.isBlank()) {
                "I couldn't get a location for the weather, sir — grant location or set one in Settings."
            } else {
                "I couldn't find \"$place\", sir."
            }
        val unit = wd.tempUnitSymbol
        val now = wd.current
        val today = wd.daily.firstOrNull()
        val out = buildString {
            append(wd.locationName).append(": ")
            if (now != null) {
                append(WeatherCode.describe(now.weatherCode))
                now.temperature?.let { append(", ").append(Formatters.number(it, 0)).append(unit) }
                // "Feels like" only when it diverges enough to matter.
                val feels = now.apparentTemperature
                val temp = now.temperature
                if (feels != null && temp != null && kotlin.math.abs(feels - temp) >= 3) {
                    append(" (feels ").append(Formatters.number(feels, 0)).append(unit).append(")")
                }
                now.windSpeed?.let { append(", wind ").append(Formatters.number(it, 0)).append(" ").append(wd.windUnitSymbol) }
                append(".")
                // What the conditions do to a person, which is usually what was really being asked.
                // Every index returns null outside the range it is fitted for, so a mild day adds
                // nothing here rather than padding the answer.
                now.temperatureC?.let { t ->
                    WeatherComfort.headline(
                        temperatureC = t,
                        humidityPercent = now.humidity,
                        windKmh = now.windKmh,
                        gustKmh = now.gustKmh,
                        dewPointC = now.dewPointC,
                        unitSymbol = unit,
                    )?.let { append(" ").append(it) }
                }
                WeatherComfort.mugginess(now.dewPointC)
                    ?.takeIf { it == "Oppressive" || it == "Very humid" }
                    ?.let { append(" Air is ").append(it.lowercase()).append(".") }
            }
            if (today != null) {
                append(" Today ").append(Formatters.number(today.tempMax, 0)).append(unit)
                    .append("/").append(Formatters.number(today.tempMin, 0)).append(unit)
                today.precipProbabilityMax?.let { append(", ").append(it).append("% rain") }
                append(".")
            }
            // Air quality, named by what is actually driving it rather than by an index number.
            // "US AQI 118" tells you nothing you can act on; "ozone is 1.4x its guideline" tells
            // you to stay in this afternoon rather than to shut the windows.
            wd.airQuality?.let { aq ->
                val readings = listOfNotNull(
                    AirQualityGuide.assess(AirQualityGuide.Pollutant.PM2_5, aq.pm25),
                    AirQualityGuide.assess(AirQualityGuide.Pollutant.PM10, aq.pm10),
                    AirQualityGuide.assess(AirQualityGuide.Pollutant.OZONE, aq.ozone),
                    AirQualityGuide.assess(AirQualityGuide.Pollutant.NITROGEN_DIOXIDE, aq.nitrogenDioxide),
                    AirQualityGuide.assess(AirQualityGuide.Pollutant.SULPHUR_DIOXIDE, aq.sulphurDioxide),
                )
                val driver = AirQualityGuide.dominant(readings)
                if (driver != null && driver.band != AirQualityGuide.Band.WELL_UNDER &&
                    driver.band != AirQualityGuide.Band.WITHIN
                ) {
                    append(" ").append(AirQualityGuide.summary(readings))
                }
            }
        }.trim()
        return out.ifBlank { "No weather available for ${wd.locationName}, sir." }
    }

    private suspend fun resolve(place: String): WeatherData? {
        if (place.isNotBlank()) {
            val ll = location.geocode(place) ?: return null
            return weather.fetch(ll.first, ll.second, place, force = false).data
        }
        val s = settings.current()
        if (s.useDeviceLocation) {
            location.current()?.let { return weather.fetch(it.latitude, it.longitude, it.name, force = false).data }
        }
        val saved = s.savedLocations.getOrNull(s.selectedLocationIndex) ?: s.savedLocations.firstOrNull() ?: return null
        return weather.fetch(saved.latitude, saved.longitude, saved.name, force = false).data
    }
}
