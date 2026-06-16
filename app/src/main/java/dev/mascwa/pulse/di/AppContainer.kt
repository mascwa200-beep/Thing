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
        )
    }
    /** Bounded ReAct loop wiring the on-device model to [agentTools] + durable memory + knowledge. */
    val agentOrchestrator: dev.mascwa.pulse.jarvis.agent.AgentOrchestrator by lazy {
        dev.mascwa.pulse.jarvis.agent.AgentOrchestrator(inferenceEngine, jarvisMemory, agentTools, knowledgeStore)
    }

    /** Compass is stateful per-screen, so hand out a fresh controller each time. */
    fun newCompassController(): CompassController = CompassController(appContext)

    /** Telemetry is stateful per-screen (sensor lifecycle), so hand out fresh. */
    fun newTelemetryController(): dev.mascwa.pulse.data.sensors.TelemetryController =
        dev.mascwa.pulse.data.sensors.TelemetryController(appContext)

    val notifier: Notifier by lazy { Notifier(appContext) }
    val notificationScheduler: NotificationScheduler by lazy { NotificationScheduler(appContext) }
}
