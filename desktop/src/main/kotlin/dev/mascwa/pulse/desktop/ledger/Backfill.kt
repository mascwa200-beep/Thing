package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.Novelty
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * A year of the world, poured in on the first pass, so the wall is worth opening on day one.
 *
 * The obvious weakness of a recorder is that it is useless until it has recorded something. A wall
 * saying *"not enough history yet"* for six weeks is a wall nobody opens a seventh time. Two of the
 * providers already in use serve their own history, keylessly, in one request each — so most of the
 * metrics can start scored rather than start blank.
 *
 * ## ⚠️ "History exists" is not "history of the same thing", and this is the whole design
 *
 * A backfilled observation and a self-recorded one are only interchangeable if they measure the same
 * quantity. The weather archive is **ERA5 reanalysis on a ~31 km grid**; the live endpoint is a blended
 * high-resolution forecast. They are different models, and mixing them puts a systematically shifted
 * distribution underneath the readings taken here — which does not look like a bug, it looks like the
 * world having been different last year.
 *
 * Measured over one week in London, the disagreement is nothing like uniform across fields. As a
 * fraction of each field's own spread:
 *
 * ```
 * surface pressure    0.009      wind gusts       0.006      humidity   0.029
 * temperature         0.055      dew point        0.143
 * wind speed          0.493  <-- half a MAD of systematic offset
 * cloud cover         0.220  <-- and up to a hundred points apart on a single hour
 * ```
 *
 * ⚠️ **That list is not hardcoded, and deliberately so.** One week at one flat coastal city is thin
 * evidence for what a 31 km grid cell does in mountains, and the fields that fail here are exactly the
 * ones whose error is terrain-driven. So the agreement is **measured on the machine that will use it**:
 * one extra request for the overlapping week from the live endpoint, [agrees] judged per field against
 * that field's own variability, and only the fields that pass are poured in. The app checks whether its
 * own backfill can be trusted here, rather than trusting a table written somewhere else.
 *
 * ## What is deliberately not backfilled, and why
 *
 * - **Visibility** — the archive does not carry it. Probed, not assumed.
 * - **Markets.** Yahoo serves two years of daily bars from the endpoint already in use, but a daily
 *   bar's close is a different sub-population from the hourly intraday readings the collector takes,
 *   and a bar's whole-day move is systematically larger than a part-way-through-the-day
 *   `changePercent`. Backfilling those would make every recorded move look unusually small. It would
 *   also cost one request per symbol against a provider that has already banned this project's address
 *   once, for a series that [Novelty.score]'s like-for-like rule discards after a single day of real
 *   collection anyway.
 * - **Solar flux.** SWPC's long series is *monthly means* — a different measurement, and the only
 *   daily-resolution feed reaches back thirty days, which barely clears the refusal floor.
 * - **Seismic.** A count reconstructed from an event query is not the count the collector records.
 *
 * Air quality has no such question: the live reading and the history come from **one endpoint and one
 * model**, and checked hour for hour they agree to the last digit on every pollutant and both indices.
 * It is poured in whole.
 */
class Backfill(
    private val ledger: WorldLedger,
    private val http: HttpClient,
) {

    /** What one backfill pass did, per metric, in terms a person can read. */
    data class Report(
        val filled: Map<String, Int>,
        val rejected: Map<String, String>,
        val skipped: List<String>,
    ) {
        fun describe(): String {
            val head = when {
                filled.isEmpty() && rejected.isEmpty() -> "nothing to backfill"
                filled.isEmpty() -> "no history could be trusted here (${rejected.size} judged, none agreed)"
                else -> "${filled.values.sum()} readings across ${filled.size} metrics" +
                    if (rejected.isEmpty()) "" else ", ${rejected.size} rejected as a different measurement"
            }
            // ⚠️ A provider that could not be reached is said out loud. It is the difference between
            // "there is no history for this" and "nobody has asked yet", and only one of them means
            // the next pass will try again.
            return head + if (skipped.isEmpty()) "" else "; could not reach ${skipped.joinToString(", ")}"
        }
    }

    /** How a field's history compared with the live endpoint over the overlapping window. */
    data class Agreement(val overlap: Int, val bias: Double, val spread: Double, val ratio: Double, val agrees: Boolean)

    /**
     * Fill whatever has not been filled yet, once per metric.
     *
     * Never throws: a provider having a bad afternoon leaves those metrics unmarked and they are tried
     * again on the next pass. Returns null when there was nothing to do at all, so the caller can stay
     * quiet rather than reporting a pass that did nothing.
     */
    suspend fun runOnce(lat: Double, lon: Double, placeKey: String?, nowMs: Long = System.currentTimeMillis()): Report? {
        val filled = mutableMapOf<String, Int>()
        val rejected = mutableMapOf<String, String>()
        val skipped = mutableListOf<String>()

        val wanted = (MetricRegistry.WEATHER + MetricRegistry.AIR).filterNot { ledger.isBackfilled(it.key(placeKey)) }
        if (wanted.isEmpty()) return null

        if (wanted.any { it.domain == MetricRegistry.Domain.WEATHER }) {
            runCatching { fillWeather(lat, lon, placeKey, nowMs, filled, rejected, skipped) }
                .onFailure { skipped += "weather (${it.message ?: "unreachable"})" }
        }
        if (wanted.any { it.domain == MetricRegistry.Domain.AIR }) {
            runCatching { fillAir(lat, lon, placeKey, filled, skipped) }
                .onFailure { skipped += "air quality (${it.message ?: "unreachable"})" }
        }

        return Report(filled, rejected, skipped)
    }

    // ---------------------------------------------------------------- weather

    private suspend fun fillWeather(
        lat: Double,
        lon: Double,
        placeKey: String?,
        nowMs: Long,
        filled: MutableMap<String, Int>,
        rejected: MutableMap<String, String>,
        skipped: MutableList<String>,
    ) {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), ZoneOffset.UTC)
        // ⚠️ Ends yesterday. The reanalysis reaches the previous day and asking for today returns a
        // short or empty block, which would look like a provider fault rather than a calendar one.
        val archive = http.getJson(
            "https://archive-api.open-meteo.com/v1/archive" +
                "?latitude=$lat&longitude=$lon" +
                "&start_date=${today.minusDays(ARCHIVE_DAYS)}&end_date=${today.minusDays(1)}" +
                "&hourly=$WEATHER_FIELDS&timezone=GMT",
            OmSeries.serializer(),
        )

        // The reference the archive is judged against: the same fields, the same hours, from the
        // endpoint the live readings actually come from.
        val live = http.getJson(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon&past_days=$OVERLAP_DAYS&forecast_days=1" +
                "&hourly=$WEATHER_FIELDS&timezone=GMT",
            OmSeries.serializer(),
        )

        // Hoisted rather than read through the property: a nullable field of a data class does not
        // smart-cast at a later use, whatever was checked earlier.
        val pastHours = archive.hourly ?: return
        val nowHours = live.hourly ?: return
        val archiveHours = pastHours.time
        val liveIndex = nowHours.time.withIndex().associate { (i, t) -> t to i }

        for ((metricId, field) in WEATHER_MAP) {
            val key = MetricRegistry.BY_ID[metricId]?.key(placeKey) ?: continue
            if (ledger.isBackfilled(key)) continue

            val past = pastHours.column(field)
            val now = nowHours.column(field)
            if (past == null || now == null) {
                skipped += "$metricId (not carried by the archive)"
                continue
            }

            val verdict = agrees(
                archiveHours.mapIndexedNotNull { i, t -> liveIndex[t]?.let { j -> past.getOrNull(i) to now.getOrNull(j) } },
            )
            // Marked either way: a field judged a different measurement here will be judged the same
            // way tomorrow, and re-fetching a year of it every pass to reach the same answer would be
            // a standing cost for nothing.
            ledger.markBackfilled(key)
            if (!verdict.agrees) {
                rejected[metricId] = describeRejection(verdict)
                continue
            }

            val rows = archiveHours.mapIndexedNotNull { i, t ->
                val v = past.getOrNull(i) ?: return@mapIndexedNotNull null
                Novelty.Observation(hourMs(t) ?: return@mapIndexedNotNull null, v, backfilled = true)
            }
            if (rows.isEmpty()) continue
            ledger.appendAll(key, rows)
            filled[metricId] = rows.size
        }
    }

    private fun describeRejection(v: Agreement): String = when {
        v.overlap < MIN_OVERLAP -> "only ${v.overlap} overlapping hours to judge by"
        v.spread <= 0.0 -> "the live reading never moved, so there was nothing to judge against"
        else -> "off by %.2f against a spread of %.2f — a different measurement here".format(v.bias, v.spread)
    }

    // ---------------------------------------------------------------- air quality

    private suspend fun fillAir(
        lat: Double,
        lon: Double,
        placeKey: String?,
        filled: MutableMap<String, Int>,
        skipped: MutableList<String>,
    ) {
        val air = http.getJson(
            "https://air-quality-api.open-meteo.com/v1/air-quality" +
                "?latitude=$lat&longitude=$lon&past_days=$AIR_DAYS&forecast_days=1" +
                "&hourly=$AIR_FIELDS&timezone=GMT",
            OmSeries.serializer(),
        )
        val block = air.hourly ?: return
        val hours = block.time

        for ((metricId, field) in AIR_MAP) {
            val key = MetricRegistry.BY_ID[metricId]?.key(placeKey) ?: continue
            if (ledger.isBackfilled(key)) continue
            val values = block.column(field)
            if (values == null) {
                skipped += "$metricId (not carried)"
                continue
            }
            ledger.markBackfilled(key)
            val rows = hours.mapIndexedNotNull { i, t ->
                val v = values.getOrNull(i) ?: return@mapIndexedNotNull null
                Novelty.Observation(hourMs(t) ?: return@mapIndexedNotNull null, v, backfilled = true)
            }
            if (rows.isEmpty()) continue
            ledger.appendAll(key, rows)
            filled[metricId] = rows.size
        }
    }

    // ---------------------------------------------------------------- the response shape

    /**
     * Every Open-Meteo product answers in this shape, so one declaration serves the archive, the
     * forecast and the air quality endpoints. Fields absent from a given product simply arrive null.
     */
    @Serializable
    data class OmSeries(val hourly: Hourly? = null) {
        @Serializable
        data class Hourly(
            val time: List<String> = emptyList(),
            val temperature_2m: List<Double?>? = null,
            val relative_humidity_2m: List<Double?>? = null,
            val surface_pressure: List<Double?>? = null,
            val dew_point_2m: List<Double?>? = null,
            val wind_speed_10m: List<Double?>? = null,
            val wind_gusts_10m: List<Double?>? = null,
            val cloud_cover: List<Double?>? = null,
            val pm10: List<Double?>? = null,
            val pm2_5: List<Double?>? = null,
            val ozone: List<Double?>? = null,
            val nitrogen_dioxide: List<Double?>? = null,
            val sulphur_dioxide: List<Double?>? = null,
            val carbon_monoxide: List<Double?>? = null,
            val european_aqi: List<Double?>? = null,
            val us_aqi: List<Double?>? = null,
        ) {
            fun column(name: String): List<Double?>? = when (name) {
                "temperature_2m" -> temperature_2m
                "relative_humidity_2m" -> relative_humidity_2m
                "surface_pressure" -> surface_pressure
                "dew_point_2m" -> dew_point_2m
                "wind_speed_10m" -> wind_speed_10m
                "wind_gusts_10m" -> wind_gusts_10m
                "cloud_cover" -> cloud_cover
                "pm10" -> pm10
                "pm2_5" -> pm2_5
                "ozone" -> ozone
                "nitrogen_dioxide" -> nitrogen_dioxide
                "sulphur_dioxide" -> sulphur_dioxide
                "carbon_monoxide" -> carbon_monoxide
                "european_aqi" -> european_aqi
                "us_aqi" -> us_aqi
                else -> null
            }
        }
    }

    companion object {

        /**
         * Whether a field's history is close enough to the live endpoint to be treated as the same
         * measurement, judged over hours the two both cover.
         *
         * ⚠️ The test is the systematic offset **as a fraction of the field's own spread**, not the
         * offset itself. Half a degree means nothing on a temperature that swings ten and everything on
         * a dew point that swings two, and an absolute threshold would have to be written per field and
         * per unit — which is a table, and a table is the thing this exists to avoid.
         *
         * ⚠️ The spread comes from the **live** side. The archive's is a fine estimate too, but the
         * question being asked is whether pouring the archive under the recorded readings would shift
         * the distribution *those* readings will be judged against, so it is their spread that sets
         * what counts as a shift.
         *
         * Mean rather than median for the bias, because a systematic offset is what is being looked
         * for and the mean is what an offset moves. Outliers in the difference are exactly the terrain
         * disagreements that should count against a field.
         */
        fun agrees(pairs: List<Pair<Double?, Double?>>): Agreement {
            val both = pairs.mapNotNull { (a, b) ->
                if (a != null && b != null && a.isFinite() && b.isFinite()) a to b else null
            }
            if (both.size < MIN_OVERLAP) return Agreement(both.size, 0.0, 0.0, Double.NaN, agrees = false)

            val bias = both.sumOf { (a, b) -> a - b } / both.size
            val spread = Novelty.describe(both.map { (_, b) -> Novelty.Observation(0L, b) })?.mad ?: 0.0
            // ⚠️ A flat live window is not evidence of agreement, it is the absence of evidence: the
            // ratio would be zero or infinite depending only on whether the bias happened to be zero.
            if (spread <= 0.0) return Agreement(both.size, bias, 0.0, Double.NaN, agrees = false)

            val ratio = abs(bias) / spread
            return Agreement(both.size, bias, spread, ratio, agrees = ratio < BIAS_LIMIT)
        }

        /**
         * How much systematic offset, as a fraction of the field's own spread, still counts as the same
         * measurement.
         *
         * Set from the London measurement in the class note above: it admits pressure (0.009), gusts
         * (0.006), humidity (0.029), temperature (0.055) and dew point (0.143), and refuses cloud cover
         * (0.220) and wind speed (0.493). ⚠️ Dew point sits close to the line, which is the honest
         * position — its spread is small, so a quarter of a degree is a larger share of it than the same
         * quarter degree would be anywhere else. It is admitted because a mild offset is temporary and
         * disclosed: [Novelty.Basis] labels it, and [Novelty.score] drops the backfill outright as soon
         * as there are enough readings taken here.
         */
        const val BIAS_LIMIT = 0.15

        /** Fewest overlapping hours worth judging by. Below this the bias is noise about noise. */
        const val MIN_OVERLAP = 48

        /** How far back the reanalysis is asked for. A year is one request of roughly a quarter megabyte. */
        const val ARCHIVE_DAYS = 365L

        /** Days of overlap fetched purely to judge the archive against the live endpoint. */
        const val OVERLAP_DAYS = 7

        /** The air quality endpoint's own maximum look-back. */
        const val AIR_DAYS = 92

        private const val WEATHER_FIELDS =
            "temperature_2m,relative_humidity_2m,surface_pressure,dew_point_2m," +
                "wind_speed_10m,wind_gusts_10m,cloud_cover"

        private const val AIR_FIELDS =
            "pm10,pm2_5,ozone,nitrogen_dioxide,sulphur_dioxide,carbon_monoxide,european_aqi,us_aqi"

        /**
         * Ledger metric to archive column.
         *
         * ⚠️ `weather.visibility` is absent because the archive does not carry it, and
         * `weather.wind` / `weather.cloud` are present because [agrees] is what decides them — this
         * machine's answer, not London's. The values recorded live are the canonical ones
         * (`temperatureC`, `windKmh`, `surfacePressure`), which are Celsius, km/h and hPa, and those are
         * Open-Meteo's defaults, so no unit parameter is sent. Sending the user's display preference
         * here would be far worse than any model bias: a series half in Fahrenheit.
         */
        internal val WEATHER_MAP = listOf(
            "weather.temp" to "temperature_2m",
            "weather.dew-point" to "dew_point_2m",
            "weather.humidity" to "relative_humidity_2m",
            "weather.pressure" to "surface_pressure",
            "weather.wind" to "wind_speed_10m",
            "weather.gust" to "wind_gusts_10m",
            "weather.cloud" to "cloud_cover",
        )

        internal val AIR_MAP = listOf(
            "air.pm25" to "pm2_5",
            "air.pm10" to "pm10",
            "air.ozone" to "ozone",
            "air.no2" to "nitrogen_dioxide",
            "air.so2" to "sulphur_dioxide",
            "air.co" to "carbon_monoxide",
            "air.aqi-eu" to "european_aqi",
            "air.aqi-us" to "us_aqi",
        )

        /** `2026-08-14T07:00` as milliseconds. Null rather than an exception on anything unexpected. */
        internal fun hourMs(iso: String): Long? = runCatching {
            java.time.LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }
}
