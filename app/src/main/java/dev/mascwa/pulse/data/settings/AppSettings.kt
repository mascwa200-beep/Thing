package dev.mascwa.pulse.data.settings

import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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

/** Sections shown on the Home dashboard; order is user-customizable. */
enum class HomeSection(val title: String) {
    HEADLINES("Top Headlines"),
    MARKETS("Markets"),
    WEATHER("Weather"),
    ECONOMY("Economy"),
    INFLATION("Inflation"),
    FUEL("Fuel & Energy"),
    POLITICS("Politics"),
    TECH("Tech"),
    POPCULTURE("Pop Culture"),
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

/** Optional, user-supplied free API keys. Blank = use keyless sources. */
@Serializable
data class ApiKeys(
    val newsApi: String = "",
    val fred: String = "",
    val eia: String = "",
    val finnhub: String = "",
    val openWeatherMap: String = "",
) {
    val hasNewsApi get() = newsApi.isNotBlank()
    val hasFred get() = fred.isNotBlank()
    val hasEia get() = eia.isNotBlank()
    val hasFinnhub get() = finnhub.isNotBlank()
    val hasOwm get() = openWeatherMap.isNotBlank()
}

/** Notification preferences. */
@Serializable
data class NotificationPrefs(
    val masterEnabled: Boolean = true,
    val breakingNews: Boolean = true,
    val marketAlerts: Boolean = true,
    val weatherAlerts: Boolean = true,
    val dailyDigest: Boolean = true,
    val digestHour: Int = 8,            // 0..23 local
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
    /** Absolute percent move on a watch item that triggers an alert. */
    val marketMovePercent: Double = 3.0,
)

/** The full, single source of truth for user configuration. */
@Serializable
data class AppSettings(
    // Appearance
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val highContrast: Boolean = false,

    // Locale / region (International defaults; everything overridable here)
    val countryCode: String = "US",     // ISO 3166-1 alpha-2 (economy/fuel/news region)
    val newsLanguage: String = "en",    // Google News hl
    val newsCountry: String = "US",     // Google News gl / ceid
    val currencyCode: String = "USD",

    // Units
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val windUnit: WindUnit = WindUnit.KMH,
    val precipUnit: PrecipUnit = PrecipUnit.MM,
    val use24HourClock: Boolean = true,

    // Refresh / data
    val refreshIntervalMinutes: Int = 60,
    val refreshOnlyOnWifi: Boolean = false,
    val newsItemsPerCategory: Int = 30,

    // Home dashboard
    val homeSections: List<HomeSection> = HomeSection.entries.toList(),

    // Markets
    val watchlist: List<WatchItem> = DefaultData.watchlist,
    val cryptoList: List<WatchItem> = DefaultData.crypto,

    // Weather
    val useDeviceLocation: Boolean = true,
    val savedLocations: List<SavedLocation> = emptyList(),
    val selectedLocationIndex: Int = 0,

    // News
    val customFeeds: List<CustomFeed> = emptyList(),
    val mutedKeywords: List<String> = emptyList(),

    // Integrations
    val apiKeys: ApiKeys = ApiKeys(),

    // Notifications
    val notifications: NotificationPrefs = NotificationPrefs(),

    // Bookkeeping
    val onboardingComplete: Boolean = false,
    val deviceGateAcknowledged: Boolean = false,
)

/** Sensible International defaults for first launch. */
object DefaultData {
    val watchlist = listOf(
        WatchItem("^spx", "S&P 500", WatchType.INDEX),
        WatchItem("^ndq", "Nasdaq 100", WatchType.INDEX),
        WatchItem("^dji", "Dow Jones", WatchType.INDEX),
        WatchItem("^ftm", "FTSE 100", WatchType.INDEX),
        WatchItem("^dax", "DAX", WatchType.INDEX),
        WatchItem("^nkx", "Nikkei 225", WatchType.INDEX),
        WatchItem("aapl.us", "Apple", WatchType.STOCK),
        WatchItem("msft.us", "Microsoft", WatchType.STOCK),
        WatchItem("nvda.us", "NVIDIA", WatchType.STOCK),
        WatchItem("googl.us", "Alphabet", WatchType.STOCK),
        WatchItem("eurusd", "EUR/USD", WatchType.FOREX),
        WatchItem("gbpusd", "GBP/USD", WatchType.FOREX),
        WatchItem("gc.f", "Gold", WatchType.COMMODITY),
        WatchItem("cl.f", "Crude Oil (WTI)", WatchType.COMMODITY),
    )
    val crypto = listOf(
        WatchItem("bitcoin", "Bitcoin", WatchType.CRYPTO),
        WatchItem("ethereum", "Ethereum", WatchType.CRYPTO),
        WatchItem("solana", "Solana", WatchType.CRYPTO),
        WatchItem("ripple", "XRP", WatchType.CRYPTO),
    )
}
