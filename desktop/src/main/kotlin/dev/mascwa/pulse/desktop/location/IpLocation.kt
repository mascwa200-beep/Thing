package dev.mascwa.pulse.desktop.location

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Roughly where this machine is, worked out from its internet connection.
 *
 * ⚠️ **This locates the CONNECTION, not the person, and the difference is not academic.** Probed live
 * from one machine, three of these services answered Council Bluffs, San Francisco and Brooklyn — all
 * three correct about some hop, none about where the machine physically was, because the traffic left
 * through a proxy. A VPN produces exactly the same effect, and so does a corporate network, and so
 * does a mobile carrier that backhauls to another city.
 *
 * So the result is a STARTING POINT, never an assertion. Every surface that uses it says where it
 * came from, and the setting it fills in is editable — which is the owner's own decision on this, and
 * the right one. A desktop has no GPS to fall back to; a text field does not need one.
 */
data class IpFix(
    val latitude: Double,
    val longitude: Double,
    /** "San Francisco, United States" — for the field, so it reads as a place rather than a number pair. */
    val label: String,
    /** Which service said so, so a wrong answer is traceable rather than mysterious. */
    val source: String,
)

/**
 * Ask the internet where it thinks we are.
 *
 * Two services, tried in order, both keyless and free. ⚠️ They are NOT interchangeable and the order
 * is deliberate: `ipwho.is` is HTTPS, so it goes first; `ip-api.com` is HTTP-only, which is acceptable
 * on a desktop (nothing secret crosses, and the answer is a starting point that gets corrected by
 * hand) but is the fallback rather than the default for the obvious reason.
 *
 * Every failure is silent and returns null. Not knowing where you are is the ordinary state of a
 * machine with no location hardware, and it is not an error to report.
 */
class IpLocationService(private val http: HttpClient) {

    suspend fun locate(): IpFix? = fromIpWhoIs() ?: fromIpApi()

    private suspend fun fromIpWhoIs(): IpFix? = runCatching {
        val r = http.getJson(IPWHOIS_URL, IpWhoIs.serializer())
        // ⚠️ `success` is checked rather than trusted: this service answers 200 with `success:false`
        // for a private or unroutable address, and the coordinates in that body are zero. A machine
        // at 0°N 0°E is in the Gulf of Guinea, which is a much worse answer than none.
        if (r.success != true) return@runCatching null
        val lat = r.latitude ?: return@runCatching null
        val lon = r.longitude ?: return@runCatching null
        if (lat == 0.0 && lon == 0.0) return@runCatching null
        IpFix(lat, lon, place(r.city, r.country), "ipwho.is")
    }.getOrNull()

    private suspend fun fromIpApi(): IpFix? = runCatching {
        val r = http.getJson(IPAPI_URL, IpApi.serializer())
        if (r.status != "success") return@runCatching null
        val lat = r.lat ?: return@runCatching null
        val lon = r.lon ?: return@runCatching null
        if (lat == 0.0 && lon == 0.0) return@runCatching null
        IpFix(lat, lon, place(r.city, r.country), "ip-api.com")
    }.getOrNull()

    private fun place(city: String?, country: String?): String =
        listOfNotNull(city?.takeIf { it.isNotBlank() }, country?.takeIf { it.isNotBlank() })
            .joinToString(", ")
            .ifBlank { "Unknown place" }

    @Serializable
    private data class IpWhoIs(
        val success: Boolean? = null,
        val city: String? = null,
        val country: String? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    @Serializable
    private data class IpApi(
        val status: String? = null,
        val city: String? = null,
        val country: String? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        @SerialName("regionName") val regionName: String? = null,
    )

    private companion object {
        const val IPWHOIS_URL = "https://ipwho.is/"
        const val IPAPI_URL = "http://ip-api.com/json/"
    }
}
