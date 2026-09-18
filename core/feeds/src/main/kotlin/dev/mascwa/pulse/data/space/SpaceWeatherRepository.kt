package dev.mascwa.pulse.data.space

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.math.roundToInt

/** NOAA SWPC space-weather data — keyless JSON. */
class SpaceWeatherRepository(
    private val http: HttpClient,
    private val cache: DiskCache,
) {
    private val ttl = 15 * 60 * 1000L

    /**
     * One gate per host, the way `MarketsRepository` gates Yahoo. A refresh now fans out ten
     * products at once and OkHttp would happily put all of them on the wire together; that is the
     * exact burst shape that has previously earned this app a durable rate-limit ban elsewhere.
     * SWPC is a public feed built for polling, so four in flight is plenty polite and still fast.
     */
    private val swpcGate = Semaphore(4)

    private suspend fun fetchText(url: String): String = swpcGate.withPermit { http.getString(url) }

    /**
     * [lat]/[lon] optional — when present, the NOAA OVATION aurora probability for that point is
     * included.
     *
     * [heavy] controls whether the large solar products (X-ray, protons, F10.7, scales, regions)
     * are fetched. The console wants them; the background worker calls this every 15 minutes with
     * `force = true` purely to read `kp`, and those five products are ~546 KB of the ~596 KB
     * payload — roughly 57 MB a day to obtain one number. A light fetch skips them and carries the
     * previously cached values forward rather than overwriting them with blanks.
     *
     * ⚠️ **Defaulting to true means every caller that forgets is a heavy one, and five had.** The
     * board, the Oracle, the lock widget and both desktop standby paths read nothing but `kp` and
     * were asking for the whole payload; `force = false` hid it most of the time, because a warm
     * cache costs nothing — but the moment the TTL had lapsed each of them pulled ~596 KB for one
     * number, on paths that run unattended. All five pass `heavy = false` now. The default stays
     * true so a NEW caller gets everything rather than silently missing a panel; the cost of
     * getting it wrong in that direction is a wasted fetch, and in the other it is a blank screen.
     *
     * ⚠️ [heavy] is deliberately NOT part of the cache key. A light fetch writes a COMPLETE record
     * — the carried-forward values above — so there is one entry to read whichever way it was
     * filled. Keying on it would double the entries and leave the light callers unable to see
     * anything the console had already fetched.
     */
    suspend fun fetch(
        force: Boolean,
        lat: Double? = null,
        lon: Double? = null,
        heavy: Boolean = true,
    ): Fetched<SpaceWeather> {
        // ⚠️ Locale.US, like every other cache key in this package. A bare `"%.0f".format(v)` takes
        // the DEVICE locale, so the same coordinate spells itself differently depending on what
        // language the phone is set to — a comma decimal separator here, Arabic-Indic digits there.
        // Nothing breaks loudly: the key is hashed to a filename, so it stays stable while the
        // locale does. It stops being stable the moment somebody changes the phone's language, and
        // every entry written before then is orphaned rather than superseded.
        val key = if (lat != null && lon != null)
            "space_weather_${String.format(java.util.Locale.US, "%.0f", lat)}" +
                "_${String.format(java.util.Locale.US, "%.0f", lon)}"
        else "space_weather"
        if (!force) {
            cache.read(key, ttl, SpaceWeather.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val data = coroutineScope {
                // Every branch is individually runCatching-wrapped: one dead product must degrade
                // to a missing panel, never take the console down with it.
                val kpD = async { runCatching { loadKp() }.getOrDefault(emptyList()) }
                val windD = async { runCatching { loadWind() }.getOrDefault(emptyList<TimePoint>() to emptyList()) }
                val alertsD = async { runCatching { loadAlerts() }.getOrDefault(emptyList()) }
                val xrayD = async { if (heavy) runCatching { loadXray() }.getOrNull() else null }
                val protonD = async { if (heavy) runCatching { loadProtons() }.getOrDefault(emptyList()) else emptyList() }
                val f107D = async { if (heavy) runCatching { loadF107() }.getOrDefault(null to null) else null to null }
                val scalesD = async { if (heavy) runCatching { loadScales() }.getOrNull() else null }
                val regionsD = async { if (heavy) runCatching { loadRegions() }.getOrDefault(emptyList()) else emptyList() }
                val auroraD = async {
                    if (lat != null && lon != null) auroraFor(lat, lon) else null
                }

                val kpPoints = kpD.await()
                val kpSeries = kpPoints.map { it.v }
                val kp = kpSeries.lastOrNull()
                val (windPoints, bzPoints) = windD.await()
                val xray = xrayD.await()
                val protonPoints = protonD.await()
                val f107 = f107D.await()
                val scales = scalesD.await()
                val fresh = SpaceWeather(
                    kp = kp,
                    kpSeries = kpSeries.takeLast(28),
                    kpPoints = kpPoints.takeLast(28),
                    solarWindSpeed = windPoints.lastOrNull()?.v,
                    windPoints = windPoints.takeLast(SERIES_CAP),
                    bz = bzPoints.lastOrNull()?.v,
                    bzPoints = bzPoints.takeLast(SERIES_CAP),
                    stormLevel = SpaceWeather.stormLevelForKp(kp),
                    auroraChance = SpaceWeather.auroraForKp(kp),
                    auroraProbabilityPct = auroraD.await(),
                    alerts = alertsD.await(),
                    xrayFlux = xray?.longChannel?.lastOrNull()?.v,
                    xrayShortFlux = xray?.shortChannel?.lastOrNull()?.v,
                    xrayPoints = xray?.longChannel.orEmpty().takeLast(SERIES_CAP),
                    protonFlux = protonPoints.lastOrNull()?.v,
                    protonPoints = protonPoints.takeLast(SERIES_CAP),
                    f107 = f107.first,
                    f107Mean = f107.second,
                    scalesNow = scales?.now,
                    scales24h = scales?.past24h,
                    scaleForecast = scales?.forecast.orEmpty(),
                    regions = regionsD.await(),
                )
                // A light fetch skipped the heavy products; carry the last known values forward so
                // the background worker cannot blank out what the console already had.
                val prior = if (heavy) null else cache.readAny(key, SpaceWeather.serializer())?.value
                if (prior == null) fresh else fresh.copy(
                    xrayFlux = prior.xrayFlux,
                    xrayShortFlux = prior.xrayShortFlux,
                    xrayPoints = prior.xrayPoints,
                    protonFlux = prior.protonFlux,
                    protonPoints = prior.protonPoints,
                    f107 = prior.f107,
                    f107Mean = prior.f107Mean,
                    scalesNow = prior.scalesNow,
                    scales24h = prior.scales24h,
                    scaleForecast = prior.scaleForecast,
                    regions = prior.regions,
                )
            }
            cache.write(key, data, SpaceWeather.serializer())
            Fetched(data, false)
        } catch (e: Exception) {
            cache.readAny(key, SpaceWeather.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    /** NOAA OVATION aurora probability (%) at the grid point nearest to [lat]/[lon].
     *  The feed is a full integer-degree grid of (lon 0..359, lat -90..90, prob) triples. */
    private suspend fun loadAurora(lat: Double, lon: Double): Int? {
        // Deliberately NOT through the gate. This is a single request, not part of the fan-out,
        // and its caller boxes it at 4 s — a budget that would otherwise be spent queueing behind
        // nine other products before a ~900 KB grid even starts downloading.
        val text = http.getString("https://services.swpc.noaa.gov/json/ovation_aurora_latest.json")
        val coords = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject["coordinates"]?.jsonArray ?: return null
        val lonR = (((lon % 360) + 360) % 360).roundToInt() % 360
        val latR = lat.roundToInt().coerceIn(-90, 90)
        // Fast path: lon-major ordering, lat ascending from -90.
        coords.getOrNull(lonR * 181 + (latR + 90))?.jsonArray?.let { row ->
            if (row.getOrNull(0)?.jsonPrimitive?.intOrNull == lonR &&
                row.getOrNull(1)?.jsonPrimitive?.intOrNull == latR
            ) {
                return row.getOrNull(2)?.jsonPrimitive?.intOrNull
            }
        }
        // Fallback: nearest-grid scan.
        var best: Int? = null
        var bestDist = Int.MAX_VALUE
        coords.forEach { el ->
            val a = el.jsonArray
            val clon = a.getOrNull(0)?.jsonPrimitive?.intOrNull ?: return@forEach
            val clat = a.getOrNull(1)?.jsonPrimitive?.intOrNull ?: return@forEach
            val dLon = minOf((clon - lonR + 360) % 360, (lonR - clon + 360) % 360)
            val d = dLon * dLon + (clat - latR) * (clat - latR)
            if (d < bestDist) { bestDist = d; best = a.getOrNull(2)?.jsonPrimitive?.intOrNull }
        }
        return best
    }

    /** Public, time-boxed aurora probability for the HUD / on-demand callers. */
    suspend fun auroraFor(lat: Double, lon: Double): Int? =
        runCatching { withTimeoutOrNull(4_000) { loadAurora(lat, lon) } }.getOrNull()

    /** Planetary K-index, timestamped. NOAA serves this either as array-of-arrays (with a
     *  header row) or array-of-objects — handle both. */
    private suspend fun loadKp(): List<TimePoint> {
        val text = fetchText("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
        return arr.mapIndexedNotNull { i, row ->
            when (row) {
                is JsonArray -> {
                    if (i == 0) return@mapIndexedNotNull null // header
                    val v = row.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    val t = parseTime(row.getOrNull(0)?.jsonPrimitive?.contentOrNull)
                    if (v == null || !v.isFinite()) null else TimePoint(t, v)
                }
                is JsonObject -> {
                    val v = (row["kp_index"] ?: row["Kp"] ?: row["kp"])
                        ?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    val t = parseTime(
                        (row["time_tag"] ?: row["time"])?.jsonPrimitive?.contentOrNull,
                    )
                    if (v == null || !v.isFinite()) null else TimePoint(t, v)
                }
                else -> null
            }
        }
    }

    /**
     * Solar wind speed (km/s) and IMF Bz (nT) — both from one fetch.
     *
     * The old `products/solar-wind/plasma-5-minute.json` and `mag-5-minute.json` are **gone**:
     * SWPC removed the whole `solar-wind/` directory, both URLs now 404, and the two readings have
     * silently been blank in the app ever since. The propagated geospace product carries speed and
     * every magnetic-field component in a single array-of-arrays with a header row, which is the
     * shape this parser already wanted — so it is one 7 KB request instead of two dead ones.
     */
    private suspend fun loadWind(): Pair<List<TimePoint>, List<TimePoint>> {
        val text = fetchText(SOLAR_WIND)
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
        val speed = mutableListOf<TimePoint>()
        val bz = mutableListOf<TimePoint>()
        arr.forEachIndexed { i, el ->
            if (i == 0) return@forEachIndexed // header row
            val row = el as? JsonArray ?: return@forEachIndexed
            val t = parseTime(row.getOrNull(0)?.jsonPrimitive?.contentOrNull)
            fun col(index: Int): Double? = row.getOrNull(index)
                ?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.takeIf { it.isFinite() }
            // A gap in the feed is a gap, not a reading of zero — skip rather than substitute.
            col(WIND_SPEED_COL)?.let { speed += TimePoint(t, it) }
            col(WIND_BZ_COL)?.let { bz += TimePoint(t, it) }
        }
        return speed to bz
    }

    /** Both GOES X-ray channels. The 0.1-0.8 nm long channel is the one flare class is defined on. */
    private data class Xray(val longChannel: List<TimePoint>, val shortChannel: List<TimePoint>)

    private suspend fun loadXray(): Xray {
        val text = fetchText(XRAY_6H)
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
        val long = mutableListOf<TimePoint>()
        val short = mutableListOf<TimePoint>()
        arr.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val flux = obj["flux"]?.jsonPrimitive?.doubleOrNull ?: return@forEach
            if (!flux.isFinite()) return@forEach
            val t = parseTime(obj["time_tag"]?.jsonPrimitive?.contentOrNull)
            when (obj["energy"]?.jsonPrimitive?.contentOrNull) {
                LONG_CHANNEL -> long += TimePoint(t, flux)
                SHORT_CHANNEL -> short += TimePoint(t, flux)
            }
        }
        return Xray(long.sortedBy { it.t }, short.sortedBy { it.t })
    }

    /** Integral proton flux at >=10 MeV — the channel the NOAA S scale is defined on. */
    private suspend fun loadProtons(): List<TimePoint> {
        val text = fetchText(PROTONS_1D)
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            if (obj["energy"]?.jsonPrimitive?.contentOrNull != PROTON_CHANNEL) return@mapNotNull null
            val flux = obj["flux"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            if (!flux.isFinite()) return@mapNotNull null
            TimePoint(parseTime(obj["time_tag"]?.jsonPrimitive?.contentOrNull), flux)
        }.sortedBy { it.t }
    }

    /**
     * F10.7 cm radio flux and NOAA's 90-day mean. The feed is newest-first, but only some rows
     * carry `ninety_day_mean` — the newest row usually has it as null — so the two are searched
     * independently rather than taking both from the same row and losing the mean.
     */
    private suspend fun loadF107(): Pair<Double?, Double?> {
        val text = fetchText(F107)
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
        fun field(name: String): Double? = arr.firstNotNullOfOrNull { el ->
            (el as? JsonObject)?.get(name)?.jsonPrimitive?.doubleOrNull
                ?.takeIf { it.isFinite() && it > 0 }
        }
        return field("flux") to field("ninety_day_mean")
    }

    /** The current + past-24h + three-day NOAA R/S/G scales. */
    private data class Scales(
        val now: ScaleForecast?,
        val past24h: ScaleForecast?,
        val forecast: List<ScaleForecast>,
    )

    private suspend fun loadScales(): Scales {
        val text = fetchText(NOAA_SCALES)
        val obj = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonObject
        // Keys are "-1" (worst of the past 24 h), "0" (now) and "1".."3" (forecast days).
        fun at(key: String, label: String): ScaleForecast? {
            val day = obj[key]?.jsonObject ?: return null
            fun scale(k: String) = day[k]?.jsonObject?.get("Scale")
                ?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            fun prob(k: String, field: String) = day[k]?.jsonObject?.get(field)
                ?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            return ScaleForecast(
                label = label,
                r = scale("R"), s = scale("S"), g = scale("G"),
                rMinorProbPct = prob("R", "MinorProb"),
                rMajorProbPct = prob("R", "MajorProb"),
                sProbPct = prob("S", "Prob"),
            )
        }
        return Scales(
            now = at("0", "Now"),
            past24h = at("-1", "Past 24h"),
            forecast = listOfNotNull(at("1", "Day 1"), at("2", "Day 2"), at("3", "Day 3")),
        )
    }

    /**
     * Sunspot groups. This feed lags — it can be weeks behind — so every region carries the date
     * NOAA actually observed it and the UI must show that rather than implying "now".
     */
    private suspend fun loadRegions(): List<SolarRegion> {
        val text = fetchText(SOLAR_REGIONS)
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
        val newestDate = arr.mapNotNull {
            (it as? JsonObject)?.get("observed_date")?.jsonPrimitive?.contentOrNull
        }.maxOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val observed = obj["observed_date"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            // Only the most recent observing day — older rows are the same regions, days earlier.
            if (observed != newestDate) return@mapNotNull null
            val number = obj["region"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            fun int(k: String) = obj[k]?.jsonPrimitive?.intOrNull ?: 0
            fun str(k: String) = obj[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            SolarRegion(
                number = number,
                location = str("location"),
                spotClass = str("spot_class"),
                magClass = str("mag_class"),
                area = int("area"),
                spotCount = int("number_spots"),
                cFlareProbPct = int("c_flare_probability"),
                mFlareProbPct = int("m_flare_probability"),
                xFlareProbPct = int("x_flare_probability"),
                observedDate = observed,
            )
        }.sortedByDescending { it.area }.take(12)
    }

    /**
     * NOAA time tags are ISO-8601 UTC, sometimes with a trailing `Z`, sometimes space-separated,
     * sometimes with fractional seconds. 0 when unparseable — a bad stamp must not sink the row.
     *
     * `Instant` rather than `SimpleDateFormat` deliberately: these loaders run concurrently and
     * walk thousands of rows each, and SimpleDateFormat is both allocation-heavy per row and not
     * thread-safe, so a shared one would corrupt across the parallel fetches.
     */
    private fun parseTime(raw: String?): Long {
        val s = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return 0L
        val iso = s.replace(' ', 'T').substringBefore('.').let {
            if (it.endsWith("Z")) it else "${it}Z"
        }
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)
    }

    private suspend fun loadAlerts(): List<SpaceAlert> {
        val text = fetchText("https://services.swpc.noaa.gov/products/alerts.json")
        val arr = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }.jsonArray
        return arr.take(12).mapNotNull { el ->
            val obj = el.jsonObject
            val message = obj["message"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val issued = obj["issue_datetime"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val title = message.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("WARNING") || it.startsWith("ALERT") || it.startsWith("WATCH") || it.startsWith("SUMMARY") }
                ?: message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?: "Space weather notice"
            SpaceAlert(title = title.take(120), issued = issued, message = message.trim().take(600))
        }
    }

    companion object {
        /** Speed and every field component in one product — see [loadWind] for why the old
         *  `solar-wind/` pair is gone. Column 0 is the time tag. */
        private const val SOLAR_WIND = "https://services.swpc.noaa.gov/products/geospace/propagated-solar-wind-1-hour.json"
        private const val WIND_SPEED_COL = 1
        private const val WIND_BZ_COL = 6
        private const val XRAY_6H = "https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json"
        private const val PROTONS_1D = "https://services.swpc.noaa.gov/json/goes/primary/integral-protons-1-day.json"
        private const val F107 = "https://services.swpc.noaa.gov/json/f107_cm_flux.json"
        private const val NOAA_SCALES = "https://services.swpc.noaa.gov/products/noaa-scales.json"
        private const val SOLAR_REGIONS = "https://services.swpc.noaa.gov/json/solar_regions.json"

        /** GOES energy-band labels, exactly as the feed spells them. */
        private const val LONG_CHANNEL = "0.1-0.8nm"
        private const val SHORT_CHANNEL = "0.05-0.4nm"
        /** The proton channel the NOAA S scale is defined on. */
        private const val PROTON_CHANNEL = ">=10 MeV"

        /** Keep series bounded — these feeds run to thousands of rows and land in the disk cache. */
        private const val SERIES_CAP = 180
    }
}
