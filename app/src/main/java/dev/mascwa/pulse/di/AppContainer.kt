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
import dev.mascwa.pulse.analytics.UsageDataTracker
import dev.mascwa.pulse.analytics.DiagnosticsManager
import dev.mascwa.pulse.analytics.FeatureRecommendationEngine
import dev.mascwa.pulse.analytics.LearningEngine
import kotlinx.serialization.json.Json

class AppContainer(private val appContext: Context) {

    val json: Json by lazy { HttpClient.defaultJson() }
    val http: HttpClient by lazy { HttpClient.create(json, appContext.cacheDir) }
    val diskCache: DiskCache by lazy { DiskCache(appContext, json) }
    val updateRepository: dev.mascwa.pulse.data.update.UpdateRepository by lazy {
        dev.mascwa.pulse.data.update.UpdateRepository(appContext, http, settingsRepository)
    }
    val gitHubRepo: dev.mascwa.pulse.data.selfcode.GitHubRepo by lazy {
        dev.mascwa.pulse.data.selfcode.GitHubRepo(settingsRepository)
    }
    val selfCoder: dev.mascwa.pulse.data.selfcode.SelfCoder by lazy {
        dev.mascwa.pulse.data.selfcode.SelfCoder(inferenceEngine, gitHubRepo, selfEditStore)
    }

    val selfEditStore: dev.mascwa.pulse.data.selfedit.SelfEditStore by lazy {
        dev.mascwa.pulse.data.selfedit.SelfEditStore(appContext, json)
    }

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

    // Enhanced integration features
    val usageDataTracker: UsageDataTracker by lazy { UsageDataTracker(settingsRepository) }
    val diagnosticsManager: DiagnosticsManager by lazy { DiagnosticsManager() }
    val featureRecommendationEngine: FeatureRecommendationEngine by lazy { FeatureRecommendationEngine(settingsRepository) }
    val learningEngine: LearningEngine by lazy { LearningEngine(settingsRepository) }

    val jarvisDatabase: dev.mascwa.pulse.data.jarvis.db.JarvisDatabase by lazy {
        dev.mascwa.pulse.data.jarvis.db.JarvisDatabase.build(appContext)
    }
    val jarvisMemory: dev.mascwa.pulse.data.jarvis.JarvisMemory by lazy {
        dev.mascwa.pulse.data.jarvis.JarvisMemory(jarvisDatabase)
    }
    val knowledgeStore: dev.mascwa.pulse.data.jarvis.KnowledgeStore by lazy {
        dev.mascwa.pulse.data.jarvis.KnowledgeStore(jarvisDatabase)
    }
    val knowledgeSeeder: dev.mascwa.pulse.jarvis.KnowledgeSeeder by lazy {
        dev.mascwa.pulse.jarvis.KnowledgeSeeder(appContext, knowledgeStore, jarvisMemory)
    }
    val modelManager: dev.mascwa.pulse.jarvis.inference.ModelManager by lazy {
        dev.mascwa.pulse.jarvis.inference.ModelManager(appContext)
    }
    val inferenceEngine: dev.mascwa.pulse.jarvis.inference.RoutingInferenceEngine by lazy {
        dev.mascwa.pulse.jarvis.inference.RoutingInferenceEngine(
            appContext,
            modelManager,
            promptConfig = {
                val j = settingsRepository.current().jarvis
                dev.mascwa.pulse.jarvis.inference.PromptConfig(j.chatFormat, j.modelUrl.ifBlank { null })
            },
            backendProvider = { runCatching { settingsRepository.current().jarvis.inferenceBackend }.getOrDefault(0) },
            onNativeCrash = {
                runCatching { settingsRepository.update { it.copy(jarvis = it.jarvis.copy(inferenceBackend = 2)) } }
            },
            maxTokensProvider = { runCatching { settingsRepository.current().jarvis.maxTokens }.getOrDefault(2048) },
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
    val deviceContextProvider: dev.mascwa.pulse.core.telemetry.DeviceContextProvider by lazy {
        dev.mascwa.pulse.core.telemetry.DeviceContextProvider(appContext)
    }
    val banterEngine: dev.mascwa.pulse.core.telemetry.BanterContextEngine by lazy {
        dev.mascwa.pulse.core.telemetry.BanterContextEngine()
    }
    val intentRouter: dev.mascwa.pulse.core.telemetry.IntentRouter by lazy {
        dev.mascwa.pulse.core.telemetry.IntentRouter()
    }
    val actionOrchestrator: dev.mascwa.pulse.jarvis.orchestrator.ActionOrchestrator by lazy {
        dev.mascwa.pulse.jarvis.orchestrator.ActionOrchestrator(appContext)
    }
    val textToSpeech: dev.mascwa.pulse.jarvis.voice.TextToSpeechEngine by lazy {
        dev.mascwa.pulse.jarvis.voice.TextToSpeechEngine(appContext)
    }
    val voskSpeech: dev.mascwa.pulse.jarvis.voice.VoskSpeech by lazy {
        dev.mascwa.pulse.jarvis.voice.VoskSpeech(appContext)
    }
    val deviceSpeech: dev.mascwa.pulse.jarvis.voice.DeviceSpeechRecognizer by lazy {
        dev.mascwa.pulse.jarvis.voice.DeviceSpeechRecognizer(appContext)
    }

    val crashReporter: dev.mascwa.pulse.crash.CrashReporter by lazy {
        dev.mascwa.pulse.crash.CrashReporter(appContext)
    }
    val agentTools: List<dev.mascwa.pulse.jarvis.agent.JarvisTool> by lazy {
        listOf(
            dev.mascwa.pulse.jarvis.agent.WebSearchTool(http),
            dev.mascwa.pulse.jarvis.agent.WebFetchTool(http),
            dev.mascwa.pulse.jarvis.agent.DownloadTool(appContext, http),
            dev.mascwa.pulse.jarvis.agent.RepoReadTool(http, settingsRepository),
            dev.mascwa.pulse.jarvis.agent.RememberTool(jarvisMemory),
            dev.mascwa.pulse.jarvis.agent.RecallTool(jarvisMemory),
            dev.mascwa.pulse.jarvis.agent.KnowledgeTool(knowledgeStore),
            dev.mascwa.pulse.jarvis.agent.DeviceTool(deviceContextProvider),
            dev.mascwa.pulse.jarvis.agent.WeatherTool(weatherRepository, locationProvider, settingsRepository),
            dev.mascwa.pulse.jarvis.agent.LocationTool(locationProvider),
            dev.mascwa.pulse.jarvis.agent.CallTool(appContext),
            dev.mascwa.pulse.jarvis.agent.SmsTool(appContext),
            dev.mascwa.pulse.jarvis.agent.EmailTool(appContext),
            dev.mascwa.pulse.jarvis.agent.CalendarEventTool(appContext),
            dev.mascwa.pulse.jarvis.agent.AlarmTool(appContext),
            dev.mascwa.pulse.jarvis.agent.TimerTool(appContext),
            dev.mascwa.pulse.jarvis.agent.ReminderTool(appContext, jarvisMemory),
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

    private val toolCapabilities: Map<String, suspend (String) -> String> by lazy {
        mapOf<String, suspend (String) -> String>(
            "web" to { q -> dev.mascwa.pulse.jarvis.agent.WebSearchTool(http).run(q) },
            "fetch" to { q -> dev.mascwa.pulse.jarvis.agent.WebFetchTool(http).run(q) },
            "docs" to { q -> dev.mascwa.pulse.jarvis.agent.KnowledgeTool(knowledgeStore).run(q) },
            "recall" to { q -> dev.mascwa.pulse.jarvis.agent.RecallTool(jarvisMemory).run(q) },
        )
    }

    val approvalGate: dev.mascwa.pulse.jarvis.selfedit.ApprovalGate by lazy {
        dev.mascwa.pulse.jarvis.selfedit.ApprovalGate(
            selfEditStore,
            knowledgeStore,
            research = { topic -> dev.mascwa.pulse.jarvis.agent.WebSearchTool(http).run(topic) },
            commitCode = { action ->
                val result = selfCoder.commit(action)
                if (result.startsWith("Opened PR")) {
                    runCatching {
                        jarvisMemory.remember(
                            "Self-code change you shipped: ${action.payload["goal"].orEmpty().take(120)} — $result",
                            dev.mascwa.pulse.data.jarvis.db.NoteSource.INFERENCE,
                        )
                    }
                }
                result
            },
        )
    }

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
        val codeOn = runCatching { settingsRepository.current().jarvis.selfCodingEnabled }.getOrDefault(false)
        val codeTools = if (codeOn) listOf(
            dev.mascwa.pulse.jarvis.agent.SelfCodeReadTool(gitHubRepo),
            dev.mascwa.pulse.jarvis.agent.ProposeCodeChangeTool(
                selfCoder,
                autonomous = {
                    runCatching { settingsRepository.current().jarvis.autonomousSelfCoding }.getOrDefault(false)
                },
                autoApply = { action -> approvalGate.apply(action) },
            ),
        ) else emptyList()
        agentTools + (if (selfOn) selfEditTools else emptyList()) + authored + codeTools
    }

    val agentOrchestrator: dev.mascwa.pulse.jarvis.agent.AgentOrchestrator by lazy {
        dev.mascwa.pulse.jarvis.agent.AgentOrchestrator(inferenceEngine, jarvisMemory, agentToolsProvider, knowledgeStore)
    }

    val briefingBuilder: dev.mascwa.pulse.jarvis.BriefingBuilder by lazy {
        dev.mascwa.pulse.jarvis.BriefingBuilder(
            weatherRepository, newsRepository, marketsRepository,
            calendarObjectives, waypointStore, locationProvider, settingsRepository,
        )
    }

    val curiosityEngine: dev.mascwa.pulse.jarvis.curiosity.CuriosityEngine by lazy {
        dev.mascwa.pulse.jarvis.curiosity.CuriosityEngine(jarvisMemory, inferenceEngine, settingsRepository)
    }

    fun newCompassController(): CompassController = CompassController(appContext)

    fun newTelemetryController(): dev.mascwa.pulse.data.sensors.TelemetryController =
        dev.mascwa.pulse.data.sensors.TelemetryController(appContext)

    val notifier: Notifier by lazy { Notifier(appContext) }
    val notificationScheduler: NotificationScheduler by lazy { NotificationScheduler(appContext) }
}