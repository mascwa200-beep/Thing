package dev.mascwa.pulse.data.safety

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.util.Geo
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
 * Citizen-style "Nearby Safety" feed aggregated from free, keyless public
 * hazard sources: USGS earthquakes, GDACS global disasters, and US NWS weather
 * alerts. No proprietary incident feed exists keyless — this is the honest,
 * public-data equivalent. Results sorted by distance and cached for offline.
 */
class SafetyRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {
    private val ttl = 10 * 60 * 1000L

    suspend fun fetch(lat: Double, lon: Double, force: Boolean): Fetched<SafetyResult> {
        val key = "safety_${"%.2f".format(lat)}_${"%.2f".format(lon)}"
        if (!force) {
            cache.read(key, ttl, SafetyResult.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon), true, it.savedAtMs)
            }
        }
        return try {
            val incidents = coroutineScope {
                val quakesD = async { runCatching { usgs(lat, lon) }.getOrDefault(emptyList()) }
                val gdacsD = async { runCatching { gdacs(lat, lon) }.getOrDefault(emptyList()) }
                val nwsD = async { runCatching { nws(lat, lon) }.getOrDefault(emptyList()) }
                val crimeD = async { runCatching { ukCrime(lat, lon) }.getOrDefault(emptyList()) }
                (quakesD.await() + gdacsD.await() + nwsD.await() + crimeD.await())
            }
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.distanceMeters }, { -it.timeEpochMs }))
                .take(60)
            val result = SafetyResult(lat, lon, incidents)
            cache.write(key, result, SafetyResult.serializer())
            Fetched(result, false)
        } catch (e: Exception) {
            cache.readAny(key, SafetyResult.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon), true, it.savedAtMs)
            }
            throw e
        }
    }

    private fun recompute(r: SafetyResult, lat: Double, lon: Double): SafetyResult {
        val updated = r.incidents.map {
            it.copy(
                distanceMeters = Geo.distanceMeters(lat, lon, it.latitude, it.longitude),
                bearing = Geo.bearingDegrees(lat, lon, it.latitude, it.longitude),
            )
        }.sortedBy { it.distanceMeters }
        return r.copy(originLat = lat, originLon = lon, incidents = updated)
    }

    // --- USGS earthquakes (GeoJSON, global, keyless) ---
    private suspend fun usgs(lat: Double, lon: Double): List<Incident> {
        val text = http.getString("https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson")
        val features = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { f ->
            val o = f.jsonObject
            val props = o["properties"]?.jsonObject ?: return@mapNotNull null
            val coords = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val ilon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val ilat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val mag = props["mag"]?.jsonPrimitive?.doubleOrNull
            val place = props["place"]?.jsonPrimitive?.contentOrNull ?: "Earthquake"
            val time = props["time"]?.jsonPrimitive?.longOrNull ?: 0L
            val sev = when {
                (mag ?: 0.0) >= 6.5 -> Severity.EXTREME
                (mag ?: 0.0) >= 5.5 -> Severity.HIGH
                (mag ?: 0.0) >= 4.0 -> Severity.MODERATE
                else -> Severity.LOW
            }
            Incident(
                id = "usgs_${o["id"]?.jsonPrimitive?.contentOrNull ?: "$ilat$ilon$time"}",
                type = IncidentType.EARTHQUAKE.name,
                title = mag?.let { "M%.1f — %s".format(it, place) } ?: place,
                severity = sev.name,
                latitude = ilat, longitude = ilon,
                distanceMeters = Geo.distanceMeters(lat, lon, ilat, ilon),
                bearing = Geo.bearingDegrees(lat, lon, ilat, ilon),
                timeEpochMs = time,
                source = "USGS",
                url = props["url"]?.jsonPrimitive?.contentOrNull,
                magnitude = mag,
            )
        }
    }

    // --- GDACS global disasters (GeoJSON, keyless) ---
    private suspend fun gdacs(lat: Double, lon: Double): List<Incident> {
        val text = http.getString("https://www.gdacs.org/gdacsapi/api/events/geteventlist/MAP")
        val features = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { f ->
            runCatching {
                val o = f.jsonObject
                val props = o["properties"]?.jsonObject ?: return@runCatching null
                val coords = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray
                val ilon = coords?.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
                val ilat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
                val name = props["name"]?.jsonPrimitive?.contentOrNull
                    ?: props["htmldescription"]?.jsonPrimitive?.contentOrNull ?: "Disaster"
                val eventType = props["eventtype"]?.jsonPrimitive?.contentOrNull
                val sev = when (props["alertlevel"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                    "red" -> Severity.EXTREME
                    "orange" -> Severity.HIGH
                    "green" -> Severity.LOW
                    else -> Severity.MODERATE
                }
                val type = if (eventType == "WF") IncidentType.FIRE else IncidentType.DISASTER
                Incident(
                    id = "gdacs_${props["eventid"]?.jsonPrimitive?.contentOrNull ?: "$ilat$ilon"}",
                    type = type.name,
                    title = name.take(120),
                    severity = sev.name,
                    latitude = ilat, longitude = ilon,
                    distanceMeters = Geo.distanceMeters(lat, lon, ilat, ilon),
                    bearing = Geo.bearingDegrees(lat, lon, ilat, ilon),
                    timeEpochMs = parseIso(props["fromdate"]?.jsonPrimitive?.contentOrNull),
                    source = "GDACS",
                )
            }.getOrNull()
        }
    }

    // --- US National Weather Service active alerts (keyless; empty outside US) ---
    private suspend fun nws(lat: Double, lon: Double): List<Incident> {
        val text = http.getString("https://api.weather.gov/alerts/active?point=$lat,$lon")
        val features = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { f ->
            val props = f.jsonObject["properties"]?.jsonObject ?: return@mapNotNull null
            val event = props["event"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val sev = when (props["severity"]?.jsonPrimitive?.contentOrNull) {
                "Extreme" -> Severity.EXTREME
                "Severe" -> Severity.HIGH
                "Moderate" -> Severity.MODERATE
                else -> Severity.LOW
            }
            Incident(
                id = "nws_${f.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: event}",
                type = IncidentType.WEATHER.name,
                title = props["headline"]?.jsonPrimitive?.contentOrNull ?: event,
                severity = sev.name,
                latitude = lat, longitude = lon, // alerts cover the user's area
                distanceMeters = 0.0,
                bearing = 0.0,
                timeEpochMs = parseIso(props["effective"]?.jsonPrimitive?.contentOrNull),
                source = "NWS",
            )
        }
    }

    // --- UK street-level crime (data.police.uk, keyless; England/Wales/NI only) ---
    private suspend fun ukCrime(lat: Double, lon: Double): List<Incident> {
        val text = http.getString("https://data.police.uk/api/crimes-street/all-crime?lat=$lat&lng=$lon")
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.jsonObject
                val loc = o["location"]?.jsonObject ?: return@runCatching null
                val clat = loc["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@runCatching null
                val clon = loc["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@runCatching null
                val category = (o["category"]?.jsonPrimitive?.contentOrNull ?: "crime")
                    .replace('-', ' ').replaceFirstChar { it.uppercase() }
                val street = loc["street"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                val sev = if (category.contains("violen", true) || category.contains("robbery", true) ||
                    category.contains("weapon", true)) Severity.MODERATE else Severity.LOW
                Incident(
                    id = "police_${o["persistent_id"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: "${clat}_${clon}_$category"}",
                    type = IncidentType.CRIME.name,
                    title = category + (street?.let { " · $it" } ?: ""),
                    severity = sev.name,
                    latitude = clat, longitude = clon,
                    distanceMeters = Geo.distanceMeters(lat, lon, clat, clon),
                    bearing = Geo.bearingDegrees(lat, lon, clat, clon),
                    timeEpochMs = parseMonth(o["month"]?.jsonPrimitive?.contentOrNull),
                    source = "Police.uk",
                )
            }.getOrNull()
        }.sortedBy { it.distanceMeters }.take(25)
    }

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)
    private fun parseMonth(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching { monthFmt.parse(s)?.time }.getOrNull() ?: 0L
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val isoZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private fun parseIso(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching { iso.parse(s)?.time }.getOrNull()
            ?: runCatching { isoZ.parse(s.take(19))?.time }.getOrNull() ?: 0L
    }
}
