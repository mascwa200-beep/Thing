package dev.mascwa.pulse.data.space

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** NOAA SWPC space-weather data — keyless JSON. */
class SpaceWeatherRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {
    private val ttl = 15 * 60 * 1000L

    /** [lat]/[lon] optional — when present, the NOAA OVATION aurora probability
     *  for that point is included. */
    suspend fun fetch(force: Boolean, lat: Double? = null, lon: Double? = null): Fetched<SpaceWeather> {
        val key = if (lat != null && lon != null)
            "space_weather_${"%.0f".format(lat)}_${"%.0f".format(lon)}" else "space_weather"
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
                val auroraD = async {
                    if (lat != null && lon != null) runCatching { loadAurora(lat, lon) }.getOrNull() else null
                }

                val kpSeries = kpD.await()
                val kp = kpSeries.lastOrNull()
                SpaceWeather(
                    kp = kp,
                    kpSeries = kpSeries.takeLast(28),
                    solarWindSpeed = windD.await(),
                    bz = bzD.await(),
                    stormLevel = SpaceWeather.stormLevelForKp(kp),
                    auroraChance = SpaceWeather.auroraForKp(kp),
                    auroraProbabilityPct = auroraD.await(),
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

    /** NOAA OVATION aurora probability (%) at the grid point nearest to [lat]/[lon].
     *  The feed is a full integer-degree grid as `[lon(0..359), lat(-90..90), prob]`. */
    private suspend fun loadAurora(lat: Double, lon: Double): Int? {
        val text = http.getString("https://services.swpc.noaa.gov/json/ovation_aurora_latest.json")
        val coords = http.json.parseToJsonElement(text).jsonObject["coordinates"]?.jsonArray ?: return null
        val lonR = (((lon % 360) + 360) % 360).roundToInt() % 360
        val latR = lat.roundToInt().coerceIn(-90, 90)
        // Fast path: lon-major ordering, lat ascending from -90.
        coords.getOrNull(lonR * 181 + (latR + 90))?.jsonArray?.let { row ->
            if (row.getOrNull(0)?.jsonPrimitive?.intOrNull == lonR &&
                row.getOrNull(1)?.jsonPrimitive?.intOrNull == latR
            ) {
                return row.getOrNull(2)?.jsonPrimitive?.intOrNull
            }
        }
        // Fallback: nearest-grid scan.
        var best: Int? = null
        var bestDist = Int.MAX_VALUE
        coords.forEach { el ->
            val a = el.jsonArray
            val clon = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@forEach
            val clat = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return@forEach
            val dLon = minOf((clon - lonR + 360) % 360, (lonR - clon + 360) % 360)
            val d = dLon * dLon + (clat - latR) * (clat - latR)
            if (d < bestDist) { bestDist = d; best = a.getOrNull(2)?.jsonPrimitive?.intOrNull }
        }
        return best
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
