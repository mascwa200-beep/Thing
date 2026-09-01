package dev.mascwa.pulse.data.water

import android.content.Context
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.WaterStations
import dev.mascwa.pulse.core.util.Fetched
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The water where you are, from NOAA CO-OPS — keyless, and whichever product your nearest gauge
 * actually publishes.
 *
 * ## Why this is not simply "tides"
 *
 * ⚠️ **There are no tide-prediction stations in Michigan**, because the Great Lakes are not tidal —
 * the nearest to the owner is 879 km away in Washington DC. A tides-only block would have sat
 * permanently empty where they live. But NOAA publishes water LEVELS for the lakes, and there is a
 * gauge 8.6 km from them. So the bundled list carries which product each station supports and the
 * reading follows the coast or the lake, rather than assuming one of them.
 *
 * NOAA states this limitation itself rather than leaving it to be guessed: asking a Great Lakes
 * station for predictions answers **HTTP 400** with
 * `{"error":{"message":" Great Lakes stations don't have Predictions data."}}`. ⚠️ Worth recording
 * because it is the OPPOSITE of what this app usually finds — a real failing status rather than a
 * 200 carrying an error object, so [HttpClient.getString] throws and the `runCatching` below sees
 * it. Nothing here has to sniff a body for the word "error".
 *
 * ## The two paths
 *
 * [fetch] goes to the network and is for the background worker. [cached] never does and is for the
 * widget, whose whole budget is four seconds a source — the same split [dev.mascwa.pulse.data.sky]
 * uses for orbital elements, and for the same reason: a widget must not start a fetch on a
 * half-hourly schedule, and no cache is genuinely nothing to report.
 */
class WaterRepository(
    private val context: Context,
    private val http: HttpClient,
    private val cache: DiskCache,
) {

    /**
     * One reading, as the caller will draw it.
     *
     * The [line] is composed by the pure core, so a widget row, a screen and a future assistant tool
     * all say the same thing rather than each phrasing it their own way.
     */
    @Serializable
    data class Reading(
        val line: String,
        val stationId: String,
        val stationName: String,
        val distanceKm: Double,
        val lake: Boolean,
    )

    private val stationLock = Mutex()
    private var stationList: List<WaterStations.Station>? = null

    /**
     * The bundled station list, parsed once.
     *
     * ⚠️ Memoised behind a mutex rather than `by lazy`: this is read from the widget's provider, the
     * background worker and possibly a screen at the same time, and a `lazy` initialiser doing 3,551
     * string splits under contention is exactly the kind of thing that turns a four-second budget
     * into a missed row. The list is immutable once built, so every later reader is free.
     */
    suspend fun stations(): List<WaterStations.Station> {
        stationList?.let { return it }
        return stationLock.withLock {
            stationList ?: withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open(ASSET).bufferedReader().useLines { lines ->
                        lines.mapNotNull(WaterStations::parse).toList()
                    }
                }.getOrDefault(emptyList()).also { stationList = it }
            }
        }
    }

    /** The gauge this coordinate should read, or null when nothing is near enough to mean anything. */
    suspend fun nearest(lat: Double, lon: Double): WaterStations.Near? =
        WaterStations.nearest(stations(), lat, lon)

    /**
     * What the cache already holds for this coordinate. Never touches the network.
     *
     * ⚠️ Keyed on the STATION, not the coordinate: the reading is a property of the gauge, so
     * walking half a mile must not orphan an entry that is still describing your water.
     */
    suspend fun cached(lat: Double, lon: Double): Reading? {
        val near = nearest(lat, lon) ?: return null
        return runCatching {
            cache.readAny(key(near.station), Reading.serializer())?.value
        }.getOrNull()
    }

    /**
     * Read the water, going to NOAA when what is held has aged out.
     *
     * `null` data is a real answer and means there is no gauge in range — the caller draws nothing.
     * ⚠️ A block that is absent is honest; one reporting the tide 900 km away is not.
     */
    suspend fun fetch(lat: Double, lon: Double, force: Boolean): Fetched<Reading?> {
        val near = nearest(lat, lon) ?: return Fetched(null, fromCache = true)
        val station = near.station
        val ttl = if (station.kind == WaterStations.Kind.LEVEL) LEVEL_TTL_MS else TIDE_TTL_MS
        val k = key(station)

        if (!force) {
            val hit = runCatching { cache.read(k, ttl, Reading.serializer()) }.getOrNull()
            if (hit != null) return Fetched(hit.value, fromCache = true, timestampEpochMs = hit.savedAtMs)
        }

        val fresh = runCatching {
            when (station.kind) {
                WaterStations.Kind.TIDE -> readTide(station, near.km)
                WaterStations.Kind.LEVEL -> readLevel(station, near.km)
            }
        }.getOrNull()

        if (fresh != null) {
            runCatching { cache.write(k, fresh, Reading.serializer()) }
            return Fetched(fresh, fromCache = false)
        }

        // The network was tried and did not answer. Serve whatever is held, at any age, and say so
        // — "this is what we had" and "there is nothing here" must not render the same way.
        val stale = runCatching { cache.readAny(k, Reading.serializer())?.value }.getOrNull()
        return Fetched(stale, fromCache = true, refreshFailed = true)
    }

    // ---- the two products -------------------------------------------------------------------

    private suspend fun readTide(station: WaterStations.Station, km: Double): Reading? {
        // ⚠️ 72 hours from local midnight, not 24. `interval=hilo` returns four turns a day, so a
        // day's window leaves nothing ahead by the evening — which is exactly when somebody looks.
        val url = BASE +
            "?product=predictions&application=$APP&format=json&units=english" +
            "&time_zone=lst_ldt&datum=MLLW&interval=hilo" +
            "&begin_date=${LocalDate.now().format(REQUEST_DATE)}&range=72" +
            "&station=${station.id}"
        val body = http.getString(url)
        val turns = json(body)["predictions"]?.jsonArray.orEmpty().mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val at = o["t"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val v = o["v"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
            val type = o["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            WaterStations.Turn(at, high = type == "H", feet = v)
        }
        val line = WaterStations.describeTides(turns, nowLocal()) ?: return null
        return Reading(line, station.id, station.name, km, lake = false)
    }

    private suspend fun readLevel(station: WaterStations.Station, km: Double): Reading? {
        // ⚠️ IGLD, and the bundled list is trimmed so that is always the right datum. NOAA also
        // publishes levels for Mississippi river stages, the Texas Laguna Madre and six stations in
        // Puerto Rico, none of which have an IGLD elevation — they are deliberately not bundled,
        // because every one of them has a tide station within 37 km that answers the question
        // better. `WaterStationsAssetTest` holds that.
        val url = BASE +
            "?product=water_level&application=$APP&format=json&units=english" +
            "&time_zone=lst_ldt&datum=IGLD&date=latest&station=${station.id}"
        val body = http.getString(url)
        val root = json(body)
        val row = root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val feet = row["v"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
        // The feed names the station too, and its name is the tidier of the two ("Holland" against
        // the list's "Holland, MI"). Prefer it, fall back to ours.
        val name = root["metadata"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: station.name
        val line = WaterStations.describeLevel(name, feet, "IGLD") ?: return null
        return Reading(line, station.id, station.name, km, lake = true)
    }

    private fun json(body: String): JsonObject = LENIENT.parseToJsonElement(body).jsonObject

    /**
     * Now, in the shape NOAA speaks.
     *
     * ⚠️ `Locale.US` on both formatters, and it is not cosmetic. A pattern built with no locale
     * takes the device's, and in a locale whose `DecimalStyle` uses Arabic-Indic digits — or whose
     * default calendar is not Gregorian — the string this produces would neither be a date NOAA
     * accepts nor compare correctly against the ASCII timestamps it returns. Nothing would throw;
     * the tide line would simply stop appearing on those phones.
     *
     * The device's own clock is right for the station: the gauge is within 90 km by construction,
     * and the request asks for `lst_ldt`, which is the station's local time.
     */
    private fun nowLocal(): String = LocalDateTime.now().format(NOW)

    private fun key(s: WaterStations.Station) = "water_${s.kind.name.lowercase()}_${s.id}"

    private companion object {
        const val ASSET = "water/stations.tsv"
        const val BASE = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter"

        /** NOAA asks callers to identify themselves. */
        const val APP = "lcars-widget"

        /**
         * Predictions are computed months ahead, so what is held stays true all day; a 72-hour
         * window still has two days ahead of it after a full day on disk.
         */
        const val TIDE_TTL_MS = 24 * 60 * 60 * 1000L

        /**
         * A lake level is an observation and it moves — wind setup and seiche shift a basin by a
         * foot over an afternoon — so this is refreshed far more often than a prediction.
         */
        const val LEVEL_TTL_MS = 60 * 60 * 1000L

        val REQUEST_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
        val NOW: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        val LENIENT = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
