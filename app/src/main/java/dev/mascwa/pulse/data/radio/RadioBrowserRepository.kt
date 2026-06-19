package dev.mascwa.pulse.data.radio

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Geo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Local / regional radio via the **Radio Browser** community API (free, keyless, no registration —
 * a worldwide database of internet-radio streams). Local lookup is genuinely **geo-sourced**: it asks
 * for stations physically near the device coordinates (Radio Browser `geo_lat`/`geo_long`/`geo_distance`)
 * and sorts by true distance, falling back to country/state popularity only when no geo-tagged stations
 * are nearby. `all.api.radio-browser.info` is a DNS round-robin across the project's mirrors, so a single
 * node being down still resolves.
 *
 * Defensive: any failure yields an empty list (the curated SomaFM streams always remain available).
 */
class RadioBrowserRepository(private val http: HttpClient) {

    @Serializable
    private data class ApiStation(
        val name: String = "",
        val url: String = "",
        @SerialName("url_resolved") val urlResolved: String = "",
        val tags: String = "",
        val state: String = "",
        val country: String = "",
        val codec: String = "",
        val bitrate: Int = 0,
        @SerialName("geo_lat") val geoLat: Double? = null,
        @SerialName("geo_long") val geoLong: Double? = null,
    )

    /** Search radius for "local" geo-sourced stations (200 km ≈ a broadcast region). */
    private val localRadiusMeters = 200_000

    /**
     * Up to [limit] stations near [lat],[lon] — genuinely location-sourced (Radio Browser geo search,
     * sorted by true distance). Falls back to [countryCode]/[state] popularity when nothing geo-tagged
     * is nearby (many stations omit coordinates).
     */
    suspend fun localStations(
        lat: Double,
        lon: Double,
        countryCode: String?,
        state: String?,
        limit: Int = 30,
    ): List<RadioStation> {
        val geo = runCatching { geoStations(lat, lon, limit) }.getOrDefault(emptyList())
        if (geo.isNotEmpty()) return geo
        val cc = countryCode?.trim()?.uppercase().orEmpty()
        if (cc.isBlank()) return emptyList()
        return countryStations(cc, state, limit)
    }

    /** Stations physically within [localRadiusMeters] of the coordinates, nearest first. */
    private suspend fun geoStations(lat: Double, lon: Double, limit: Int): List<RadioStation> {
        val url = "https://all.api.radio-browser.info/json/stations/search" +
            "?geo_lat=$lat&geo_long=$lon&geo_distance=$localRadiusMeters" +
            "&has_geo_info=true&hidebroken=true&limit=300"
        val raw = http.getJson(url, ListSerializer(ApiStation.serializer()))
        val seen = HashSet<String>()
        return raw.asSequence()
            .mapNotNull { st ->
                val glat = st.geoLat ?: return@mapNotNull null
                val glon = st.geoLong ?: return@mapNotNull null
                val mapped = st.toStation() ?: return@mapNotNull null
                if (!seen.add(mapped.streamUrl)) return@mapNotNull null
                mapped.station to Geo.distanceMeters(lat, lon, glat, glon)
            }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** Popularity-ordered stations for a country, with [state] stations floated to the top. */
    private suspend fun countryStations(cc: String, state: String?, limit: Int): List<RadioStation> {
        val url = "https://all.api.radio-browser.info/json/stations/search" +
            "?countrycode=$cc&order=clickcount&reverse=true&hidebroken=true&limit=120"
        val raw = http.getJson(url, ListSerializer(ApiStation.serializer()))

        val seen = HashSet<String>()
        val mapped = raw.asSequence()
            .mapNotNull { it.toStation() }
            .filter { seen.add(it.streamUrl) } // de-dup by stream
            .toList()

        val wanted = state?.trim()?.lowercase().orEmpty()
        val ordered = if (wanted.isBlank()) {
            mapped
        } else {
            val (here, elsewhere) = mapped.partition { it.stateKey == wanted }
            here + elsewhere
        }
        return ordered.take(limit).map { it.station }
    }

    /** Search any station worldwide by name (most-clicked first), de-duped by stream. */
    suspend fun searchStations(query: String, limit: Int = 30): List<RadioStation> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = java.net.URLEncoder.encode(q, "UTF-8")
        val url = "https://all.api.radio-browser.info/json/stations/search" +
            "?name=$encoded&order=clickcount&reverse=true&hidebroken=true&limit=$limit"
        val raw = http.getJson(url, ListSerializer(ApiStation.serializer()))
        val seen = HashSet<String>()
        return raw.asSequence()
            .mapNotNull { it.toStation()?.station }
            .filter { seen.add(it.streamUrl) }
            .toList()
    }

    /** A mapped station plus its lower-cased state key (for the "near me" partition). */
    private data class Mapped(val station: RadioStation, val stateKey: String) {
        val streamUrl get() = station.streamUrl
    }

    private fun ApiStation.toStation(): Mapped? {
        val stream = urlResolved.ifBlank { url }.trim()
        val title = name.trim()
        if (stream.isBlank() || title.isBlank() || !stream.startsWith("http")) return null
        val band = buildBand()
        return Mapped(RadioStation(name = title, band = band, streamUrl = stream), stateKey = state.trim().lowercase())
    }

    /** A short Pip-Boy "band" tag: top genre tags, else the state/country. */
    private fun ApiStation.buildBand(): String {
        val tagPart = tags.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" · ") { it.uppercase() }
        if (tagPart.isNotBlank()) return tagPart
        val place = state.ifBlank { country }.trim()
        return if (place.isNotBlank()) place.uppercase() else "LOCAL"
    }
}
