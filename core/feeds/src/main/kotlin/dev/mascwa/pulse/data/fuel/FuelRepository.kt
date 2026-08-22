package dev.mascwa.pulse.data.fuel

import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.Fetched
import dev.mascwa.pulse.data.economy.WorldBankClient
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.settings.FuelPreferences
import dev.mascwa.pulse.data.settings.WatchItem
import dev.mascwa.pulse.data.settings.WatchType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    /** Whose pump prices, and the optional EIA key. Read per fetch. See [FuelPreferences]. */
    private val preferences: suspend () -> FuelPreferences,
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
        val s = preferences()
        val country = s.countryCode.ifBlank { "US" }
        val key = "fuel_${country}_${s.hasEia}"
        if (!force) {
            cache.read(key, ttl, FuelData.serializer())?.let {
                return Fetched(it.value, true, it.savedAtMs)
            }
        }
        return try {
            val data = coroutineScope {
                val benchmarksD = async { runCatching { markets.quotesFor(energySymbols) }.getOrDefault(emptyList()) }
                val eiaD = async {
                    if (s.hasEia && country.equals("US", true)) {
                        runCatching { fetchEiaUsRetail(s.eiaKey) }.getOrDefault(emptyList())
                    } else emptyList()
                }

                FuelData(
                    countryCode = country,
                    countryName = country,
                    benchmarks = benchmarksD.await(),
                    // The World Bank pump-price indicators (EP.PMP.SGAS.CD / EP.PMP.DESL.CD)
                    // were archived/removed upstream, so national averages are only
                    // available via the optional EIA key (US) for now.
                    nationalPrices = emptyList(),
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
        val data = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }
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
