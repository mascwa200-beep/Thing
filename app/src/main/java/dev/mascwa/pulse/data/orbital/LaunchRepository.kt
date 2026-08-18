package dev.mascwa.pulse.data.orbital

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One scheduled launch. */
@Serializable
data class UpcomingLaunch(
    val name: String,
    val provider: String = "",
    val mission: String = "",
    val pad: String = "",
    val location: String = "",
    val orbit: String = "",
    /** Scheduled instant, or null when the feed gives no parseable date. */
    val netEpochMs: Long? = null,
    /**
     * How firm that instant actually is — "Minute", "Hour", "Day", "Month", "Year".
     *
     * Load-bearing, not decoration. Most launches beyond the next fortnight are known only to the
     * month, and rendering one of those as a second-by-second countdown would be inventing
     * precision the provider never claimed.
     */
    val netPrecision: String = "",
    /**
     * The two ends of the launch window, when the feed publishes them — and it publishes them on
     * every launch measured.
     *
     * ⚠️ **[netPrecision] does not cover this and it is easy to think it does.** Precision says how
     * well the T-0 itself is known; the window says how much room the flight actually has, and the
     * two come apart completely. Measured live: `Starlink Group 17-50` carries a T-0 of
     * `03:45:08` at **Second** precision — so [timeIsFirm] is true and the screen prints it to the
     * second — inside a window running `02:00 → 06:00`. Four hours, entirely invisible. See
     * [dev.mascwa.pulse.core.telemetry.LaunchWindow].
     */
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
    /** "Go", "TBC", "TBD", "Hold", "Success", "Failure". */
    val status: String = "",
    val statusDetail: String = "",
    val imageUrl: String? = null,
) {
    /** True when the schedule is firm enough that a countdown means something. */
    val timeIsFirm: Boolean
        get() = netEpochMs != null && netPrecision.lowercase() in FIRM_PRECISIONS

    private companion object {
        val FIRM_PRECISIONS = setOf("second", "minute", "hour")
    }
}

/**
 * Upcoming orbital launches from The Space Devs' Launch Library — keyless and free.
 *
 * The anonymous tier is rate limited to a handful of requests an hour, so the cache TTL here is
 * deliberately long and is enforced even against a forced refresh. A launch schedule does not move
 * minute to minute, and being a good citizen of a free API is the price of using one.
 */
class LaunchRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {

    suspend fun upcoming(force: Boolean = false): Fetched<List<UpcomingLaunch>> {
        val cached = cache.readAny(KEY, Stored.serializer())
        val ageMs = cached?.let { System.currentTimeMillis() - it.savedAtMs } ?: Long.MAX_VALUE
        val stillFresh = if (force) ageMs < MIN_REFRESH_MS else ageMs < TTL_MS
        if (cached != null && stillFresh) return Fetched(cached.value.launches, true, cached.savedAtMs)

        val parsed = runCatching { gate.withPermit { parse(http.getString(URL)) } }.getOrNull()
        if (!parsed.isNullOrEmpty()) {
            runCatching { cache.write(KEY, Stored(parsed), Stored.serializer()) }
            return Fetched(parsed, false)
        }
        // Rate limited or offline: a stale schedule is still broadly right.
        return cached?.let { Fetched(it.value.launches, true, it.savedAtMs) }
            ?: Fetched(emptyList(), false)
    }

    private fun parse(body: String): List<UpcomingLaunch> = runCatching {
        val root = http.json.parseToJsonElement(body).jsonObject
        root["results"]?.jsonArray.orEmpty().mapNotNull { element ->
            runCatching {
                val o = element.jsonObject
                val name = o.str("name") ?: return@runCatching null
                UpcomingLaunch(
                    name = name,
                    provider = o.str("lsp_name").orEmpty(),
                    mission = o.str("mission").orEmpty(),
                    pad = o.str("pad").orEmpty(),
                    location = o.str("location").orEmpty(),
                    orbit = o.str("orbit").orEmpty(),
                    netEpochMs = o.str("net")?.let(::parseIso),
                    netPrecision = o["net_precision"]?.jsonObject?.str("name").orEmpty(),
                    windowStartMs = o.str("window_start")?.let(::parseIso),
                    windowEndMs = o.str("window_end")?.let(::parseIso),
                    status = o["status"]?.jsonObject?.str("abbrev").orEmpty(),
                    statusDetail = o["status"]?.jsonObject?.str("name").orEmpty(),
                    imageUrl = o.str("image"),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun parseIso(text: String): Long? = runCatching {
        // The feed stamps UTC as a trailing Z, which SimpleDateFormat's X pattern reads.
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(text)?.time
    }.getOrNull()

    @Serializable
    private data class Stored(val launches: List<UpcomingLaunch> = emptyList())

    private companion object {
        const val KEY = "launches_upcoming"
        const val URL = "https://ll.thespacedevs.com/2.2.0/launch/upcoming/?limit=25&mode=list"

        /** Launch schedules shift over days, not minutes. */
        const val TTL_MS = 3 * 60 * 60 * 1000L

        /** The anonymous tier allows only a few calls an hour — honoured even on a forced refresh. */
        const val MIN_REFRESH_MS = 30 * 60 * 1000L

        val gate = Semaphore(1)
    }
}
