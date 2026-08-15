package dev.mascwa.pulse.data.radar

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.util.Geo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * TACNET radar feed. Live aircraft come from the keyless community ADS-B
 * networks (adsb.lol primary, adsb.fi fallback) via a radius query around the
 * device's GPS — both are real feeder data, no key required. The live ISS
 * (wheretheiss.at) and nearby USGS earthquakes are merged in as special
 * contacts. Results are cached briefly so the scope still shows the last
 * picture (dimmed, "LINK LOST") when the network drops.
 */
class RadarRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {
    private val ttl = 20 * 1000L          // live-ish; auto-refresh forces past this
    private val fetchNm = 250             // adsb.lol max radius (~463 km)
    private val quakeMaxMeters = 800_000.0

    suspend fun fetch(lat: Double, lon: Double, force: Boolean): Fetched<RadarData> {
        // Locale.US: the default locale renders a comma decimal on much of the planet, which would
        // silently give the same place two different cache keys depending on device settings.
        val key = "radar_${fmt("%.2f", lat)}_${fmt("%.2f", lon)}"
        if (!force) {
            cache.read(key, ttl, RadarData.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon), true, it.savedAtMs)
            }
        }
        return try {
            val data = coroutineScope {
                // ISS + quakes are best-effort; the aircraft feed is authoritative so a
                // genuine network failure propagates and we fall back to the last picture.
                val issD = async { runCatching { iss() }.getOrNull() }
                val quakeD = async { runCatching { quakes(lat, lon) }.getOrDefault(emptyList()) }
                val (air, src) = aircraft(lat, lon)
                val extras = listOfNotNull(issD.await()) + quakeD.await()
                RadarData(lat, lon, air + extras, source = src)
            }
            cache.write(key, data, RadarData.serializer())
            Fetched(recompute(data, lat, lon), false)
        } catch (e: Exception) {
            cache.readAny(key, RadarData.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon), true, it.savedAtMs)
            }
            throw e
        }
    }

    /** Recompute distance + bearing from the current origin and re-sort. */
    private fun recompute(d: RadarData, lat: Double, lon: Double): RadarData {
        val updated = d.contacts.map {
            it.copy(
                distanceMeters = Geo.distanceMeters(lat, lon, it.latitude, it.longitude),
                bearingDeg = Geo.bearingDegrees(lat, lon, it.latitude, it.longitude),
            )
        }.sortedBy { it.distanceMeters }
        return d.copy(originLat = lat, originLon = lon, contacts = updated)
    }

    // --- Live aircraft (keyless community ADS-B) ---
    private suspend fun aircraft(lat: Double, lon: Double): Pair<List<Contact>, String> {
        val (text, src) = try {
            http.getString("https://api.adsb.lol/v2/lat/$lat/lon/$lon/dist/$fetchNm") to "adsb.lol"
        } catch (_: Exception) {
            http.getString("https://opendata.adsb.fi/api/v2/lat/$lat/lon/$lon/dist/$fetchNm") to "adsb.fi"
        }
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject["ac"]?.jsonArray
            ?: return emptyList<Contact>() to src
        val list = arr.mapNotNull { el ->
            val o = el.jsonObject
            fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            fun num(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
            fun int(k: String) = o[k]?.jsonPrimitive?.intOrNull
            fun flag(k: String) = int(k) == 1

            val clat = num("lat") ?: return@mapNotNull null
            val clon = num("lon") ?: return@mapNotNull null
            val hex = str("hex").orEmpty()
            val flight = str("flight")
            val reg = str("r")
            val type = str("t")
            // alt_baro is the string "ground" for an aircraft on the surface, so a null here is
            // genuinely ambiguous — it means either "on the ground" or "not reported".
            val rawAlt = o["alt_baro"]?.jsonPrimitive?.contentOrNull?.trim()
            val onGround = rawAlt.equals("ground", ignoreCase = true)
            val altFeet = num("alt_baro")
            val gsKnots = num("gs")
            val squawk = str("squawk")
            val dbFlags = int("dbFlags") ?: 0

            Contact(
                id = "ac_${hex.ifBlank { "$clat$clon" }}",
                label = flight ?: reg ?: hex.uppercase().ifBlank { "UNKNOWN" },
                latitude = clat, longitude = clon,
                altitudeM = altFeet?.let { it * FEET_TO_M },
                groundSpeedKmh = gsKnots?.let { it * KNOTS_TO_KMH },
                trackDeg = num("track"),
                detail = listOfNotNull(reg, type).joinToString(" · "),
                kind = ContactKind.AIRCRAFT.name,
                squawk = squawk,
                verticalRateFpm = num("baro_rate")?.toInt(),
                category = str("category"),
                military = dbFlags and 0x1 == 0x1,
                emergency = squawk in EMERGENCY_SQUAWKS,

                registration = reg,
                typeCode = type,
                description = str("desc"),
                operator = str("ownOp"),

                altitudeGeomM = num("alt_geom")?.let { it * FEET_TO_M },
                verticalRateGeomFpm = num("geom_rate")?.toInt(),
                trueHeadingDeg = num("true_heading"),
                onGround = onGround,

                selectedAltitudeFt = int("nav_altitude_mcp"),
                selectedHeadingDeg = num("nav_heading"),
                qnhHpa = num("nav_qnh"),
                // `as?` rather than `.jsonArray`: the accessor throws on a non-array, and one
                // oddly-shaped aircraft must not take down the whole sweep.
                navModes = (o["nav_modes"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty(),

                rssiDb = num("rssi"),
                messageCount = int("messages"),
                seenSec = num("seen"),
                seenPosSec = num("seen_pos"),
                sourceType = str("type"),

                interesting = dbFlags and 0x2 == 0x2,
                pia = dbFlags and 0x4 == 0x4,
                ladd = dbFlags and 0x8 == 0x8,

                emergencyText = str("emergency"),
                alert = flag("alert"),
                ident = flag("spi"),
            )
        }
        return list to src
    }

    // --- Live ISS position (keyless) ---
    private suspend fun iss(): Contact? {
        val text = http.getString("https://api.wheretheiss.at/v1/satellites/25544")
        val o = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject
        fun d(k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        val clat = d("latitude") ?: return null
        val clon = d("longitude") ?: return null
        return Contact(
            id = "iss",
            label = "ISS",
            latitude = clat, longitude = clon,
            altitudeM = (d("altitude") ?: 0.0) * 1000.0,
            groundSpeedKmh = d("velocity"),
            detail = "Space Station · 25544",
            kind = ContactKind.ISS.name,
        )
    }

    // --- Nearby earthquakes (USGS, keyless) ---
    private suspend fun quakes(lat: Double, lon: Double): List<Contact> {
        val text = http.getString(
            "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson",
        )
        val features = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject["features"]?.jsonArray
            ?: return emptyList()
        return features.mapNotNull { f ->
            val o = f.jsonObject
            val props = o["properties"]?.jsonObject ?: return@mapNotNull null
            val coords = o["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return@mapNotNull null
            val ilon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val ilat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (Geo.distanceMeters(lat, lon, ilat, ilon) > quakeMaxMeters) return@mapNotNull null
            val mag = props["mag"]?.jsonPrimitive?.doubleOrNull
            val place = props["place"]?.jsonPrimitive?.contentOrNull ?: "Seismic event"
            Contact(
                id = "qk_${o["id"]?.jsonPrimitive?.contentOrNull ?: "$ilat$ilon"}",
                label = mag?.let { fmt("M%.1f", it) } ?: "QUAKE",
                latitude = ilat, longitude = ilon,
                detail = place,
                kind = ContactKind.QUAKE.name,
            )
        }.take(8)
    }

    private companion object {
        const val FEET_TO_M = 0.3048
        const val KNOTS_TO_KMH = 1.852
        val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

        /** Locale.US — these strings are numbers, not prose. */
        fun fmt(pattern: String, value: Double): String =
            String.format(java.util.Locale.US, pattern, value)
    }
}
