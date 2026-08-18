package dev.mascwa.pulse.data.radio

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.StationRanking
import dev.mascwa.pulse.core.util.Geo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Local / regional radio via the **Radio Browser** community API (free, keyless, no registration —
 * a worldwide database of internet-radio streams). Local lookup is genuinely **geo-sourced**: it asks
 * for stations physically near the device coordinates (Radio Browser `geo_lat`/`geo_long`/`geo_distance`)
 * and orders them through [StationRanking], falling back to country/state popularity only when no
 * geo-tagged stations are nearby. `all.api.radio-browser.info` is a DNS round-robin across the
 * project's mirrors, so a single node being down still resolves.
 *
 * ⚠️ **`hidebroken=true` is not the liveness guarantee it reads as.** It filters on the database's
 * own last check, and that check has largely stalled: measured live, only 14 of 62,497 stations are
 * flagged broken (0.02%), and the median station's last check is **214 days old**, with 174 of a
 * 181-station sample over thirty days. So a station in these lists is one that worked at some point,
 * not one known to work now. The app does not paper over that — a stream that fails is released and
 * reported by `RadioController.failPermanently`, which is the real liveness signal.
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
        // How many people have actually tuned this station, and how they have voted on it. Present on
        // every row of every response measured; the app declared neither, so the "near you" list had
        // nothing to order by except a distance that does not discriminate. See [StationRanking].
        @SerialName("clickcount") val clickCount: Int = 0,
        val votes: Int = 0,
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

    /**
     * Stations physically within [localRadiusMeters] of the coordinates, ordered by
     * [StationRanking] — near band first, then by how much they are actually listened to.
     *
     * ⚠️ It used to sort by raw distance alone, which sounds right and measures wrong: in a dense
     * city every result is the same handful of kilometres away, so the sort key carried no
     * information and the list filled with never-played streams while the station people listen to
     * was cut by the top-30. The reasoning is in [StationRanking]'s own documentation.
     */
    private suspend fun geoStations(lat: Double, lon: Double, limit: Int): List<RadioStation> {
        val url = "https://all.api.radio-browser.info/json/stations/search" +
            "?geo_lat=$lat&geo_long=$lon&geo_distance=$localRadiusMeters" +
            "&has_geo_info=true&hidebroken=true&limit=300"
        val raw = http.getJson(url, ListSerializer(ApiStation.serializer()))
        val seen = HashSet<String>()
        val byStream = LinkedHashMap<String, RadioStation>()
        val candidates = ArrayList<StationRanking.Candidate>()
        raw.forEach { st ->
            val glat = st.geoLat ?: return@forEach
            val glon = st.geoLong ?: return@forEach
            val mapped = st.toStation() ?: return@forEach
            if (!seen.add(mapped.streamUrl)) return@forEach
            byStream[mapped.streamUrl] = mapped.station
            candidates += StationRanking.Candidate(
                id = mapped.streamUrl,
                distanceMeters = Geo.distanceMeters(lat, lon, glat, glon),
                clicks = st.clickCount,
                votes = st.votes,
            )
        }
        // The stream URL is the key because it is already what de-dups this list, so the ranking and
        // the de-dup cannot disagree about what counts as one station.
        return StationRanking.order(candidates, limit).mapNotNull { byStream[it.id] }
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

    /**
     * Top stations for an ISO-2 [countryCode], most-clicked first — powers the WORLD browse.
     *
     * ⚠️ Throws on failure, deliberately. It used to swallow, and the caller swallowed a second
     * time, so a country with no stations and a country the app could not reach produced the same
     * empty list — and the screen said "No stations found there." with no retry offered. The caller
     * needs the difference; a blank country code is still an ordinary empty answer, because that is
     * a question with no answer rather than a question that failed.
     */
    suspend fun stationsByCountry(countryCode: String, limit: Int = 30): List<RadioStation> {
        val cc = countryCode.trim().uppercase()
        if (cc.isBlank()) return emptyList()
        return countryStations(cc, null, limit)
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
        return Mapped(
            // codec and bitrate were parsed here and dropped on the floor. They are the two things
            // that say whether a stream is worth the data and whether it will sound like anything.
            RadioStation(name = title, band = band, streamUrl = stream, codec = codec, kbps = bitrate),
            stateKey = state.trim().lowercase(),
        )
    }

    /** A short "band" tag: top genre tags, else the state/country. */
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
