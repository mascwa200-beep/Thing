package dev.mascwa.pulse.data.maps

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Ground height for a list of coordinates, from **Open-Meteo**'s elevation service — free, keyless,
 * worldwide, and already a host this app talks to for weather.
 *
 * The service answers a whole batch in one request, which is what makes an elevation profile
 * practical at all: sampling a route at sixty points is one round trip, not sixty.
 */
class ElevationRepository(private val http: HttpClient) {

    @Serializable
    private data class Response(val elevation: List<Double> = emptyList())

    /**
     * Heights in metres for [points], in the same order, or null if the service could not answer.
     *
     * Returns null rather than a partial list on failure: a profile drawn from half a reply would
     * be a chart of a route that stops in the middle of nowhere.
     */
    suspend fun elevations(points: List<Pair<Double, Double>>): List<Double>? {
        if (points.isEmpty()) return emptyList()
        val out = ArrayList<Double>(points.size)
        // The service documents a limit of 100 coordinates per request, so a long route is split.
        for (chunk in points.chunked(MAX_PER_REQUEST)) {
            val lats = chunk.joinToString(",") { fmt(it.first) }
            val lons = chunk.joinToString(",") { fmt(it.second) }
            val url = "$BASE?latitude=$lats&longitude=$lons"
            val reply = runCatching { gate.withPermit { http.getJson(url, Response.serializer()) } }
                .getOrNull() ?: return null
            if (reply.elevation.size != chunk.size) return null
            out += reply.elevation
        }
        return out
    }

    /** Locale.US: these are coordinates, and a comma decimal would split one into two. */
    private fun fmt(v: Double): String = String.format(Locale.US, "%.5f", v)

    private companion object {
        const val BASE = "https://api.open-meteo.com/v1/elevation"
        const val MAX_PER_REQUEST = 100
        /** One gate per host, as everywhere else. */
        val gate = Semaphore(2)
    }
}
