package dev.mascwa.pulse.data.radar

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.core.telemetry.Sgp4
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.data.orbital.TleRepository
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
import kotlinx.serialization.json.longOrNull

/**
 * TACNET radar feed. Live aircraft come from the keyless community ADS-B
 * networks (adsb.lol primary, adsb.fi fallback) via a radius query around the
 * device's GPS — both are real feeder data, no key required. Nearby USGS
 * earthquakes are merged in as special contacts, and so is the ISS — which is
 * *propagated on the device* rather than fetched, for the reason in
 * [issContact]. Results are cached briefly so the scope still shows the last
 * picture (dimmed, "LINK LOST") when the network drops.
 */
class RadarRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
    private val tle: TleRepository,
) {
    private val ttl = 20 * 1000L          // live-ish; auto-refresh forces past this
    private val fetchNm = 250             // adsb.lol max radius (~463 km)
    private val quakeMaxMeters = 800_000.0

    suspend fun fetch(lat: Double, lon: Double, force: Boolean): Fetched<RadarData> {
        // Locale.US: the default locale renders a comma decimal on much of the planet, which would
        // silently give the same place two different cache keys depending on device settings.
        val key = "radar_${fmt("%.2f", lat)}_${fmt("%.2f", lon)}"
        // Loaded before anything else so every return below can re-place the ISS at the instant it
        // is read, whether the picture is fresh, cached, or the last one before the link dropped.
        val issElements = issElements()
        if (!force) {
            cache.read(key, ttl, RadarData.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon, issElements), true, it.savedAtMs)
            }
        }
        return try {
            val data = coroutineScope {
                // ISS + quakes are best-effort; the aircraft feed is authoritative so a
                // genuine network failure propagates and we fall back to the last picture.
                // The ISS is only fetched when there are no elements to propagate from.
                val issD = async {
                    if (issElements == null) runCatching { iss() }.getOrNull() else null
                }
                val quakeD = async { runCatching { quakes(lat, lon) }.getOrDefault(emptyList()) }
                val (air, src) = aircraft(lat, lon)
                val extras = listOfNotNull(issD.await()) + quakeD.await()
                RadarData(lat, lon, air + extras, source = src)
            }
            cache.write(key, data, RadarData.serializer())
            Fetched(recompute(data, lat, lon, issElements), false)
        } catch (e: Exception) {
            cache.readAny(key, RadarData.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon, issElements), true, it.savedAtMs)
            }
            throw e
        }
    }

    /**
     * Re-place the ISS, then recompute distance + bearing from the current origin and re-sort.
     *
     * ⚠️ Distance and bearing were already recomputed on every read "so the plot stays correct even
     * when served from cache offline" — but that only ever corrected the *observer's* end of the
     * line. The ISS end was whatever position had been stored, and the failure path serves a cached
     * picture with no age limit at all, so the dot could be hours out of place while the scope drew
     * it as a contact. Propagating replaces it with where the station is now.
     */
    private fun recompute(
        d: RadarData,
        lat: Double,
        lon: Double,
        issElements: Sgp4.Elements?,
    ): RadarData {
        val propagated = issElements?.let { issContact(it) }
        val contacts = if (propagated == null) {
            d.contacts
        } else {
            // Replace a stored ISS if there is one, and add it if there is not: a failed position
            // fetch is no longer a reason for the station to be missing from the scope.
            d.contacts.filterNot { it.kind == ContactKind.ISS.name } + propagated
        }
        val updated = contacts.map {
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

    /**
     * The ISS, worked out on the device rather than asked for.
     *
     * The scope's cache is twenty seconds and the failure path has none, while the ground point
     * moves about 416 km a minute — so a fetched position is between one and many dot-widths wrong
     * by the time it is drawn. The element set is cached for twelve hours and shared with the
     * observatory and the Home digest, so this costs no request at all in the ordinary case, has no
     * age, and works with the link down. Null when the elements cannot be propagated, which leaves
     * the fetched path as it was.
     */
    private fun issContact(elements: Sgp4.Elements): Contact? {
        val now = System.currentTimeMillis()
        val propagator = Sgp4.propagator(elements)
        val sub = SatellitePasses.subPoint(propagator, now) ?: return null
        val speedKmS = (propagator.propagateAt(now) as? Sgp4.Propagation.Ok)?.state?.speedKmS
        return Contact(
            id = "iss",
            label = "ISS",
            latitude = sub.latitudeDeg,
            longitude = sub.longitudeDeg,
            altitudeM = sub.altitudeKm * 1000.0,
            groundSpeedKmh = speedKmS?.times(3600.0),
            detail = "Space Station · $ISS_NORAD_ID",
            kind = ContactKind.ISS.name,
        )
    }

    /**
     * The station's elements from whatever is already on disk.
     *
     * Cache-only on purpose — see [TleRepository.cachedElement]. This runs before every scope
     * refresh, and a scope that refreshes every twenty seconds must not be held behind a network
     * call. The observatory and the Home sky digest keep the elements current; on a device that has
     * never had either open with a connection there is nothing cached, and the fetched position
     * below still stands in.
     */
    private suspend fun issElements(): Sgp4.Elements? =
        runCatching { tle.cachedElement(ISS_NORAD_ID) }.getOrNull()

    // --- Live ISS position (keyless), used only when there are no elements to propagate ---
    private suspend fun iss(): Contact? {
        val text = http.getString("https://api.wheretheiss.at/v1/satellites/$ISS_NORAD_ID")
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
            detail = "Space Station · $ISS_NORAD_ID",
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

            fun str(k: String) = props[k]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }
            fun num(k: String) = props[k]?.jsonPrimitive?.doubleOrNull
            fun int(k: String) = props[k]?.jsonPrimitive?.intOrNull

            val mag = num("mag")
            val place = str("place") ?: "Seismic event"
            // Depth is the THIRD coordinate, not a property. Missing it entirely is the easy
            // mistake here, and depth is what decides whether a given magnitude is dangerous.
            val depthKm = coords.getOrNull(2)?.jsonPrimitive?.doubleOrNull

            Contact(
                id = "qk_${o["id"]?.jsonPrimitive?.contentOrNull ?: "$ilat$ilon"}",
                label = mag?.let { fmt("M%.1f", it) } ?: "QUAKE",
                latitude = ilat, longitude = ilon,
                detail = place,
                kind = ContactKind.QUAKE.name,
                magnitude = mag,
                depthKm = depthKm,
                magType = str("magType"),
                pagerAlert = str("alert"),
                tsunami = int("tsunami") == 1,
                feltReports = int("felt"),
                communityIntensity = num("cdi"),
                shakingIntensity = num("mmi"),
                significance = int("sig"),
                reviewStatus = str("status"),
                eventTimeMs = props["time"]?.jsonPrimitive?.longOrNull,
                infoUrl = str("url"),
            )
        }
            // Most significant first, not merely nearest. A magnitude 6 at 700 km matters more
            // than a magnitude 2.6 next door, and the feed's own significance score already folds
            // in size, felt reports and estimated impact.
            .sortedByDescending { it.significance ?: 0 }
            .take(12)
    }

    private companion object {
        /** The station's catalogue number, in the feed URL and in the contact's own detail line. */
        const val ISS_NORAD_ID = 25544
        const val FEET_TO_M = 0.3048
        const val KNOTS_TO_KMH = 1.852
        val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

        /** Locale.US — these strings are numbers, not prose. */
        fun fmt(pattern: String, value: Double): String =
            String.format(java.util.Locale.US, pattern, value)
    }
}
