package dev.mascwa.pulse.data.radio

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Local / regional radio via the **Radio Browser** community API (free, keyless, no registration —
 * a worldwide database of internet-radio streams). We query by ISO country code (most-clicked first),
 * then float the user's own state to the top so the list reads as "near me". `all.api.radio-browser.info`
 * is a DNS round-robin across the project's mirrors, so a single node being down still resolves.
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
    )

    /**
     * Up to [limit] working stations for [countryCode]; if [state] is given, stations from that state
     * are ordered first. Ordered by popularity (click count) within each group.
     */
    suspend fun localStations(countryCode: String, state: String?, limit: Int = 30): List<RadioStation> {
        val cc = countryCode.trim().uppercase()
        if (cc.isBlank()) return emptyList()
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
