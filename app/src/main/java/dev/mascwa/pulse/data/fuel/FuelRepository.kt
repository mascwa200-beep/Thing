package dev.mascwa.pulse.data.fuel

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.economy.ValueFormat
import dev.mascwa.pulse.data.economy.WorldBankClient
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.settings.WatchItem
import dev.mascwa.pulse.data.settings.WatchType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * International, keyless-first fuel & energy picture:
 *  - Live energy futures (Brent, WTI, natural gas, RBOB gasoline, heating oil)
 *    via Stooq.
 *  - National average pump prices via the World Bank (annual, US$/litre).
 *  - Optional US weekly retail prices via EIA when an API key is supplied.
 */
class FuelRepository(
    private val http: HttpClient,
    private val markets: MarketsRepository,
    private val worldBank: WorldBankClient,
    private val cache: DiskCache,
    private val settings: SettingsRepository,
) {
    private val ttl = 30 * 60 * 1000L

    private val energySymbols = listOf(
        WatchItem("cb.f", "Brent Crude", WatchType.COMMODITY),
        WatchItem("cl.f", "WTI Crude", WatchType.COMMODITY),
        WatchItem("ng.f", "Natural Gas", WatchType.COMMODITY),
        WatchItem("rb.f", "Gasoline (RBOB)", WatchType.COMMODITY),
        WatchItem("ho.f", "Heating Oil", WatchType.COMMODITY),
    )

    suspend fun fetch(force: Boolean): Fetched<FuelData> {
        val s = settings.current()
        val country = s.countryCode.ifBlank { "US" }
        val key = "fuel_${country}_${s.apiKeys.hasEia}"
        if (!force) {
            cache.read(key, ttl, FuelData.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val data = coroutineScope {
                val benchmarksD = async { runCatching { markets.stooqQuotes(energySymbols) }.getOrDefault(emptyList()) }
                val gasolineD = async {
                    runCatching {
                        worldBank.seriesRaw(
                            "EP.PMP.SGAS.CD", "Gasoline pump price", "US$/litre",
                            ValueFormat.CURRENCY_USD, false, country, startYear = 2005,
                        )
                    }.getOrNull()
                }
                val dieselD = async {
                    runCatching {
                        worldBank.seriesRaw(
                            "EP.PMP.DESL.CD", "Diesel pump price", "US$/litre",
                            ValueFormat.CURRENCY_USD, false, country, startYear = 2005,
                        )
                    }.getOrNull()
                }
                val eiaD = async {
                    if (s.apiKeys.hasEia && country.equals("US", true)) {
                        runCatching { fetchEiaUsRetail(s.apiKeys.eia) }.getOrDefault(emptyList())
                    } else emptyList()
                }

                val gasoline = gasolineD.await()
                val diesel = dieselD.await()
                val countryName = gasoline?.countryName
                    ?: diesel?.countryName ?: country

                FuelData(
                    countryCode = country,
                    countryName = countryName,
                    benchmarks = benchmarksD.await(),
                    nationalPrices = listOfNotNull(
                        gasoline?.latest?.let { PumpPrice("Gasoline", it.value, it.year) },
                        diesel?.latest?.let { PumpPrice("Diesel", it.value, it.year) },
                    ),
                    usRetail = eiaD.await(),
                )
            }
            cache.write(key, data, FuelData.serializer())
            Fetched(data, false)
        } catch (e: Exception) {
            cache.readAny(key, FuelData.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
            throw e
        }
    }

    /** EIA v2 weekly US retail prices: regular gasoline (EPMR) & diesel (EPD2D). */
    private suspend fun fetchEiaUsRetail(apiKey: String): List<RetailPricePoint> {
        val url = "https://api.eia.gov/v2/petroleum/pri/gnd/data/?api_key=$apiKey" +
            "&frequency=weekly&data[0]=value" +
            "&facets[duoarea][]=NUS" +
            "&facets[product][]=EPMR&facets[product][]=EPD2D" +
            "&sort[0][column]=period&sort[0][direction]=desc&offset=0&length=10"
        val text = http.getString(url)
        val data = http.json.parseToJsonElement(text)
            .jsonObject["response"]?.jsonObject?.get("data")?.jsonArray ?: return emptyList()

        // Take the most recent point per product.
        val seen = mutableSetOf<String>()
        val out = mutableListOf<RetailPricePoint>()
        for (el in data) {
            val obj = el.jsonObject
            val product = (obj["product-name"] ?: obj["product"])
                ?.jsonPrimitive?.contentOrNull ?: continue
            if (!seen.add(product)) continue
            out += RetailPricePoint(
                product = product,
                usdPerGallon = obj["value"]?.jsonPrimitive?.doubleOrNull,
                period = obj["period"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return out
    }
}
