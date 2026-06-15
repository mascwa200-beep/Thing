package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Orbital bodies: ISS live position (wheretheiss.at), Sun rise/set
 * (sunrise-sunset.org), offline Moon phase, and near-Earth objects (NASA NeoWs).
 * All keyless except NeoWs, which falls back to the shared DEMO_KEY.
 */
class OrbitalRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
    private val settings: SettingsRepository,
) {
    private val ttl = 5 * 60 * 1000L

    suspend fun fetch(latitude: Double?, longitude: Double?, force: Boolean): Fetched<OrbitalData> {
        val key = "orbital_${latitude?.let { "%.2f".format(it) } ?: "na"}"
        if (!force) {
            cache.read(key, ttl, OrbitalData.serializer())?.let {
                // Recompute moon + planets for the current moment even from cache.
                return Fetched(withSky(it.value, latitude, longitude), true, it.savedAtMs)
            }
        }
        val nasaKey = settings.current().apiKeys.nasaOrDemo
        return try {
            val data = coroutineScope {
                val issD = async { runCatching { loadIss() }.getOrNull() }
                val sunD = async {
                    if (latitude != null && longitude != null)
                        runCatching { loadSun(latitude, longitude) }.getOrNull() else null
                }
                val neoD = async { runCatching { loadNeos(nasaKey) }.getOrDefault(emptyList()) }
                val neos = neoD.await()
                OrbitalData(
                    iss = issD.await(),
                    sun = sunD.await(),
                    moon = MoonPhase.at(),
                    neos = neos,
                    neoHazardousCount = neos.count { it.hazardous },
                )
            }
            cache.write(key, data, OrbitalData.serializer())
            Fetched(withSky(data, latitude, longitude), false)
        } catch (e: Exception) {
            cache.readAny(key, OrbitalData.serializer())?.let {
                return Fetched(withSky(it.value, latitude, longitude), true, it.savedAtMs)
            }
            throw e
        }
    }

    /** Attach freshly-computed moon + planets (pure, no network). */
    private fun withSky(data: OrbitalData, lat: Double?, lon: Double?): OrbitalData = data.copy(
        moon = MoonPhase.at(),
        planets = if (lat != null && lon != null) PlanetCalc.planetsNow(lat, lon) else emptyList(),
    )

    private suspend fun loadIss(): IssPosition {
        val text = http.getString("https://api.wheretheiss.at/v1/satellites/25544")
        val obj = http.json.parseToJsonElement(text).jsonObject
        fun d(k: String) = obj[k]?.jsonPrimitive?.doubleOrNull
        return IssPosition(
            latitude = d("latitude") ?: 0.0,
            longitude = d("longitude") ?: 0.0,
            altitudeKm = d("altitude") ?: 0.0,
            velocityKmh = d("velocity") ?: 0.0,
        )
    }

    private suspend fun loadSun(lat: Double, lon: Double): SunTimes {
        val url = "https://api.sunrise-sunset.org/json?lat=$lat&lng=$lon&formatted=0"
        val obj = http.json.parseToJsonElement(http.getString(url)).jsonObject
        val results = obj["results"]?.jsonObject ?: return SunTimes(null, null, null)
        fun iso(k: String) = results[k]?.jsonPrimitive?.contentOrNull?.let(::parseIso)
        val dayLen = results["day_length"]?.jsonPrimitive?.let {
            it.contentOrNull?.toDoubleOrNull()?.toLong()
        }
        return SunTimes(iso("sunrise"), iso("sunset"), dayLen)
    }

    private suspend fun loadNeos(apiKey: String): List<NeoObject> {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(System.currentTimeMillis())
        val url = "https://api.nasa.gov/neo/rest/v1/feed?start_date=$today&end_date=$today&api_key=$apiKey"
        val root = http.json.parseToJsonElement(http.getString(url)).jsonObject
        val byDate = root["near_earth_objects"]?.jsonObject ?: return emptyList()
        val list = byDate[today]?.jsonArray ?: byDate.values.firstOrNull()?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val diameter = o["estimated_diameter"]?.jsonObject
                ?.get("meters")?.jsonObject?.get("estimated_diameter_max")?.jsonPrimitive?.doubleOrNull
            val hazardous = o["is_potentially_hazardous_asteroid"]?.jsonPrimitive?.contentOrNull == "true"
            val ca = o["close_approach_data"]?.jsonArray?.firstOrNull()?.jsonObject
            val miss = ca?.get("miss_distance")?.jsonObject?.get("kilometers")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            val vel = ca?.get("relative_velocity")?.jsonObject?.get("kilometers_per_hour")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            val approach = ca?.get("close_approach_date_full")?.jsonPrimitive?.contentOrNull
            NeoObject(name, diameter, miss, vel, hazardous, approach)
        }.sortedBy { it.missDistanceKm ?: Double.MAX_VALUE }
    }

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private fun parseIso(s: String): Long? = runCatching { isoFmt.parse(s)?.time }.getOrNull()
}
