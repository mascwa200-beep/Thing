package dev.mascwa.pulse.data.radar

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.util.Geo
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
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
        val key = "radar_${"%.2f".format(lat)}_${"%.2f".format(lon)}"
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
        val arr = http.json.parseToJsonElement(text).jsonObject["ac"]?.jsonArray
            ?: return emptyList<Contact>() to src
        val list = arr.mapNotNull { el ->
            val o = el.jsonObject
            val clat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val clon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val hex = o["hex"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val flight = o["flight"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            val reg = o["r"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            val type = o["t"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            val altFeet = o["alt_baro"]?.jsonPrimitive?.doubleOrNull   // string "ground" → null
            val gsKnots = o["gs"]?.jsonPrimitive?.doubleOrNull
            val track = o["track"]?.jsonPrimitive?.doubleOrNull
            Contact(
                id = "ac_${hex.ifBlank { "$clat$clon" }}",
                label = flight ?: reg ?: hex.uppercase().ifBlank { "UNKNOWN" },
                latitude = clat, longitude = clon,
                altitudeM = altFeet?.let { it * 0.3048 },
                groundSpeedKmh = gsKnots?.let { it * 1.852 },
                trackDeg = track,
                detail = listOfNotNull(reg, type).joinToString(" · "),
                kind = ContactKind.AIRCRAFT.name,
            )
        }
        return list to src
    }

    // --- Live ISS position (keyless) ---
    private suspend fun iss(): Contact? {
        val o = http.json.parseToJsonElement(
            http.getString("https://api.wheretheiss.at/v1/satellites/25544"),
        ).jsonObject
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
        val features = http.json.parseToJsonElement(text).jsonObject["features"]?.jsonArray
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
                label = mag?.let { "M%.1f".format(it) } ?: "QUAKE",
                latitude = ilat, longitude = ilon,
                detail = place,
                kind = ContactKind.QUAKE.name,
            )
        }.take(8)
    }
}
