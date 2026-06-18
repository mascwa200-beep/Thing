package dev.mascwa.pulse.data.settings

import dev.mascwa.pulse.data.objectives.Waypoint
import dev.mascwa.pulse.jarvis.inference.ChatFormat
import dev.mascwa.pulse.jarvis.inference.CloudProvider
import kotlinx.serialization.Serializable

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Free, keyless search engines opened in the browser (no search API needed). */
enum class SearchEngine(val label: String, val urlTemplate: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s"),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query=%s"),
}

/** NIGHTWIRE accent colours, user-selectable; recolour the whole UI live. */
enum class AccentColor(val argb: Long, val r: Int, val g: Int, val b: Int) {
    CYAN(0xFF2DE2E6, 45, 226, 230),
    MAGENTA(0xFFFF3864, 255, 56, 100),
    AMBER(0xFFFFC542, 255, 197, 66),
    LIME(0xFF5CFF8F, 92, 255, 143),
    VIOLET(0xFFB061FF, 176, 97, 255),
    YELLOW(0xFFFCEE0A, 252, 238, 10),
}

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

/** Personal medical / ICE info for the SOS card (stays on-device). */
@Serializable
data class EmergencyCard(
    val fullName: String = "",
    val bloodType: String = "",
    val allergies: String = "",
    val medications: String = "",
    val conditions: String = "",
    val notes: String = "",
) {
    val isEmpty get() = fullName.isBlank() && bloodType.isBlank() && allergies.isBlank() &&
        medications.isBlank() && conditions.isBlank() && notes.isBlank()
}

@Serializable
data class EmergencyContact(val name: String, val phone: String)

/** Optional, user-supplied free API keys. Blank = use keyless sources. */
@Serializable
data class ApiKeys(
    val newsApi: String = "",
    val fred: String = "",
    val eia: String = "",
    val finnhub: String = "",
    val openWeatherMap: String = "",
    val nasa: String = "",
) {
    val hasNewsApi get() = newsApi.isNotBlank()
    val hasFred get() = fred.isNotBlank()
    val hasEia get() = eia.isNotBlank()
    val hasFinnhub get() = finnhub.isNotBlank()
    val hasOwm get() = openWeatherMap.isNotBlank()
    /** NASA NeoWs falls back to the shared DEMO_KEY when no personal key is set. */
    val nasaOrDemo get() = nasa.ifBlank { "DEMO_KEY" }
}

/** On-device assistant (J.A.R.V.I.S. Matrix) configuration. Stays on-device. */
@Serializable
data class JarvisSettings(
    /** Direct URL to a MediaPipe-compatible .task LLM model (e.g. a Hugging Face file). */
    val modelUrl: String = "",
    /** Optional Bearer token for gated hosts (e.g. a Hugging Face access token). */
    val modelToken: String = "",
    /** Model's total token budget (input context + output). The engine reserves part for the answer
     *  and clamps the input to the rest, so a long chat can't overflow the context and crash. */
    val maxTokens: Int = 2048,
    /** Keep J.A.R.V.I.S. resident via the Active-Matrix foreground service. */
    val residentService: Boolean = false,
    /** Monitor a paired BLE heart-rate strap and check in on anomalies (opt-in). */
    val vitalsTracking: Boolean = false,
    /** Speak replies aloud using the device's on-device text-to-speech engine. */
    val voiceReplies: Boolean = false,
    /** Listen for the "J.A.R.V.I.S." wake word while resident (requires the mic, opt-in). */
    val wakeWord: Boolean = false,
    /** After a spoken reply, reopen the mic briefly so you can answer WITHOUT re-saying the wake word
     *  (Alexa-style follow-up). Ends when you stay silent. Requires the wake word. */
    val followUpMode: Boolean = false,
    /** Let J.A.R.V.I.S. autonomously keep a spoken conversation going (when its reply expects a
     *  response) and announce when it's wrapping up. Builds on follow-up; requires the wake word. */
    val conversationMode: Boolean = false,
    /** Let J.A.R.V.I.S. use tools (web/GitHub-read/device/memory) in a bounded agentic loop. */
    val agentToolsEnabled: Boolean = false,
    /** Optional GitHub token for the read-only repo tool (private repos / higher rate limit). */
    val githubToken: String = "",
    /**
     * Which chat template to wrap prompts in. [ChatFormat.AUTO] picks ChatML/Gemma from the model
     * URL; switch to [ChatFormat.PLAIN] if a model's replies come out garbled or double-templated.
     */
    val chatFormat: ChatFormat = ChatFormat.AUTO,
    /** MediaPipe inference backend: 0=auto (let it choose), 1=GPU, 2=CPU. Auto-falls back to CPU if a
     *  GPU decode crashes the inference process. CPU is slower but far more compatible. */
    val inferenceBackend: Int = 0,
    /** Let J.A.R.V.I.S. PROPOSE edits to its own persona/knowledge/tools + research (each applied only
     *  on your explicit approval in the Approvals screen). Opt-in; requires agent tools too. */
    val selfEditEnabled: Boolean = false,
    /** Use a cloud AI for chat instead of the on-device model (opt-in). When on AND [cloudApiKey] is
     *  set, chat + the agent loop call the provider — chat text leaves the device. Voice stays local. */
    val cloudEnabled: Boolean = false,
    /** Which cloud provider's OpenAI-compatible endpoint to call. */
    val cloudProvider: CloudProvider = CloudProvider.OPENROUTER,
    /** API key for [cloudProvider] (get one at its keyUrl). Stays on-device in settings. */
    val cloudApiKey: String = "",
    /** Optional model override; blank uses the provider's default model. */
    val cloudModel: String = "",
    /** How often J.A.R.V.I.S. asks a gap-filling "curiosity" question: 0 Off / 1 Low / 2 Medium / 3 High. */
    val curiosityLevel: Int = 1,
) {
    val hasModelUrl get() = modelUrl.isNotBlank()
    /** Cloud chat is active when enabled and a key is present. */
    val cloudActive get() = cloudEnabled && cloudApiKey.isNotBlank()
}

/** Notification preferences. */
@Serializable
data class NotificationPrefs(
    val masterEnabled: Boolean = true,
    val breakingNews: Boolean = true,
    /** Near-real-time breaking news: poll every ~90s via the resident assistant (more battery/data).
     *  Only runs while the resident J.A.R.V.I.S. service is on; otherwise news uses the 15-min worker. */
    val liveBreakingNews: Boolean = false,
    val marketAlerts: Boolean = true,
    val weatherAlerts: Boolean = true,
    val spaceAlerts: Boolean = true,
    val auroraAlerts: Boolean = true,     // NOAA OVATION aurora probability at your location
    val safetyAlerts: Boolean = true,
    val flightAlerts: Boolean = false,    // overhead aircraft (opt-in; can be frequent near airports)
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
    val theme: ThemeMode = ThemeMode.DARK,            // NIGHTWIRE is a dark terminal
    val dynamicColor: Boolean = false,                // accent swatches drive colour instead
    val highContrast: Boolean = false,
    val accentColor: AccentColor = AccentColor.CYAN,
    val amoledBlack: Boolean = false,                 // true-black surfaces
    val scanlines: Boolean = true,                    // CRT scanline overlay
    val glitch: Boolean = true,                       // chromatic glitch FX
    val bootAnimation: Boolean = true,                // terminal boot sequence on launch
    val hudStrip: Boolean = true,                     // global HUD telemetry strip
    val hudDataStream: Boolean = true,                // HUD second-row live telemetry marquee
    val haptics: Boolean = true,                      // subtle UI haptic ticks

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

    // Social & search
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val lemmyInstance: String = "lemmy.world",
    val mastodonInstance: String = "mastodon.social",

    // Image search — user-added site URLs (a %s is replaced with the query)
    val customImageSites: List<String> = emptyList(),

    // Integrations
    val apiKeys: ApiKeys = ApiKeys(),

    // Safety / SOS
    val emergencyCard: EmergencyCard = EmergencyCard(),
    val emergencyContacts: List<EmergencyContact> = emptyList(),
    val autoSendSos: Boolean = false,
    val monitoredPlaces: List<SavedLocation> = emptyList(),
    val safetyRadiusKm: Int = 50,

    // NAV map view (persisted so the map opens how you left it).
    val nav3d: Boolean = true,            // 3D tilted view vs flat 2D
    val navHeadingUp: Boolean = false,    // rotate map with phone heading vs north-up

    // NAV objectives: manual waypoints + the one currently tracked on the map.
    val waypoints: List<Waypoint> = emptyList(),
    val activeWaypointId: String? = null,

    // On-device assistant
    val jarvis: JarvisSettings = JarvisSettings(),

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
