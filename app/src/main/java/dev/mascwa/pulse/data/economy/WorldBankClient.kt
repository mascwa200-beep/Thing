package dev.mascwa.pulse.data.economy

import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Thin client for the World Bank Open Data API (keyless, international).
 * Endpoint shape: a 2-element JSON array of metadata plus a list of data points.
 */
class WorldBankClient(private val http: HttpClient) {

    /**
     * Hard cap on simultaneous World Bank requests, shared across every caller of this client.
     *
     * The dashboard fans one request per indicator, all at once. That was nine and is now closer to
     * twenty, and OkHttp will happily run twelve per host — but this app has already paid for that
     * lesson once: an eleven-request burst got a proxy IP durably banned by Yahoo, which is why
     * `MarketsRepository` carries `yahooGate`. One gate per host is the rule that came out of it, so
     * the second host to get a wide fan-out gets a gate at the same time as the fan-out, not after
     * the ban.
     */
    private val gate = Semaphore(CONCURRENCY)

    suspend fun series(
        indicator: EconomyIndicator,
        countryCode: String,
        startYear: Int = 2000,
    ): IndicatorSeries = seriesRaw(
        indicatorId = indicator.id,
        title = indicator.title,
        unit = indicator.unit,
        format = indicator.format,
        higherIsBetter = indicator.higherIsBetter,
        countryCode = countryCode,
        startYear = startYear,
    )

    /** Fetch any World Bank indicator by raw id (e.g. fuel pump prices). */
    suspend fun seriesRaw(
        indicatorId: String,
        title: String,
        unit: String,
        format: ValueFormat,
        higherIsBetter: Boolean?,
        countryCode: String,
        startYear: Int = 2000,
    ): IndicatorSeries {
        val country = countryCode.ifBlank { "WLD" }
        val url = "https://api.worldbank.org/v2/country/$country/indicator/$indicatorId" +
            "?format=json&per_page=100&date=$startYear:2100"
        val text = gate.withPermit { http.getString(url) }
        val root = http.json.parseToJsonElement(text).jsonArray
        val dataArr = (root.getOrNull(1) as? JsonArray)
        if (dataArr == null) {
            return IndicatorSeries(
                indicatorId, title, country, country,
                unit, format, higherIsBetter, emptyList(),
            )
        }
        // When the World Bank itself last revised this series. A third date, distinct from the year
        // the figure describes and from the moment this app fetched it — the response has carried it
        // all along and it was being dropped, which left those three collapsed into one on screen.
        val lastUpdatedMs = (root.getOrNull(0) as? kotlinx.serialization.json.JsonObject)
            ?.get("lastupdated")?.jsonPrimitive?.contentOrNull
            ?.let { isoDateToEpochMs(it) }
        var countryName = country
        val points = buildList {
            for (el in dataArr) {
                val obj = el.jsonObject
                val value = obj["value"]?.jsonPrimitive?.doubleOrNull ?: continue
                val year = obj["date"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: continue
                val cName = obj["country"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
                if (cName != null) countryName = cName
                add(IndicatorPoint(year, value))
            }
        }.sortedBy { it.year }

        return IndicatorSeries(
            indicatorId = indicatorId,
            indicatorTitle = title,
            countryCode = country,
            countryName = countryName,
            unit = unit,
            format = format,
            higherIsBetter = higherIsBetter,
            points = points,
            lastUpdatedMs = lastUpdatedMs,
        )
    }

    private companion object {
        const val CONCURRENCY = 5

        /**
         * `"2026-07-13"` to epoch millis (UTC midnight), or null if it is anything else.
         *
         * Hand-parsed rather than run through a date formatter because the shape is fixed and a
         * formatter would drag a locale and a timezone into a value that has neither. Days-from-civil
         * is the same arithmetic `EconomyVintage` uses, kept here rather than shared because the core
         * module takes no dependency on this one.
         */
        fun isoDateToEpochMs(iso: String): Long? {
            val parts = iso.trim().split('-')
            if (parts.size != 3) return null
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull()?.takeIf { it in 1..12 } ?: return null
            val day = parts[2].toIntOrNull()?.takeIf { it in 1..31 } ?: return null
            val y = if (month <= 2) year - 1 else year
            val era = Math.floorDiv(y.toLong(), 400L)
            val yoe = y - era * 400
            val mp = if (month > 2) month - 3 else month + 9
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365L + yoe / 4 - yoe / 100 + doy
            return (era * 146_097L + doe - 719_468L) * 86_400_000L
        }
    }
}
