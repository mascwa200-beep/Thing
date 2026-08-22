package dev.mascwa.pulse.data.settings

import kotlinx.serialization.Serializable

/**
 * The handful of preference VALUES the repositories in this module actually read.
 *
 * ⚠️ Deliberately only these six, and deliberately in the SAME package the rest of the settings live
 * in, so nothing in `:app` needed an import change. The repositories here are shared with the desktop
 * companion; `AppSettings` and `SettingsRepository` are not and never will be, because the two
 * applications store preferences in entirely different places — a DataStore on the phone, a JSON file
 * on the desktop. What crosses the boundary is a unit, a watch-list row and a saved place, which are
 * plain values either machine can hold.
 *
 * Everything else — the API keys, the assistant, the sensing and notification preferences — stays in
 * `:app` where it belongs. Splitting a package across two modules is fine on the JVM; splitting a
 * whole settings object across two applications that disagree about what settings are would not be.
 */

enum class TemperatureUnit(val apiValue: String, val symbol: String) {
    CELSIUS("celsius", "°C"),
    FAHRENHEIT("fahrenheit", "°F"),
}

enum class WindUnit(val apiValue: String, val symbol: String) {
    KMH("kmh", "km/h"),
    MPH("mph", "mph"),
    MS("ms", "m/s"),
    KNOTS("kn", "kn"),
}

enum class PrecipUnit(val apiValue: String, val symbol: String) {
    MM("mm", "mm"),
    INCH("inch", "in"),
}

enum class WatchType { INDEX, STOCK, FOREX, COMMODITY, CRYPTO }

@Serializable
data class WatchItem(
    val id: String,          // stooq symbol or coingecko id
    val label: String,       // display name
    val type: WatchType,
)

@Serializable
data class CustomFeed(
    val name: String,
    val url: String,
)

@Serializable
data class SavedLocation(
    val name: String,
    val country: String = "",
    val latitude: Double,
    val longitude: Double,
    val timezone: String = "auto",
)

/**
 * The units a forecast is asked for and rendered in.
 *
 * ⚠️ A record of the three values rather than the whole settings object, because the whole settings
 * object is where the two applications stop agreeing. Open-Meteo takes the unit as a request parameter,
 * so it is also part of the cache key — the same coordinate in Fahrenheit is a different answer.
 */
data class WeatherPreferences(
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val windUnit: WindUnit = WindUnit.KMH,
    val precipUnit: PrecipUnit = PrecipUnit.MM,
)

/**
 * What to price, and in what.
 *
 * Two lists rather than one because they come from different places — the watch list is quoted by Yahoo
 * and the coins by CoinGecko — and the repository fetches them separately so one source being down does
 * not take the other with it.
 */
data class MarketPreferences(
    val currencyCode: String = "USD",
    val watchlist: List<WatchItem> = emptyList(),
    val cryptoList: List<WatchItem> = emptyList(),
)

/**
 * Which country's figures, and the optional key for the one US-only source.
 *
 * The key is blank when there isn't one, which is the ordinary case: the EIA's retail pump prices need
 * a free key and cover only the United States, so everything else comes from the keyless sources.
 */
data class FuelPreferences(
    val countryCode: String = "US",
    val eiaKey: String = "",
) {
    val hasEia: Boolean get() = eiaKey.isNotBlank()
}
