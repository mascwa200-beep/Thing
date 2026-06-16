package dev.mascwa.pulse.data.economy

import dev.mascwa.pulse.core.network.HttpClient
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
        higherIsBetter: Boolean,
        countryCode: String,
        startYear: Int = 2000,
    ): IndicatorSeries {
        val country = countryCode.ifBlank { "WLD" }
        val url = "https://api.worldbank.org/v2/country/$country/indicator/$indicatorId" +
            "?format=json&per_page=100&date=$startYear:2100"
        val text = http.getString(url)
        val root = http.json.parseToJsonElement(text).jsonArray
        val dataArr = (root.getOrNull(1) as? JsonArray)
        if (dataArr == null) {
            return IndicatorSeries(
                indicatorId, title, country, country,
                unit, format, higherIsBetter, emptyList(),
            )
        }
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
        )
    }
}
