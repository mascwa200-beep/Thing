package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
        // ⚠️ The key used to carry LATITUDE ONLY, so every place on the same parallel shared one
        // entry — and the payload includes sunrise and sunset, which are a function of longitude
        // as much as latitude. Lisbon and Ankara both sit near 39°N and their sunrise times differ
        // by the better part of two hours; whichever was fetched first was served to the other.
        // Locale.US because a comma-decimal device otherwise writes a differently-spelled key for
        // the same place, quietly orphaning the cache when the device language changes.
        val key = if (latitude != null && longitude != null) {
            String.format(Locale.US, "orbital_%.2f_%.2f", latitude, longitude)
        } else {
            "orbital_na"
        }
        if (!force) {
            cache.read(key, ttl, OrbitalData.serializer())?.let {
                // Recompute moon + planets for the current moment even from cache.
                return Fetched(withSky(it.value, latitude, longitude), true, it.savedAtMs)
            }
        }
        val nasaKey = settings.current().apiKeys.nasaOrDemo
        return try {
            // ⚠️ Each sub-fetch used to swallow its own exception, so the outer catch below could
            // never fire: a total network failure produced an all-empty payload, wrote it over the
            // previous good cache entry, and returned it as a fresh, non-stale, successful result.
            // The sky screen then said "No close approaches catalogued for today." The failures are
            // now counted so the difference between a quiet sky and no answer survives.
            val failures = mutableListOf<Throwable>()
            var attempted = 0
            val data = coroutineScope {
                val issD = async {
                    runCatching { loadIss() }.onFailure { failures += it }.getOrNull()
                }
                val sunD = async {
                    if (latitude != null && longitude != null) {
                        runCatching { loadSun(latitude, longitude) }.onFailure { failures += it }.getOrNull()
                    } else {
                        null // Not attempted rather than failed — no location is not an outage.
                    }
                }
                val neoD = async {
                    runCatching { loadNeos(nasaKey) }.onFailure { failures += it }
                }
                attempted = if (latitude != null && longitude != null) 3 else 2
                val neoResult = neoD.await()
                val neos = neoResult.getOrDefault(emptyList())
                OrbitalData(
                    iss = issD.await(),
                    sun = sunD.await(),
                    moon = MoonPhase.at(),
                    neos = neos,
                    neoHazardousCount = neos.count { it.hazardous },
                    // An empty list means "nothing is coming near" only if we actually asked and
                    // were answered. NASA's DEMO_KEY is rate-limited per IP, so a 429 here is
                    // routine rather than an exotic offline case.
                    neosUnavailable = neoResult.isFailure,
                )
            }
            // Everything failed: this is an outage, not a quiet sky. Throwing hands it to the catch
            // below, which serves the previous good data — and to AsyncLoader, which already knows
            // how to mark that stale and show the reason.
            if (failures.size >= attempted) throw failures.first()
            // A partial failure must not occupy the cache's five-minute window, or the missing parts
            // stay missing until it expires. Only a complete answer is worth remembering.
            if (failures.isEmpty()) cache.write(key, data, OrbitalData.serializer())
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
        val obj = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject
        fun d(k: String) = obj[k]?.jsonPrimitive?.doubleOrNull
        return IssPosition(
            latitude = d("latitude") ?: 0.0,
            longitude = d("longitude") ?: 0.0,
            altitudeKm = d("altitude") ?: 0.0,
            // Seconds since the epoch, and the service's own word for when the fix was taken.
            timestampMs = d("timestamp")?.let { (it * 1000.0).toLong() } ?: 0L,
        )
    }

    private suspend fun loadSun(lat: Double, lon: Double): SunTimes {
        val url = "https://api.sunrise-sunset.org/json?lat=$lat&lng=$lon&formatted=0"
        val text = http.getString(url)
        val obj = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject
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
        val text = http.getString(url)
        val root = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject
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
            // The instant, so the screen can show it in the reader's own zone rather than in UTC.
            val approachMs = ca?.get("epoch_date_close_approach")?.jsonPrimitive?.longOrNull
            NeoObject(name, diameter, miss, vel, hazardous, approach, approachMs)
        }.sortedBy { it.missDistanceKm ?: Double.MAX_VALUE }
    }

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private fun parseIso(s: String): Long? = runCatching { isoFmt.parse(s)?.time }.getOrNull()
}
