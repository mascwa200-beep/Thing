package dev.mascwa.pulse.data.space

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** NOAA SWPC space-weather data — keyless JSON. */
class SpaceWeatherRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {
    private val ttl = 15 * 60 * 1000L
    private val key = "space_weather"

    suspend fun fetch(force: Boolean): Fetched<SpaceWeather> {
        if (!force) {
            cache.read(key, ttl, SpaceWeather.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val data = coroutineScope {
                val kpD = async { runCatching { loadKp() }.getOrDefault(emptyList()) }
                val windD = async { runCatching { loadSummary(SOLAR_WIND_SPEED, "WindSpeed") }.getOrNull() }
                val bzD = async { runCatching { loadSummary(SOLAR_WIND_MAG, "Bz") }.getOrNull() }
                val alertsD = async { runCatching { loadAlerts() }.getOrDefault(emptyList()) }

                val kpSeries = kpD.await()
                val kp = kpSeries.lastOrNull()
                SpaceWeather(
                    kp = kp,
                    kpSeries = kpSeries.takeLast(28),
                    solarWindSpeed = windD.await(),
                    bz = bzD.await(),
                    stormLevel = SpaceWeather.stormLevelForKp(kp),
                    auroraChance = SpaceWeather.auroraForKp(kp),
                    alerts = alertsD.await(),
                )
            }
            cache.write(key, data, SpaceWeather.serializer())
            Fetched(data, false)
        } catch (e: Exception) {
            cache.readAny(key, SpaceWeather.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    /** Planetary K-index: array-of-arrays, first row is the header. */
    private suspend fun loadKp(): List<Double> {
        val text = http.getString("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
        val arr = http.json.parseToJsonElement(text).jsonArray
        return arr.drop(1).mapNotNull { row ->
            row.jsonArray.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        }
    }

    /** Simple SWPC summary objects, e.g. {"WindSpeed":"399.0","TimeStamp":...}. */
    private suspend fun loadSummary(url: String, field: String): Double? {
        val text = http.getString(url)
        return http.json.parseToJsonElement(text).jsonObject[field]
            ?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    }

    private suspend fun loadAlerts(): List<SpaceAlert> {
        val text = http.getString("https://services.swpc.noaa.gov/products/alerts.json")
        val arr = http.json.parseToJsonElement(text).jsonArray
        return arr.take(12).mapNotNull { el ->
            val obj = el.jsonObject
            val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val issued = obj["issue_datetime"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = message.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("WARNING") || it.startsWith("ALERT") || it.startsWith("WATCH") || it.startsWith("SUMMARY") }
                ?: message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: "Space weather notice"
            SpaceAlert(title = title.take(120), issued = issued, message = message.trim().take(600))
        }
    }

    companion object {
        private const val SOLAR_WIND_SPEED = "https://services.swpc.noaa.gov/products/summary/solar-wind-speed.json"
        private const val SOLAR_WIND_MAG = "https://services.swpc.noaa.gov/products/summary/solar-wind-mag-field.json"
    }
}
