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
    /**
     * Brave Search. The only tier of the assistant's `web` tool that reaches the open web.
     *
     * Everything else this app searches is keyless — the bundled library is on disk and Wikipedia
     * asks for nothing — so without this a question about today has no way to be answered, and
     * `SearchPlan` says so by name rather than quietly returning an encyclopaedia article instead.
     */
    val brave: String = "",
) {
    val hasNewsApi get() = newsApi.isNotBlank()
    val hasFred get() = fred.isNotBlank()
    val hasEia get() = eia.isNotBlank()
    val hasFinnhub get() = finnhub.isNotBlank()
    val hasOwm get() = openWeatherMap.isNotBlank()
    val hasBrave get() = brave.isNotBlank()
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
    /**
     * How the computer addresses you — "Captain", a rank, your name.
     *
     * Blank (the default) means it addresses you directly with no honorific, which is what a ship's
     * computer does. Injected into the system prompt each turn rather than hardcoded in replies.
     */
    val address: String = "",
    /**
     * The exact TTS voice to speak in, by the engine's own internal name.
     *
     * Blank means automatic, which leans female and American — the register the computer is dressed
     * as. Stored as the raw name rather than an index because the installed set changes when the user
     * adds or removes a language pack, and an index would then quietly point at a different voice.
     * A name that is no longer installed falls back to automatic rather than to silence.
     */
    val voiceName: String = "",
    /**
     * Listen for the "Computer" wake word while resident (requires the mic, opt-in).
     *
     * The field name is a serialization key and stays as it is; the word it listens for lives in
     * [dev.mascwa.pulse.core.telemetry.WakePhrase].
     */
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
) {
    val hasModelUrl get() = modelUrl.isNotBlank()
    /** Cloud chat is active when enabled and a key is present. */
    val cloudActive get() = cloudEnabled && cloudApiKey.isNotBlank()
}

/**
 * Notification preferences — the ONE-notification model: the app posts exactly one LCARS Situation Board
 * (per-row show toggles + one urgent-buzz switch) plus the full-screen breaking-news takeover. The 13
 * legacy per-category flags were pruned after the cutover (kotlinx `ignoreUnknownKeys` makes old saved
 * blobs load cleanly without them).
 */
@Serializable
data class NotificationPrefs(
    val masterEnabled: Boolean = true,
    /** BREAKING takeover: on a MAJOR event (a death, a disaster), the full-screen breaking-news page opens
     *  by itself — directly when "display over other apps" is granted, via full-screen intent otherwise. */
    val breakingInterrupt: Boolean = true,
    /** Near-real-time news polling (~90s) via the resident assistant (more battery/data). Keeps the board
     *  and the takeover check near-live; otherwise the 15-min worker is the cadence. */
    val liveBreakingNews: Boolean = false,
    val quietHoursEnabled: Boolean = false,
    val quietStartHour: Int = 22,
    val quietEndHour: Int = 7,
    /** Absolute percent move that puts an instrument on the board's MARKET row. */
    val marketMovePercent: Double = 3.0,
    // --- The board's rows + its one buzz switch. ---
    val showNewsRow: Boolean = true,
    val showMarketsRow: Boolean = true,
    val showWeatherRow: Boolean = true,
    val showAgendaRow: Boolean = true,
    /**
     * The board's HEALTH row: today's eating against today's target.
     *
     * ⚠️ Default ON, but the row is silent until there is both a target and something logged — so a
     * reader who never opens HEALTH never sees it, and switching this off is for someone who uses the
     * feature and would rather not have a calorie count on their lock screen.
     */
    val showHealthRow: Boolean = true,
    /** Whether a NEW urgent item (due reminder, major emergency, security/safety notice) may re-post the
     *  board with sound and vibration. Off = the board still updates, just always silently. */
    val urgentAlertsEnabled: Boolean = true,
    /**
     * The official-emergency takeover: a full-screen condition-red screen and a full-volume alarm
     * when a government feed publishes a life-threatening alert covering your location.
     *
     * ⚠️ Default ON, and deliberately so — an emergency warning that ships switched off protects
     * nobody. The alarm plays on the alarm stream regardless of silent or Do Not Disturb, which is
     * the owner's explicit instruction and the whole point of the feature; this switch turns the
     * feature off entirely, and there is no per-alert mute.
     *
     * ⚠️ Coverage is the feed's, not ours: `api.weather.gov` answers only inside the United States.
     */
    val emergencyTakeover: Boolean = true,
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

/**
 * Sensorium — ambient environment sensing. Default ON per the owner's explicit adaptive-24/7 choice:
 * the fusion core (barometer/light/motion/magnetometer/radio density) runs whenever the service does,
 * mic and camera sips arm on the next app-open (Android's while-in-use law) and are individually
 * toggleable. Everything is classify-then-discard: raw audio/frames never persist, never leave the
 * device — only text labels and numbers reach the rest of the app.
 */
@Serializable
data class SensingSettings(
    /** Master switch for the whole subsystem (the service exists only while this is on). */
    val enabled: Boolean = true,
    /** Ambient mic sips (YAMNet soundscape labels + safety-sound events). */
    val micSensing: Boolean = true,
    /** Ambient camera sips (EfficientNet scene labels; adaptive bursts). */
    val cameraSensing: Boolean = true,
    /** WiFi/BT scan bursts for crowd-density signals (needs Location for WiFi counts). */
    val radioSensing: Boolean = true,
    /** Record notable events into the episodic memory stream. */
    val rememberEvents: Boolean = true,
    /** Battery %, discharging, below which the whole stack stands down (heartbeat only). */
    val standDownBatteryPct: Int = 9,
    /**
     * The acoustic interrogator: continuous speech capture, offline transcription to a rolling
     * encrypted log, and fallacy screening against the offline library.
     *
     * ⚠️ **DEFAULT OFF, and it is the only sensing switch that is.** The rest of this group produces
     * text labels from sound and light and keeps nothing; this one writes down what was actually
     * said, which can include people who did not choose to be recorded. It also takes the microphone
     * outright, so the wake word stands down while it runs. Neither of those should begin because an
     * app updated.
     */
    val interrogator: Boolean = false,
)

/**
 * The LAN remote link — letting a paired desktop switch app features on and off from the same Wi-Fi.
 *
 * Default **OFF**: the link only listens while the user has explicitly turned it on, which is exactly what
 * "on and off whenever" means here. Nothing in this group is a secret: [pairedKeys] holds the *public*
 * keys of paired computers, so it deliberately does not need adding to `allSecretValues()` /
 * `SettingsBackup.redactSecrets`. This phone's own private key lives in the Keystore and never appears in
 * settings, and the pairing code is never persisted at all.
 */
@Serializable
data class RemoteSettings(
    /** Master switch. Off = no listening socket exists at all. */
    val enabled: Boolean = false,
    /** Port to listen on; must match what the desktop dials. */
    val port: Int = 8765,
    /** Base64 X.509/SPKI public keys of paired computers. Public data, not credentials. */
    val pairedKeys: List<String> = emptyList(),
    /** Friendly name this phone reports to the desktop. Blank = use the device model. */
    val deviceLabel: String = "",
)

/**
 * The HEALTH tab: who you are, where you are going, and how fast.
 *
 * These are the inputs [dev.mascwa.pulse.core.telemetry.MacroTargets] needs on every recomputation —
 * small, rarely changed, and useless without each other, which is what makes them settings rather than a
 * store. The weigh-ins and the food log are time series and live in `data/health`.
 *
 * ⚠️ Every field is a serialization key. Renaming one silently discards the *whole* blob's saved value on
 * every existing device, so a name that reads slightly wrong stays.
 *
 * ⚠️ These are personal but they are not credentials, so they are deliberately NOT added to
 * `allSecretValues()` or `SettingsBackup.redactSecrets` — a backup of your own app carrying your own
 * height is the point of a backup. Nothing here leaves the device by any other route.
 */
@Serializable
data class HealthSettings(
    /** Centimetres. 0 = not told, which every consumer must treat as "cannot compute" rather than zero. */
    val heightCm: Double = 0.0,

    /**
     * ⚠️ The YEAR of birth, not an age, and that is the whole reason this field is shaped like this. An
     * age stored as a number is wrong within twelve months and then stays wrong for ever, quietly
     * drifting the resting-rate floor that every calorie target sits on. A year is right until the
     * calendar says otherwise. 0 = not told.
     */
    val birthYear: Int = 0,

    /** [dev.mascwa.pulse.core.telemetry.Body.Sex] name. Unstated takes the higher resting rate — the safe direction. */
    val sex: String = "UNSPECIFIED",

    /** Kilograms. 0 = no goal, which means maintain. */
    val goalKg: Double = 0.0,

    /** Signed kilograms per week: negative loses, zero maintains, positive gains. */
    val ratePerWeekKg: Double = 0.0,

    /** [dev.mascwa.pulse.core.telemetry.MacroTargets.DietMode] name. */
    val dietMode: String = "BALANCED",

    /** Grams per kilogram of reference mass. 0 = whatever the diet mode says. */
    val proteinGPerKg: Double = 0.0,

    /** [dev.mascwa.pulse.core.telemetry.Expenditure.Activity] name — only used until the measurement takes over. */
    val activity: String = "LIGHT",

    /** [dev.mascwa.pulse.core.telemetry.BodyTrend.MassUnit] name. Display only; everything is stored in kg. */
    val massUnit: String = "KG",

    /** How far back the expenditure measurement looks. */
    val expenditureWindowDays: Int = 28,

    /** Whether the tab has ever been set up — decides between the welcome and the dashboard. */
    val configured: Boolean = false,
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
    val reactorDialSlots: List<String> = emptyList(), // Reactor Dial: package name pinned to each rotary slot ("" = empty); index = position
    val scanlines: Boolean = false,                   // CRT scanline overlay (off: clean 2077-HUD look, lighter)
    val glitch: Boolean = false,                      // chromatic glitch FX (off by default: no jank, less GPU/RAM)
    val bootAnimation: Boolean = false,               // cold-open boot sequence on launch (off by default to save startup RAM; re-enable in Settings → Appearance)
    val hudStrip: Boolean = true,                     // global HUD telemetry strip
    val hudDataStream: Boolean = true,                // HUD second-row live telemetry marquee
    val haptics: Boolean = true,                      // subtle UI haptic ticks
    // Interface chirps, synthesised at runtime — see ui/effects/LcarsAudio.kt. Defaults on
    // because it was asked for explicitly; it is one switch away in Appearance if it grates.
    val sounds: Boolean = true,

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
    /** Whether a viewed article looks up who ELSE is carrying the same story — one extra (cached, lazy)
     *  network search per article. Default on; off = the extra fetch never fires and the card simply says
     *  no other outlet was found.
     *
     *  ⚠️ The NAME is stale and deliberately not changed: this once drove a coloured bias-distribution +
     *  social-buzz strip, both of which were removed (a band of colour is not a fact, and a lean label
     *  rates a newspaper rather than reporting the event). Renaming the field would silently discard every
     *  existing device's saved value, which is a worse trade than an inaccurate identifier. */
    val showNewsCoverageStrip: Boolean = true,

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
    val navNight: Boolean = false,        // shade the half of the world where the Sun has set
    val navBasemap: String = "NIGHTWIRE", // MapLayerCatalog.Basemap name; unknown values fall back
    val navRelief: Boolean = false,       // hillshaded relief from elevation tiles
    val navRain: Boolean = false,         // live precipitation radar overlay
    val navTraffic: Boolean = false,      // live aircraft over the map
    val navSeismic: Boolean = false,      // recent earthquakes as a heatmap

    // NAV objectives: manual waypoints + the one currently tracked on the map.
    val waypoints: List<Waypoint> = emptyList(),
    val activeWaypointId: String? = null,

    // LCARS radio: favourited stations (local or curated), surfaced as a FAVOURITES section.
    val favoriteRadio: List<dev.mascwa.pulse.data.radio.RadioStation> = emptyList(),

    // LCARS music: Spotify OAuth (PKCE) link state. Scrubbed from the settings backup export.
    val spotify: SpotifyAuthState = SpotifyAuthState(),

    // LCARS STATUS: a user-chosen operator portrait (content URI) shown in the INTEGRITY section. Blank = none.
    val operatorPortraitUri: String = "",

    // Network security / privacy (Trusted Network Mode + at-rest/HTTPS encryption controls)
    val security: SecuritySettings = SecuritySettings(),

    // Sensorium: ambient environment sensing (mic/camera/sensor fusion)
    val sensing: SensingSettings = SensingSettings(),

    // LAN remote control from a paired desktop
    val remote: RemoteSettings = RemoteSettings(),

    // On-device assistant
    val health: HealthSettings = HealthSettings(),

    val jarvis: JarvisSettings = JarvisSettings(),

    // Notifications
    val notifications: NotificationPrefs = NotificationPrefs(),

    // Bookkeeping
    val onboardingComplete: Boolean = false,
    val deviceGateAcknowledged: Boolean = false,
    /** On launch AND on every foreground return, auto-download a GREEN (CI-passed) update newer than
     *  the running build and launch the installer for the user's one-tap confirm. A sideloaded app
     *  can't install fully silently (no device-owner), so the single Android "Update" tap is the floor;
     *  everything up to it is automatic. Default ON — the user asked for "as soon as it's green, install
     *  it, show the installer thing so I can tap." Off = never check. */
    val autoUpdate: Boolean = true,
    /** Highest build number we've already auto-installed (dedupe, so one build is only ever offered
     *  once however many times the app is opened). */
    val lastAutoUpdateCode: Int = 0,
    /**
     * A build whose install was committed but which has not yet been followed by a successful
     * launch. Zero means nothing is outstanding.
     *
     * ⚠️ **The loop-breaker for a self-installing app.** Since the install now completes with no
     * confirmation, a failure that is invisible — a refused session, a build that dies before it can
     * draw — would otherwise be met by downloading and committing again on the next check, forever.
     * While this is set the automatic path stands down; it is cleared the first time the app reaches
     * a successful resume, whichever build that turns out to be, so a merely-failed install costs
     * one cycle rather than the feature. The manual UPDATE control is never gated by it.
     *
     * It is deliberately NOT a claim that the build is bad — nothing here can know that — only that
     * one install is already in flight and a second should wait for evidence.
     */
    val unconfirmedUpdateCode: Int = 0,
    /** Offer live TV channels from the volunteer-maintained iptv-org catalogue alongside the handful
     *  of broadcasters' own feeds the app ships with. **Default OFF, and it is a switch rather than a
     *  silent merge on purpose**: that catalogue is of mixed origin and includes unauthorised
     *  restreams of channels that are not free to watch, which is the owner's call to make and not
     *  ours. Costs a ~215 KB fetch, cached for a week. */
    val communityChannels: Boolean = false,
    /** Skip community-flagged segments (sponsors, self-promo, intros…) during on-demand playback.
     *  **Default OFF and a switch on purpose**: it asks a third-party database about each video
     *  (privately — a 4-hex hash prefix, never the video id) and then jumps playback on its own,
     *  both of which are behaviours to opt into rather than discover. */
    val sponsorSkip: Boolean = false,
    /** When J.A.R.V.I.S. last ran an autonomous curiosity/research pass (throttle), and a round-robin
     *  cursor over the standing interests + the device subject so it rotates what it investigates. */
    val lastCuriosityMs: Long = 0,
    val curiosityIndex: Int = 0,
    /** When the Mnemosyne reflection pass last ran (throttle). */
    val lastReflectionMs: Long = 0,
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
    apiKeys.brave,
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
