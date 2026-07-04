package dev.mascwa.pulse.data.ar

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.BuildingFootprint
import dev.mascwa.pulse.core.telemetry.BuildingFootprints
import dev.mascwa.pulse.core.telemetry.GeoPoint
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Fetches the **real OSM building footprints** near a location for the AR wasteland, via the keyless Overpass
 * API (same host the rest of the app uses). Each `way["building"]` is returned with its full node geometry
 * (`out geom`), so the renderer can draw the exact outline of the exact buildings around you — not a block.
 *
 * On-device only: it hands back plain [BuildingFootprint]s (WGS84 rings + an estimated height); nothing about
 * the query is stored or transmitted beyond the standard Overpass request. Fully defensive — any network/parse
 * failure yields an empty list, and the renderer keeps its procedural skyline as the fallback.
 */
class BuildingRepository(
    private val http: HttpClient,
) {
    /** Real building footprints within [radiusMeters] of ([lat], [lon]); empty on any failure. */
    suspend fun near(lat: Double, lon: Double, radiusMeters: Int = DEFAULT_RADIUS_M): List<BuildingFootprint> {
        val ql = """
            [out:json][timeout:25];
            (way["building"](around:$radiusMeters,$lat,$lon););
            out geom $MAX_ELEMENTS;
        """.trimIndent()
        val url = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(ql, "UTF-8")
        return runCatching {
            val text = http.getString(url)
            val elements = http.json.parseToJsonElement(text).jsonObject["elements"]?.jsonArray
                ?: return emptyList()
            elements.mapNotNull { el ->
                val o = el.jsonObject
                val geom = o["geometry"]?.jsonArray ?: return@mapNotNull null
                val ring = geom.mapNotNull { g ->
                    val go = g.jsonObject
                    val la = go["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val lo = go["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    GeoPoint(la, lo)
                }
                if (ring.size < 3) return@mapNotNull null // need a real outline, not a point/line
                val tags = o["tags"]?.jsonObject
                val height = BuildingFootprints.estimateHeight(
                    tags?.get("height")?.jsonPrimitive?.contentOrNull,
                    tags?.get("building:levels")?.jsonPrimitive?.contentOrNull,
                )
                BuildingFootprint(ring, height)
            }.take(MAX_BUILDINGS)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val DEFAULT_RADIUS_M = 300   // a visible city block or two around the player
        const val MAX_ELEMENTS = 250       // Overpass element cap
        const val MAX_BUILDINGS = 150      // renderer also caps by vertex budget
    }
}
