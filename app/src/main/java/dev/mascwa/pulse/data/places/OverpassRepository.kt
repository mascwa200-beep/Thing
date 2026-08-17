package dev.mascwa.pulse.data.places

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.PoiSearch
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.util.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Nearest places via the keyless OpenStreetMap Overpass API. One repository
 * powers every "nearest X" feature. Results are cached per category + rounded
 * location so the list (with live distance/bearing) survives offline.
 */
class OverpassRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {
    private val ttl = 6 * 60 * 60 * 1000L // 6h — POIs change slowly

    suspend fun fetch(
        category: PlaceCategory,
        lat: Double,
        lon: Double,
        force: Boolean,
    ): Fetched<PlacesResult> =
        fetch(category.name, category.overpassFilter, category.radiusMeters, category.title.dropLast(1), lat, lon, force)

    /**
     * Generic nearest-POI fetch for any OSM tag [filter] (e.g. `["amenity"="fuel"]`). [id] keys the
     * cache and labels the result; [fallbackName] names unnamed POIs. Used by the NAV map's category
     * layers without polluting [PlaceCategory] (which drives the survival "nearest places" screen).
     */
    suspend fun fetch(
        id: String,
        filter: String,
        radiusMeters: Int,
        fallbackName: String,
        lat: Double,
        lon: Double,
        force: Boolean,
    ): Fetched<PlacesResult> {
        // Locale.US: the default renders a comma decimal on much of the planet, which would give
        // one place two different cache keys depending on device settings.
        val key = "places_${id}_${fmt(lat)}_${fmt(lon)}"
        if (!force) {
            cache.read(key, ttl, PlacesResult.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon), true, it.savedAtMs)
            }
        }
        return try {
            val result = load(id, filter, radiusMeters, fallbackName, lat, lon)
            cache.write(key, result, PlacesResult.serializer())
            Fetched(result, false)
        } catch (e: Exception) {
            cache.readAny(key, PlacesResult.serializer())?.let {
                return Fetched(recompute(it.value, lat, lon), true, it.savedAtMs)
            }
            throw e
        }
    }

    /**
     * Search outward (or inward) until the response is one we can honestly rank.
     *
     * Overpass fills a quota in its own order — type, then id — and never by distance, so a quota
     * that binds hands back an arbitrary slice. [PoiSearch] turns that into a rule: pick the radius
     * so the quota does not bind, and then every match inside the circle is present and the nearest
     * of them really are the nearest. See its KDoc for the measurement behind it.
     */
    private suspend fun load(
        id: String,
        filter: String,
        radiusMeters: Int,
        fallbackName: String,
        lat: Double,
        lon: Double,
    ): PlacesResult {
        var radius = PoiSearch.startRadius(radiusMeters)
        var best: PlacesResult? = null
        var bestRadius = radius
        var truncated = false

        repeat(PoiSearch.MAX_PROBES) {
            val (result, returned) = probe(id, filter, radius, fallbackName, lat, lon)
            truncated = PoiSearch.capBound(returned)
            best = result
            // ⚠️ Kept separately from `radius`, which is about to become the radius we will try
            // NEXT. Reporting that one would label the data with a circle it never came from.
            bestRadius = radius
            val next = PoiSearch.nextRadius(
                PoiSearch.Probe(radius, returned),
                maxRadius = radiusMeters,
            ) ?: return result.copy(truncated = truncated, searchRadiusMeters = radius)
            radius = next
        }
        // Ran out of probes. Whatever the last one gave is the answer, flagged for what it is.
        return (best ?: PlacesResult(id, lat, lon, emptyList()))
            .copy(truncated = truncated, searchRadiusMeters = bestRadius)
    }

    /** One round trip. Returns the parsed places and the raw element count the quota saw. */
    private suspend fun probe(
        id: String,
        filter: String,
        radiusMeters: Int,
        fallbackName: String,
        lat: Double,
        lon: Double,
    ): Pair<PlacesResult, Int> {
        val ql = """
            [out:json][timeout:25];
            (
              node$filter(around:$radiusMeters,$lat,$lon);
              way$filter(around:$radiusMeters,$lat,$lon);
            );
            out center ${PoiSearch.HARD_CAP};
        """.trimIndent()
        val url = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(ql, "UTF-8")
        // One gate per host. Overpass is a free community endpoint and the NAV map scans several
        // categories at once; firing them all simultaneously is exactly the behaviour that earns an
        // IP a ban, which this project has already had happen once with another provider.
        val text = gate.withPermit { http.getString(url) }
        val root = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject

        // ⚠️ An Overpass runtime error is HTTP 200 with an `elements: []` and a `remark` saying what
        // went wrong, so nothing throws and the old code read it as "there is no hospital near you"
        // — then cached that for six hours. On a safety screen that is the worst possible failure
        // mode, so a remark is raised as the exception it always was.
        root["remark"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
            throw OverpassRemarkException(it)
        }
        val elements = root["elements"]?.jsonArray
            ?: return PlacesResult(id, lat, lon, emptyList()) to 0

        val places = elements.mapNotNull { el ->
            val o = el.jsonObject
            val plat = o["lat"]?.jsonPrimitive?.doubleOrNull
                ?: o["center"]?.jsonObject?.get("lat")?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val plon = o["lon"]?.jsonPrimitive?.doubleOrNull
                ?: o["center"]?.jsonObject?.get("lon")?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val tags = o["tags"]?.jsonObject
            val name = tags?.get("name")?.jsonPrimitive?.contentOrNull
                ?: tags?.get("operator")?.jsonPrimitive?.contentOrNull
                ?: fallbackName
            val phone = (tags?.get("phone") ?: tags?.get("contact:phone"))?.jsonPrimitive?.contentOrNull
            val address = buildAddress(tags)
            Place(
                name = name,
                latitude = plat,
                longitude = plon,
                distanceMeters = Geo.distanceMeters(lat, lon, plat, plon),
                bearing = Geo.bearingDegrees(lat, lon, plat, plon),
                phone = phone,
                address = address,
                // On the wire for 100% of results and thrown away until now, which left a GP
                // surgery and a major hospital rendering identically on a screen you would read
                // in an emergency.
                kind = tags?.get("amenity")?.jsonPrimitive?.contentOrNull
                    ?: tags?.get("healthcare")?.jsonPrimitive?.contentOrNull,
                emergency = tags?.get("emergency")?.jsonPrimitive?.contentOrNull,
                openingHours = tags?.get("opening_hours")?.jsonPrimitive?.contentOrNull,
                website = (tags?.get("website") ?: tags?.get("contact:website"))
                    ?.jsonPrimitive?.contentOrNull,
            )
        }
            .distinctBy { "${it.name}_${fmt(it.latitude, 4)}" }
            .sortedBy { it.distanceMeters }
            .take(PoiSearch.WANT)

        return PlacesResult(id, lat, lon, places) to elements.size
    }

    /**
     * Overpass reported a server-side failure inside a 200 response.
     *
     * Thrown so the existing catch in [fetch] serves the previous cache rather than writing an
     * empty list over it — an outage must not be recorded as "nothing here".
     */
    class OverpassRemarkException(remark: String) : Exception("Overpass: $remark")

    /** Re-derive distance/bearing from the current location for cached results. */
    private fun recompute(result: PlacesResult, lat: Double, lon: Double): PlacesResult {
        val updated = result.places.map {
            it.copy(
                distanceMeters = Geo.distanceMeters(lat, lon, it.latitude, it.longitude),
                bearing = Geo.bearingDegrees(lat, lon, it.latitude, it.longitude),
            )
        }.sortedBy { it.distanceMeters }
        return result.copy(originLat = lat, originLon = lon, places = updated)
    }

    private fun buildAddress(tags: kotlinx.serialization.json.JsonObject?): String? {
        tags ?: return null
        fun t(k: String) = tags[k]?.jsonPrimitive?.contentOrNull
        val parts = listOfNotNull(
            listOfNotNull(t("addr:housenumber"), t("addr:street")).joinToString(" ").ifBlank { null },
            t("addr:city"),
        )
        return parts.joinToString(", ").ifBlank { null }
    }

    private companion object {
        /** Shared across every caller, so parallel category scans stay polite. */
        val gate = kotlinx.coroutines.sync.Semaphore(2)

        fun fmt(v: Double, digits: Int = 2): String =
            String.format(java.util.Locale.US, "%.${digits}f", v)
    }

}
