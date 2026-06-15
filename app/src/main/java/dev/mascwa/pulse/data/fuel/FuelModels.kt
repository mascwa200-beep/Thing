package dev.mascwa.pulse.data.fuel

import dev.mascwa.pulse.data.markets.Quote
import kotlinx.serialization.Serializable

/** A national average retail pump price (per litre), with the data year. */
@Serializable
data class PumpPrice(
    val fuel: String,             // "Gasoline" / "Diesel"
    val usdPerLitre: Double?,
    val year: Int?,
)

/** Optional EIA US weekly retail price point (USD per gallon). */
@Serializable
data class RetailPricePoint(
    val product: String,
    val usdPerGallon: Double?,
    val period: String?,
)

@Serializable
data class FuelData(
    val countryCode: String,
    val countryName: String,
    val benchmarks: List<Quote>,          // live crude/energy futures (Stooq)
    val nationalPrices: List<PumpPrice>,  // World Bank annual pump prices
    val usRetail: List<RetailPricePoint>, // EIA (only if key + US)
)
