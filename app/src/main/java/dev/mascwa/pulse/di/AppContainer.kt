package dev.mascwa.pulse.di

import android.content.Context
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.data.economy.EconomyRepository
import dev.mascwa.pulse.data.economy.WorldBankClient
import dev.mascwa.pulse.data.fuel.FuelRepository
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.emergency.EmergencyService
import dev.mascwa.pulse.data.news.NewsRepository
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.sensors.SurvivalTools
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.survival.SurvivalContentRepository
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.notifications.NotificationScheduler
import dev.mascwa.pulse.notifications.Notifier
import kotlinx.serialization.json.Json

/**
 * Manual dependency-injection graph. A single container of lazily-created
 * singletons held by the Application. Avoids annotation processors (Hilt/kapt)
 * which keeps the Gradle build robust across Android Studio / AGP versions.
 */
class AppContainer(private val appContext: Context) {

    val json: Json by lazy { HttpClient.defaultJson() }
    val http: HttpClient by lazy { HttpClient.create(json, appContext.cacheDir) }
    val diskCache: DiskCache by lazy { DiskCache(appContext, json) }
    /** Checks the CI `latest` GitHub release for a newer build and downloads the APK (in-app updater). */
    val updateRepository: dev.mascwa.pulse.data.update.UpdateRepository by lazy {
        dev.mascwa.pulse.data.update.UpdateRepository(appContext, http, settingsRepository)
    }

    /** On-device editable "interpreted layer" (persona charter + version history; later: approvals,
     *  authored tools). Separate DataStore file so it never migrates/wipes settings or the Room DB. */
    val selfEditStore: dev.mascwa.pulse.data.selfedit.SelfEditStore by lazy {
        dev.mascwa.pulse.data.selfedit.SelfEditStore(appContext, json)
    }

    /**
     * Bounded Coil image loader so thumbnail-heavy screens (news/markets/images/social) can't grow
     * the heap without limit — a key part of stopping the OS low-memory kills. Installed as the app's
     * singleton loader via [PulseApplication.newImageLoader], so every `AsyncImage` uses it.
     */
    val imageLoader: coil.ImageLoader by lazy {
        coil.ImageLoader.Builder(appContext)
            .memoryCache { coil.memory.MemoryCache.Builder(appContext).maxSizePercent(0.15).build() }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(java.io.File(appContext.cacheDir, "image_cache"))
                    .maxSizeBytes(48L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext, json) }

    private val worldBank: WorldBankClient by lazy { WorldBankClient(http) }

    val newsRepository: NewsRepository by lazy {
        NewsRepository(http, diskCache, settingsRepository)
    }
    val marketsRepository: MarketsRepository by lazy {
        MarketsRepository(http, diskCache, settingsRepository)
    }
    val economyRepository: EconomyRepository by lazy {
        EconomyRepository(worldBank, diskCache, settingsRepository)
    }
    val fuelRepository: FuelRepository by lazy {
        FuelRepository(http, marketsRepository, worldBank, diskCache, settingsRepository)
    }
    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(http, diskCache, settingsRepository)
    }
    val spaceWeatherRepository: SpaceWeatherRepository by lazy {
        SpaceWeatherRepository(http, diskCache)
    }
    val orbitalRepository: OrbitalRepository by lazy {
        OrbitalRepository(http, diskCache, settingsRepository)
    }
    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    val connectivityObserver: dev.mascwa.pulse.core.connectivity.ConnectivityObserver by lazy {
        dev.mascwa.pulse.core.connectivity.ConnectivityObserver(appContext)
    }

    val overpassRepository: OverpassRepository by lazy { OverpassRepository(http, diskCache) }

    // NAV objectives: device-calendar events (geocoded) + manual waypoints persisted in settings.
    val calendarObjectives: dev.mascwa.pulse.data.objectives.CalendarObjectivesRepository by lazy {
        dev.mascwa.pulse.data.objectives.CalendarObjectivesRepository(appContext)
    }
    val waypointStore: dev.mascwa.pulse.data.objectives.WaypointStore by lazy {
        dev.mascwa.pulse.data.objectives.WaypointStore(settingsRepository)
    }
    val safetyRepository: dev.mascwa.pulse.data.safety.SafetyRepository by lazy {
        dev.mascwa.pulse.data.safety.SafetyRepository(http, diskCache)
    }
    val survivalContentRepository: SurvivalContentRepository by lazy {
        SurvivalContentRepository(appContext, json)
    }
    val emergencyService: EmergencyService by lazy { EmergencyService(appContext) }
    val survivalTools: SurvivalTools by lazy { SurvivalTools(appContext) }
    val socialRepository: dev.mascwa.pulse.data.social.SocialRepository by lazy {
        dev.mascwa.pulse.data.social.SocialRepository(http, diskCache, settingsRepository)
    }
    val imageRepository: dev.mascwa.pulse.data.images.ImageRepository by lazy {
        dev.mascwa.pulse.data.images.ImageRepository(http)
    }

    val radarRepository: dev.mascwa.pulse.data.radar.RadarRepository by lazy {
        dev.mascwa.pulse.data.radar.RadarRepository(http, diskCache)
    }

    // ---- J.A.R.V.I.S. Matrix (on-device assistant) ----
    val jarvisDatabase: dev.mascwa.pulse.data.jarvis.db.JarvisDatabase by lazy {
        dev.mascwa.pulse.data.jarvis.db.JarvisDatabase.build(appContext)
    }
    val jarvisMemory: dev.mascwa.pulse.data.jarvis.JarvisMemory by lazy {
        dev.mascwa.pulse.data.jarvis.JarvisMemory(jarvisDatabase)
    }
    /** The on-device knowledge library (docs RAG) J.A.R.V.I.S. retrieves from. */
    val knowledgeStore: dev.mascwa.pulse.data.jarvis.KnowledgeStore by lazy {
        dev.mascwa.pulse.data.jarvis.KnowledgeStore(jarvisDatabase)
    }
    /** Seeds the APK-bundled reference docs into [knowledgeStore] on first launch. */
    val knowledgeSeeder: dev.mascwa.pulse.jarvis.KnowledgeSeeder by lazy {
        dev.mascwa.pulse.jarvis.KnowledgeSeeder(appContext, knowledgeStore, jarvisMemory)
    }
    /** Provisions + tracks the on-device LLM model file (download / delete / path). */
    val modelManager: dev.mascwa.pulse.jarvis.inference.ModelManager by lazy {
        dev.mascwa.pulse.jarvis.inference.ModelManager(appContext)
    }
    /**
     * The engine the console + (later) banter/router talk to. Routes to the real
     * MediaPipe LLM once a model is provisioned, else the offline persona core.
     */
    val inferenceEngine: dev.mascwa.pulse.jarvis.inference.RoutingInferenceEngine by lazy {
        dev.mascwa.pulse.jarvis.inference.RoutingInferenceEngine(
            appContext,
            modelManager,
            // Read the chat-template choice + model URL fresh per generation so a Setup change
            // takes effect immediately, without reloading the model.
            promptConfig = {
                val j = settingsRepository.current().jarvis
                dev.mascwa.pulse.jarvis.inference.PromptConfig(j.chatFormat, j.modelUrl.ifBlank { null })
            },
            // Preferred backend, read fresh at load time (0=auto, 1=GPU, 2=CPU).
            backendProvider = { runCatching { settingsRepository.current().jarvis.inferenceBackend }.getOrDefault(0) },
            // On a native GPU crash, persist CPU so the next load (ensureReady before each send) uses it.
            onNativeCrash = {
                runCatching { settingsRepository.update { it.copy(jarvis = it.jarvis.copy(inferenceBackend = 2)) } }
            },
            // Total token budget (input + output), read fresh at load time. The engine reserves part
            // of this for the answer and clamps the input to the rest, so a long chat can't overflow.
            maxTokensProvider = { runCatching { settingsRepository.current().jarvis.maxTokens }.getOrDefault(2048) },
            // Cloud brain: when the user has enabled it + set a key, chat routes to the provider's
            // OpenAI-compatible endpoint (and the on-device model is never loaded).
            cloudConfig = {
                runCatching {
                    val j = settingsRepository.current().jarvis
                    if (j.cloudActive) {
                        dev.mascwa.pulse.jarvis.inference.CloudConfig(
                            baseUrl = j.cloudProvider.baseUrl,
                            apiKey = j.cloudApiKey,
                            model = j.cloudModel.ifBlank { j.cloudProvider.defaultModel },
                        )
                    } else {
                        null
                    }
                }.getOrNull()
            },
        )
    }
    /** Reads live device power/network/time context for proactive banter + status answers. */
    val deviceContextProvider: dev.mascwa.pulse.core.telemetry.DeviceContextProvider by lazy {
        dev.mascwa.pulse.core.telemetry.DeviceContextProvider(appContext)
    }
    val banterEngine: dev.mascwa.pulse.core.telemetry.BanterContextEngine by lazy {
        dev.mascwa.pulse.core.telemetry.BanterContextEngine()
    }
    val intentRouter: dev.mascwa.pulse.core.telemetry.IntentRouter by lazy {
        dev.mascwa.pulse.core.telemetry.IntentRouter()
    }
    /** Executes the "Lockdown" macro (clipboard wipe, DND/silent, halt BLE) with honest results. */
    val actionOrchestrator: dev.mascwa.pulse.jarvis.orchestrator.ActionOrchestrator by lazy {
        dev.mascwa.pulse.jarvis.orchestrator.ActionOrchestrator(appContext)
    }
    /** On-device text-to-speech so J.A.R.V.I.S. can speak replies (AOSP, no Play Services). */
    val textToSpeech: dev.mascwa.pulse.jarvis.voice.TextToSpeechEngine by lazy {
        dev.mascwa.pulse.jarvis.voice.TextToSpeechEngine(appContext)
    }
    /** Offline on-device speech recognition (Vosk) for tap-to-talk and the wake word. */
    val voskSpeech: dev.mascwa.pulse.jarvis.voice.VoskSpeech by lazy {
        dev.mascwa.pulse.jarvis.voice.VoskSpeech(appContext)
    }
    /** Android's on-device Google recognizer for the (more accurate) post-wake command; private,
     *  no network. Falls back to Vosk when on-device recognition isn't available on a device. */
    val deviceSpeech: dev.mascwa.pulse.jarvis.voice.DeviceSpeechRecognizer by lazy {
        dev.mascwa.pulse.jarvis.voice.DeviceSpeechRecognizer(appContext)
    }

    /** App-wide crash reporter backing the global handler + the SYS crash console. */
    val crashReporter: dev.mascwa.pulse.crash.CrashReporter by lazy {
        dev.mascwa.pulse.crash.CrashReporter(appContext)
    }
    /** Read-only, on-device tools J.A.R.V.I.S. can invoke (web/GitHub-read/device/memory). */
    val agentTools: List<dev.mascwa.pulse.jarvis.agent.JarvisTool> by lazy {
        listOf(
            dev.mascwa.pulse.jarvis.agent.WebSearchTool(http),
            dev.mascwa.pulse.jarvis.agent.WebFetchTool(http),
            dev.mascwa.pulse.jarvis.agent.RepoReadTool(http, settingsRepository),
            dev.mascwa.pulse.jarvis.agent.RememberTool(jarvisMemory),
            dev.mascwa.pulse.jarvis.agent.RecallTool(jarvisMemory),
            dev.mascwa.pulse.jarvis.agent.KnowledgeTool(knowledgeStore),
            dev.mascwa.pulse.jarvis.agent.DeviceTool(deviceContextProvider),
            // Device-action tools — each opens the relevant app pre-filled (you confirm the final step).
            dev.mascwa.pulse.jarvis.agent.CallTool(appContext),
            dev.mascwa.pulse.jarvis.agent.SmsTool(appContext),
            dev.mascwa.pulse.jarvis.agent.EmailTool(appContext),
            dev.mascwa.pulse.jarvis.agent.CalendarEventTool(appContext),
            dev.mascwa.pulse.jarvis.agent.AlarmTool(appContext),
            dev.mascwa.pulse.jarvis.agent.TimerTool(appContext),
            dev.mascwa.pulse.jarvis.agent.CameraTool(appContext),
            dev.mascwa.pulse.jarvis.agent.ContactsTool(appContext),
            dev.mascwa.pulse.jarvis.agent.SpotifyTool(appContext),
            dev.mascwa.pulse.jarvis.agent.MapsTool(appContext),
            dev.mascwa.pulse.jarvis.agent.SettingsTool(appContext),
            dev.mascwa.pulse.jarvis.agent.OpenLinkTool(appContext),
            dev.mascwa.pulse.jarvis.agent.TorchTool(appContext),
            dev.mascwa.pulse.jarvis.agent.ClipboardTool(appContext),
        )
    }
    /** Enqueue-only self-edit + read-only inspection tools, offered to the model only when the user
     *  has turned self-edit on. They never mutate anything — they queue a PendingAction for approval. */
    private val selfEditTools: List<dev.mascwa.pulse.jarvis.agent.JarvisTool> by lazy {
        listOf(
            dev.mascwa.pulse.jarvis.agent.ProposePersonaTool(selfEditStore),
            dev.mascwa.pulse.jarvis.agent.ProposeDocTool(selfEditStore, "add"),
            dev.mascwa.pulse.jarvis.agent.ProposeDocTool(selfEditStore, "edit"),
            dev.mascwa.pulse.jarvis.agent.ProposeDocTool(selfEditStore, "delete"),
            dev.mascwa.pulse.jarvis.agent.ProposeResearchTool(selfEditStore),
            dev.mascwa.pulse.jarvis.agent.ProposeToolTool(selfEditStore),
            dev.mascwa.pulse.jarvis.agent.SelfInspectTool(selfEditStore, knowledgeStore, appContext),
        )
    }

    /** Vetted capability implementations an authored Lua tool may be granted (web/fetch/docs/recall).
     *  Each delegates to an existing built-in tool — authored scripts get no raw fs/network. */
    private val toolCapabilities: Map<String, suspend (String) -> String> by lazy {
        mapOf<String, suspend (String) -> String>(
            "web" to { q -> dev.mascwa.pulse.jarvis.agent.WebSearchTool(http).run(q) },
            "fetch" to { q -> dev.mascwa.pulse.jarvis.agent.WebFetchTool(http).run(q) },
            "docs" to { q -> dev.mascwa.pulse.jarvis.agent.KnowledgeTool(knowledgeStore).run(q) },
            "recall" to { q -> dev.mascwa.pulse.jarvis.agent.RecallTool(jarvisMemory).run(q) },
        )
    }

    /** The single applier of approved self-changes (called only from the Approvals UI tap). RESEARCH
     *  fetches via the vetted web-search tool — and only after the user approves. */
    val approvalGate: dev.mascwa.pulse.jarvis.selfedit.ApprovalGate by lazy {
        dev.mascwa.pulse.jarvis.selfedit.ApprovalGate(
            selfEditStore,
            knowledgeStore,
            research = { topic -> dev.mascwa.pulse.jarvis.agent.WebSearchTool(http).run(topic) },
        )
    }

    /** Live tool set resolved per agent run: base tools + (self-edit tools + approved authored Lua
     *  tools, when self-edit is enabled). Resolved each run, so toggles / new tools apply at once. */
    private val agentToolsProvider: suspend () -> List<dev.mascwa.pulse.jarvis.agent.JarvisTool> = {
        val selfOn = runCatching { settingsRepository.current().jarvis.selfEditEnabled }.getOrDefault(false)
        val authored = if (selfOn) {
            runCatching {
                selfEditStore.current().authoredTools
                    .filter { it.enabled }
                    .map { dev.mascwa.pulse.jarvis.agent.LuaScriptTool(it, toolCapabilities) }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        agentTools + (if (selfOn) selfEditTools else emptyList()) + authored
    }

    /** Bounded ReAct loop wiring the on-device model to the live tool set + durable memory + knowledge. */
    val agentOrchestrator: dev.mascwa.pulse.jarvis.agent.AgentOrchestrator by lazy {
        dev.mascwa.pulse.jarvis.agent.AgentOrchestrator(inferenceEngine, jarvisMemory, agentToolsProvider, knowledgeStore)
    }

    /** Builds J.A.R.V.I.S.'s spoken daily brief from live weather/objectives/news/markets. */
    val briefingBuilder: dev.mascwa.pulse.jarvis.BriefingBuilder by lazy {
        dev.mascwa.pulse.jarvis.BriefingBuilder(
            weatherRepository, newsRepository, marketsRepository,
            calendarObjectives, waypointStore, locationProvider, settingsRepository,
        )
    }

    /** Curious Learning: rate-limited, gap-driven questions over the durable memory store. */
    val curiosityEngine: dev.mascwa.pulse.jarvis.curiosity.CuriosityEngine by lazy {
        dev.mascwa.pulse.jarvis.curiosity.CuriosityEngine(jarvisMemory, inferenceEngine, settingsRepository)
    }

    /** Compass is stateful per-screen, so hand out a fresh controller each time. */
    fun newCompassController(): CompassController = CompassController(appContext)

    /** Telemetry is stateful per-screen (sensor lifecycle), so hand out fresh. */
    fun newTelemetryController(): dev.mascwa.pulse.data.sensors.TelemetryController =
        dev.mascwa.pulse.data.sensors.TelemetryController(appContext)

    val notifier: Notifier by lazy { Notifier(appContext) }
    val notificationScheduler: NotificationScheduler by lazy { NotificationScheduler(appContext) }
}
