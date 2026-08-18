package dev.mascwa.pulse.data.safety

import dev.mascwa.pulse.core.network.HttpException
import dev.mascwa.pulse.core.telemetry.SafetyCoverage
import dev.mascwa.pulse.core.telemetry.CapAlerts
import dev.mascwa.pulse.core.telemetry.Seismic
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
import kotlinx.serialization.json.intOrNull
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
        // Locale.US so the key is stable: a comma-decimal device would otherwise write a
        // different key for the same place, and stop finding its own cached result.
        val key = "safety_" + String.format(Locale.US, "%.2f_%.2f", lat, lon)
        if (!force) {
            cache.read(key, ttl, SafetyResult.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon), true, it.savedAtMs)
            }
        }
        return try {
            // Each source's outcome is kept, not swallowed. `runCatching{}.getOrDefault(emptyList())`
            // made a failed fetch identical to an empty one, and both identical to a source that does
            // not operate here — so the screen asserted "nothing reported nearby" in all three cases.
            val states = mutableMapOf<SafetyCoverage.Source, SafetyCoverage.Availability>()
            val incidents = coroutineScope {
                val quakesD = async { runCatching { usgs(lat, lon) } }
                val gdacsD = async { runCatching { gdacs(lat, lon) } }
                val nwsD = async { runCatching { nws(lat, lon) } }
                val crimeD = async { runCatching { ukCrime(lat, lon) } }

                val quakes = quakesD.await()
                val disasters = gdacsD.await()
                val alerts = nwsD.await()
                val crime = crimeD.await()

                // Both of these are worldwide, so any absence of results is real.
                states[SafetyCoverage.Source.QUAKES] = availability(quakes.isSuccess)
                states[SafetyCoverage.Source.DISASTERS] = availability(disasters.isSuccess)

                // The weather feed states its own reach: outside the United States it answers 400
                // "out of bounds" rather than an empty list, so this needs no geographic guess.
                states[SafetyCoverage.Source.WEATHER_ALERTS] = when {
                    alerts.isSuccess -> SafetyCoverage.Availability.COVERED
                    (alerts.exceptionOrNull() as? HttpException)?.code == 400 ->
                        SafetyCoverage.Availability.NOT_COVERED
                    else -> SafetyCoverage.Availability.FAILED
                }

                // The crime feed does not: Berlin, Edinburgh and a quiet English village all get
                // `200 []`, so geography is the only signal there is. A failure still overrides it —
                // we would rather say "couldn't reach it" than "it doesn't cover you" when the truth
                // is that we never got an answer.
                states[SafetyCoverage.Source.STREET_CRIME] =
                    if (crime.isFailure) SafetyCoverage.Availability.FAILED
                    else SafetyCoverage.crimeCoverage(lat, lon)

                listOf(quakes, disasters, alerts, crime).flatMap { it.getOrDefault(emptyList()) }
            }
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.distanceMeters }, { -it.timeEpochMs }))
                .take(60)
            val result = SafetyResult(
                lat, lon, incidents,
                sourceStates = states.entries.associate { (k, v) -> k.name to v.name },
            )
            cache.write(key, result, SafetyResult.serializer())
            Fetched(result, false)
        } catch (e: Exception) {
            cache.readAny(key, SafetyResult.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon), true, it.savedAtMs)
            }
            throw e
        }
    }

    /** A worldwide source either answered or did not; there is no third state for it. */
    private fun availability(ok: Boolean) =
        if (ok) SafetyCoverage.Availability.COVERED else SafetyCoverage.Availability.FAILED

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
            // Depth is the third coordinate, not a property, which is how it came to be missed.
            // It matters more than almost anything else here: in a single day's feed the events
            // ranged from 1 km to 564 km down, and a 564 km event is barely felt at the surface.
            val depth = coords.getOrNull(2)?.jsonPrimitive?.doubleOrNull
            val tsunami = (props["tsunami"]?.jsonPrimitive?.intOrNull ?: 0) == 1
            val pager = props["alert"]?.jsonPrimitive?.contentOrNull
            // Grading lives in the CI-tested core, so the notification gate, the map colour and
            // the list badge cannot drift apart, and so the rules are provable.
            val sev = when (Seismic.alertLevel(mag, depth, tsunami, pager)) {
                Seismic.Alert.EXTREME -> Severity.EXTREME
                Seismic.Alert.HIGH -> Severity.HIGH
                Seismic.Alert.MODERATE -> Severity.MODERATE
                Seismic.Alert.LOW -> Severity.LOW
            }
            Incident(
                id = "usgs_${o["id"]?.jsonPrimitive?.contentOrNull ?: "$ilat$ilon$time"}",
                type = IncidentType.EARTHQUAKE.name,
                // Locale.US: a magnitude is a number, and a comma decimal reads as a different
                // one. RadarRepository renders the same value from the same feed and already
                // pins it, so without this the two screens disagree on the same earthquake.
                title = mag?.let { String.format(Locale.US, "M%.1f — %s", it, place) } ?: place,
                severity = sev.name,
                latitude = ilat, longitude = ilon,
                distanceMeters = Geo.distanceMeters(lat, lon, ilat, ilon),
                bearing = Geo.bearingDegrees(lat, lon, ilat, ilon),
                timeEpochMs = time,
                source = "USGS",
                url = props["url"]?.jsonPrimitive?.contentOrNull,
                magnitude = mag,
                depthKm = depth,
                tsunami = tsunami,
                pagerAlert = pager,
                significance = props["sig"]?.jsonPrimitive?.intOrNull,
                magType = props["magType"]?.jsonPrimitive?.contentOrNull,
                feltReports = props["felt"]?.jsonPrimitive?.intOrNull,
                shakingIntensity = props["mmi"]?.jsonPrimitive?.doubleOrNull,
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
            // CAP grades an alert on three axes and this read one, so a "might happen tomorrow"
            // watch and a "happening now, we can see it" warning graded identically.
            val urgency = props["urgency"]?.jsonPrimitive?.contentOrNull
            val certainty = props["certainty"]?.jsonPrimitive?.contentOrNull
            val sev = when (CapAlerts.grade(props["severity"]?.jsonPrimitive?.contentOrNull, urgency, certainty)) {
                CapAlerts.Grade.EXTREME -> Severity.EXTREME
                CapAlerts.Grade.HIGH -> Severity.HIGH
                CapAlerts.Grade.MODERATE -> Severity.MODERATE
                CapAlerts.Grade.LOW -> Severity.LOW
            }
            val expires = parseIso(props["expires"]?.jsonPrimitive?.contentOrNull)
            // The endpoint is called "active", but this result is cached and served from cache for
            // as long as it is all there is offline. Without this an alert that ended hours ago is
            // presented as a current danger.
            if (CapAlerts.hasExpired(expires.takeIf { it > 0L }, System.currentTimeMillis())) {
                return@mapNotNull null
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
                // The field that says what to do, present on 78 of 80 live alerts and previously
                // parsed away — in the safety feature.
                instruction = CapAlerts.instruction(props["instruction"]?.jsonPrimitive?.contentOrNull),
                timing = CapAlerts.timing(urgency, certainty),
                expiresEpochMs = expires.takeIf { it > 0L },
                areaDescription = props["areaDesc"]?.jsonPrimitive?.contentOrNull?.take(120),
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
