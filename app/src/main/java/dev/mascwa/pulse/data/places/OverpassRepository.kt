package dev.mascwa.pulse.data.places

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.core.util.Geo
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
        val key = "places_${id}_${"%.2f".format(lat)}_${"%.2f".format(lon)}"
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

    private suspend fun load(
        id: String,
        filter: String,
        radiusMeters: Int,
        fallbackName: String,
        lat: Double,
        lon: Double,
    ): PlacesResult {
        val ql = """
            [out:json][timeout:25];
            (
              node$filter(around:$radiusMeters,$lat,$lon);
              way$filter(around:$radiusMeters,$lat,$lon);
            );
            out center 80;
        """.trimIndent()
        val url = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(ql, "UTF-8")
        val text = http.getString(url)
        val elements = http.json.parseToJsonElement(text).jsonObject["elements"]?.jsonArray ?: return PlacesResult(id, lat, lon, emptyList())

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
            )
        }
            .distinctBy { "${it.name}_${"%.4f".format(it.latitude)}" }
            .sortedBy { it.distanceMeters }
            .take(40)

        return PlacesResult(id, lat, lon, places)
    }

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
}
