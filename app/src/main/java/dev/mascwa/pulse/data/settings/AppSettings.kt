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

/** Spotify OAuth state (Authorization Code + PKCE). Tokens stay on-device in the settings JSON and are
 *  scrubbed from the backup export. No client secret is stored — PKCE doesn't use one. */
@Serializable
data class SpotifyAuthState(
    val accessToken: String = "",
    val refreshToken: String = "",
    /** Epoch ms when [accessToken] expires; the repo refreshes within 60s of it. */
    val expiresAtMs: Long = 0,
    /** The in-flight PKCE verifier between launching the browser and the redirect coming back. */
    val pendingVerifier: String = "",
    /** After the user connects the App Remote player once, reconnect to it automatically when the MUSIC
     *  tab opens — so playback "just works" via the (background) Spotify app without re-tapping connect. */
    val appRemoteAutoConnect: Boolean = false,
    /** Cached for the UI header. */
    val displayName: String = "",
    val premium: Boolean = false,
) {
    val linked get() = accessToken.isNotBlank() || refreshToken.isNotBlank()
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
    /** When a cloud brain (e.g. OpenRouter) is active, pass the wake-word command transcript through it
     *  once to fix speech-to-text mishears before acting — better understanding without a heavier STT
     *  model. No effect unless [cloudActive]. On by default; adds one short cloud round-trip per command. */
    val voiceCloudInterpret: Boolean = true,
    /** Speak proactive context remarks aloud while resident (greeting on start, reactions to power/network
     *  changes) — not just show them in the notification. Off by default; respects quiet hours and never
     *  speaks while the console is open or mid-command. Requires the resident service + a TTS engine. */
    val speakProactive: Boolean = false,
    /** Show a glanceable J.A.R.V.I.S. HUD (clock, brief, latest reply) on connected display glasses / an
     *  external or wireless display, via Android's Presentation API. Off by default; shows while the app
     *  is open and only when an external "presentation" display is connected. */
    val glassesHud: Boolean = false,
    /** Let J.A.R.V.I.S. use tools (web/GitHub-read/device/memory) in a bounded agentic loop. */
    val agentToolsEnabled: Boolean = false,
    /** Optional GitHub token for the read-only repo tool (private repos / higher rate limit). */
    val githubToken: String = "",
    /** Auto-upload scrubbed crash/debug reports to the repo's `debug-reports` branch for remote reading.
     *  Needs the GitHub token; secrets are stripped before anything leaves the device. */
    val debugReports: Boolean = true,
    /** Periodically synthesise recent episodic observations into higher-level REFLECTION memories
     *  (Mnemosyne). Cloud-gated + throttled; a no-op without a working cloud brain. */
    val reflectionEnabled: Boolean = true,
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
    /** Let J.A.R.V.I.S. draft changes to its OWN source and open GitHub PRs (experimental; needs a
     *  write-scoped GitHub token). Off by default. */
    val selfCodingEnabled: Boolean = false,
    /** When self-coding is on, auto-merge its PRs once CI is green (then the updater offers the build,
     *  which you still confirm). Off by default. Never merges on a red/pending build. */
    val selfCodeAutoMerge: Boolean = false,
    /** When self-coding is on, let J.A.R.V.I.S. OPEN its own PRs without the per-change approval tap
     *  (CI must still build it + you still install the build; its approval-gate/CI/signing remain
     *  off-limits). Off by default — this hands over the human review step, so opt in deliberately. */
    val autonomousSelfCoding: Boolean = false,
    /** Detailed activity log: record full content — your chat messages and the arguments to the
     *  assistant's tool calls — to the on-device activity log (which a cloud brain can read when cloud
     *  chat is on), not just operational events. Raw API keys / tokens are ALWAYS scrubbed regardless.
     *  On by default per the owner's explicit choice; turn off for operational-only (no content). */
    val verboseActivityLog: Boolean = true,
    /** Let J.A.R.V.I.S. autonomously research your standing interests + his own curiosities in the
     *  background (cloud-gated; spends provider credits) and bring you findings with a notification.
     *  Off by default — opt in, since it acts on its own and uses your cloud key. */
    val autonomousCuriosity: Boolean = false,
    /** Free-text directive the owner types for J.A.R.V.I.S. to interpret when tending the self-care/life-sim
     *  system — how strict to be, when to nudge vs. leave you alone, which habits matter, etc. Injected into
     *  the assistant's context every turn; it uses the `selfcare` tool to read/act on the game accordingly. */
    val selfCareDirective: String = "",
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
    /** Survival check-ins for the S.P.E.C.I.A.L. life-sim: nudge you when a real-decaying need (hydration/
     *  nourishment/energy/hygiene) runs low, or an appointment is imminent — keep the operator alive. */
    val survivalAlerts: Boolean = true,
    /** Frequent, quiet rotating field-survival tips (a 300+ catalog). Silent (low-priority), self-replacing. */
    val survivalTips: Boolean = true,
    /** "Don't break your streak" nudge: when a meaningful self-care streak is in its grace day (done
     *  yesterday, not yet today) remind you once to check in and keep it alive. Default ON. */
    val streakReminders: Boolean = true,
    /** Master switch for the self-care habit check-in system (the due-habit card + the aggressive path). Off
     *  = J.A.R.V.I.S. never asks whether you've showered/brushed/eaten/hydrated. Default ON. */
    val selfCareCheckins: Boolean = true,
    /** Habits (by [RealActivity] name) the owner has switched OFF individually — never checked in on. Default
     *  empty = all of [HabitCheckin.DEFAULTS] active. */
    val disabledHabits: List<String> = emptyList(),
    /** AGGRESSIVE self-care check-ins: a full-screen alert over the lock screen that can't be dismissed until
     *  answered (vs. an ordinary notification). Default OFF — opt in knowingly; it takes over the screen. */
    val aggressiveCheckin: Boolean = false,
    /** LOCKOUT: after you dodge a check-in ("not yet" / caught lie), pin the phone (device-owner kiosk) until
     *  the sensors confirm you did the task. Default OFF. Safe: auto-releases after 10 min no matter what,
     *  the emergency dialer stays reachable, and a 5-second hold overrides it. */
    val lockoutEnabled: Boolean = false,
    /** J.A.R.V.I.S.-DRIVEN timing: instead of a fixed clock, let the assistant decide WHEN to check in — it
     *  reads your directive + current needs + what the sensors actually saw and nudges only when it judges the
     *  moment right. Needs cloud chat on (spends credits). Off by default; when on it supersedes the fixed
     *  schedule and fires an aggressive/soft check-in per your other switches. */
    val jarvisDrivenCheckins: Boolean = false,
    val dailyDigest: Boolean = true,
    /** Notify when a newer app build is available to download/install in Settings. */
    val updateChecks: Boolean = true,
    val digestHour: Int = 8,            // 0..23 local
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
    /** Absolute percent move on a watch item that triggers an alert. */
    val marketMovePercent: Double = 3.0,
)

/**
 * Network-security / privacy controls. **Trusted Network Mode** disables Wi-Fi when the device leaves the
 * designated home network and cellular is carrying it (dual-verified), re-enabling it on return; the radio
 * toggle needs Pulse provisioned as a Device Owner (one-time adb), otherwise it degrades to a notification.
 * The encryption controls protect credentials at rest and keep egress on HTTPS.
 */
@Serializable
data class SecuritySettings(
    /** Master switch for Trusted Network Mode. Default OFF — it's a deliberate, provisioned feature. */
    val trustedNetworkMode: Boolean = false,
    /** SSIDs the user designates as "home" (quotes stripped, compared case-insensitively). */
    val homeSsids: List<String> = emptyList(),
    /** Minutes to hold Wi-Fi off while away before re-enabling to re-probe for the home network. */
    val reprobeMinutes: Int = 10,
    /** Notify (vs. silently failing) when Wi-Fi can't be toggled because Pulse isn't a Device Owner. */
    val notifyWhenUnprovisioned: Boolean = true,
    /** Encrypt credentials (cloud + GitHub tokens) at rest via the Android Keystore. Default ON. */
    val encryptSecretsAtRest: Boolean = true,
    /** Block cleartext (non-HTTPS) outbound requests except explicitly whitelisted hosts. Default OFF
     *  (your http radio streams rely on cleartext; turning this on disables http-only stations). */
    val httpsOnly: Boolean = false,
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
    val liveWallpaperReadout: Boolean = true,         // J.A.R.V.I.S. live wallpaper: show the data readout under the reactor
    val reactorDialSlots: List<String> = emptyList(), // Reactor Dial: package name pinned to each rotary slot ("" = empty); index = position
    val scanlines: Boolean = false,                   // CRT scanline overlay (off: clean 2077-HUD look, lighter)
    val glitch: Boolean = false,                      // chromatic glitch FX (off by default: no jank, less GPU/RAM)
    val bootAnimation: Boolean = false,               // cold-open boot sequence on launch (off by default to save startup RAM; re-enable in Settings → Appearance)
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

    // PIP-BOY radio: favourited stations (local or curated), surfaced as a FAVOURITES section.
    val favoriteRadio: List<dev.mascwa.pulse.data.radio.RadioStation> = emptyList(),

    // PIP-BOY music: Spotify OAuth (PKCE) link state. Scrubbed from the settings backup export.
    val spotify: SpotifyAuthState = SpotifyAuthState(),

    // PIP-BOY STATUS: a user-chosen operator portrait (content URI) shown in the CND section. Blank = none.
    val operatorPortraitUri: String = "",

    // Network security / privacy (Trusted Network Mode + at-rest/HTTPS encryption controls)
    val security: SecuritySettings = SecuritySettings(),

    // On-device assistant
    val jarvis: JarvisSettings = JarvisSettings(),

    // Notifications
    val notifications: NotificationPrefs = NotificationPrefs(),

    // Bookkeeping
    val onboardingComplete: Boolean = false,
    val deviceGateAcknowledged: Boolean = false,
    /** Highest build number we've already shown an "update available" notification for (dedupe). */
    val lastUpdateNotifiedCode: Int = 0,
    /** On launch AND on every foreground return, auto-download a GREEN (CI-passed) update newer than
     *  the running build and launch the installer for the user's one-tap confirm. A sideloaded app
     *  can't install fully silently (no device-owner), so the single Android "Update" tap is the floor;
     *  everything up to it is automatic. Default ON — the user asked for "as soon as it's green, install
     *  it, show the installer thing so I can tap." Off = never check. */
    val autoUpdate: Boolean = true,
    /** Highest build number we've already auto-prompted to install (dedupe so we don't re-launch the
     *  installer on every open after the user dismisses it). */
    val lastAutoUpdateCode: Int = 0,
    /** Ambient perception for the S.P.E.C.I.A.L. life-sim: while the game (STAT) screen is open, sample the
     *  camera + mic on-device to sense your surroundings (indoor/outdoor, sounds) and shape the game. Only
     *  text labels are produced — no image/audio is ever stored or sent. Off = the game uses a neutral scene
     *  and the camera/mic are never touched. Default ON; the camera indicator shows while it samples. */
    val ambientSensing: Boolean = true,
    /** Per-sense sub-switches under [ambientSensing]: sample the microphone / camera respectively. Both ON by
     *  default; turning one off leaves the other running (e.g. hearing on, camera off — no camera indicator). */
    val ambientMic: Boolean = true,
    val ambientCamera: Boolean = true,
    /** ALWAYS-ON ambient sensing: run the camera/mic samplers in a foreground service (over the lock screen
     *  too), not just while the game screen is open, so the activity-attestation lie-catcher has a continuous
     *  history. Default OFF — it's a persistent-notification, battery + privacy commitment; opt in knowingly.
     *  Still bound by [ambientSensing] + [ambientMic]/[ambientCamera] and the runtime permissions. */
    val ambientSensingAlways: Boolean = false,
    /** When J.A.R.V.I.S. last ran an autonomous curiosity/research pass (throttle), and a round-robin
     *  cursor over the standing interests + the device subject so it rotates what it investigates. */
    val lastCuriosityMs: Long = 0,
    val curiosityIndex: Int = 0,
    /** When the Mnemosyne reflection pass last ran (throttle). */
    val lastReflectionMs: Long = 0,
    /** When the J.A.R.V.I.S.-driven self-care timing pass last ran (throttle — it's a cloud call). */
    val lastSelfCareCheckMs: Long = 0,
    /** Periodically anchor the blackbox audit ledger head to a public RFC-3161 TSA (opt-in; sends only a
     *  hash). [lastLedgerAnchorMs] throttles it (~daily). The manual "Anchor now" button is always available. */
    val autoAnchorLedger: Boolean = false,
    val lastLedgerAnchorMs: Long = 0,
    /** Dedupe for the hardware-attestation audit producer: the last recorded posture signature (record
     *  only when it CHANGES — a posture change is a real security event; identical verdicts are noise) and
     *  when the probe last ran (so the worker doesn't re-attest on every tick). */
    val lastAttestationSig: String = "",
    val lastAttestationCheckMs: Long = 0,
    /** Auto-scroll speed multiplier for the home markets ticker (1.0 = default; higher = faster).
     *  Adjustable via the Settings slider; clamped to a sane range where used. */
    val tickerSpeed: Float = 1.0f,
    /** (Deprecated — the visual "watching" overlay was removed; kept to avoid a settings migration.) */
    val jarvisPresence: Boolean = true,
    /** What the user wants J.A.R.V.I.S. to brief them on in the home status feed (free text, e.g. a
     *  project, a topic, "device health"). Drives the feed's MONITORING line. Never chat content. */
    val jarvisFeedTopic: String = "",
    /** PHONE PENALTIES (the life-sim needs revoke real device capabilities when neglected). Master switch,
     *  default OFF — it uses Device-Owner powers to lock phone features until you tend the need. Every lock is
     *  reversible; turning this OFF releases everything. Needs Device Owner to have any effect. */
    val phonePenalties: Boolean = false,
    /** The harsher "kiosk" tier: while ANY need is critical, pin Pulse to the foreground so you must tend it
     *  (emergency dialer stays reachable; guaranteed auto-release net). On by default WITHIN the opt-in feature. */
    val phonePenaltyKiosk: Boolean = true,
    /** Packages the "distraction apps" lock (hydration penalty) suspends. Empty = that one lock is a no-op
     *  until you pick apps. Pulse / the dialer / the launcher are always filtered out defensively. */
    val phonePenaltyDistractionApps: List<String> = emptyList(),
    /** Persisted set of currently-penalised needs (NeedKind names) — the hysteresis state, so a penalty
     *  survives a restart and releases only when the need recovers. Managed by PhonePenaltyController. */
    val phonePenalisedNeeds: List<String> = emptyList(),
)

/**
 * Every live credential value the app holds, as plain strings — the single source of truth for "what must
 * never leave the device in cleartext." Mirrors [dev.mascwa.pulse.data.settings.SettingsBackup.redactSecrets]'s
 * canonical set (apiKeys, jarvis tokens, Spotify OAuth) so a newly-added secret is covered by construction.
 * Used by the debug-report uploader for exact-match scrubbing (the load-bearing pass that catches opaque,
 * pattern-evading tokens like a Spotify `BQ…` blob or a hyphenated `sk-or-v1-…` key). Blanks are filtered out.
 */
fun AppSettings.allSecretValues(): List<String> = listOf(
    apiKeys.newsApi, apiKeys.fred, apiKeys.eia, apiKeys.finnhub, apiKeys.openWeatherMap, apiKeys.nasa,
    jarvis.githubToken, jarvis.modelToken, jarvis.cloudApiKey,
    spotify.accessToken, spotify.refreshToken, spotify.pendingVerifier,
).map { it.trim() }.filter { it.isNotBlank() }

/** Sensible International defaults for first launch. */
object DefaultData {
    val watchlist = listOf(
        // Indices
        WatchItem("^spx", "S&P 500", WatchType.INDEX),
        WatchItem("^ndq", "Nasdaq 100", WatchType.INDEX),
        WatchItem("^dji", "Dow Jones", WatchType.INDEX),
        WatchItem("^rut", "Russell 2000", WatchType.INDEX),
        WatchItem("^ftm", "FTSE 100", WatchType.INDEX),
        WatchItem("^dax", "DAX", WatchType.INDEX),
        WatchItem("^cac", "CAC 40", WatchType.INDEX),
        WatchItem("^nkx", "Nikkei 225", WatchType.INDEX),
        WatchItem("^hsi", "Hang Seng", WatchType.INDEX),
        WatchItem("^vix", "VIX", WatchType.INDEX),
        // Stocks
        WatchItem("aapl.us", "Apple", WatchType.STOCK),
        WatchItem("msft.us", "Microsoft", WatchType.STOCK),
        WatchItem("nvda.us", "NVIDIA", WatchType.STOCK),
        WatchItem("googl.us", "Alphabet", WatchType.STOCK),
        WatchItem("amzn.us", "Amazon", WatchType.STOCK),
        WatchItem("meta.us", "Meta", WatchType.STOCK),
        WatchItem("tsla.us", "Tesla", WatchType.STOCK),
        // FX
        WatchItem("eurusd", "EUR/USD", WatchType.FOREX),
        WatchItem("gbpusd", "GBP/USD", WatchType.FOREX),
        WatchItem("usdjpy", "USD/JPY", WatchType.FOREX),
        // Commodities
        WatchItem("gc.f", "Gold", WatchType.COMMODITY),
        WatchItem("si.f", "Silver", WatchType.COMMODITY),
        WatchItem("cl.f", "Crude Oil (WTI)", WatchType.COMMODITY),
        WatchItem("cb.f", "Brent", WatchType.COMMODITY),
    )
    val crypto = listOf(
        WatchItem("bitcoin", "Bitcoin", WatchType.CRYPTO),
        WatchItem("ethereum", "Ethereum", WatchType.CRYPTO),
        WatchItem("solana", "Solana", WatchType.CRYPTO),
        WatchItem("ripple", "XRP", WatchType.CRYPTO),
        WatchItem("binancecoin", "BNB", WatchType.CRYPTO),
        WatchItem("dogecoin", "Dogecoin", WatchType.CRYPTO),
    )
}
