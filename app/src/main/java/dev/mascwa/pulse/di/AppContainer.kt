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

    /** The application context, for the few ViewModels that need it (e.g. AppOps / settings intents). */
    val applicationContext: Context get() = appContext

    val json: Json by lazy { HttpClient.defaultJson() }
    val http: HttpClient by lazy { HttpClient.create(json, appContext.cacheDir) }
    val diskCache: DiskCache by lazy { DiskCache(appContext, json) }
    /** Checks the CI `latest` GitHub release for a newer build and downloads the APK (in-app updater). */
    val updateRepository: dev.mascwa.pulse.data.update.UpdateRepository by lazy {
        dev.mascwa.pulse.data.update.UpdateRepository(appContext, http, settingsRepository)
    }
    /** GitHub write client + self-coding brain (opt-in): J.A.R.V.I.S. drafts a change and opens a PR. */
    val gitHubRepo: dev.mascwa.pulse.data.selfcode.GitHubRepo by lazy {
        dev.mascwa.pulse.data.selfcode.GitHubRepo(settingsRepository)
    }
    val selfCoder: dev.mascwa.pulse.data.selfcode.SelfCoder by lazy {
        dev.mascwa.pulse.data.selfcode.SelfCoder(inferenceEngine, gitHubRepo, selfEditStore)
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
            // Decode bundled .svg survival diagrams (crisp at any size) alongside the default raster fetchers.
            .components { add(coil.decode.SvgDecoder.Factory()) }
            .memoryCache { coil.memory.MemoryCache.Builder(appContext).maxSizePercent(0.06).build() }
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

    /** On-device, aggregated feature-usage store (counts + hour-of-day; no content/PII). */
    val usageRepository: dev.mascwa.pulse.data.usage.UsageRepository by lazy {
        dev.mascwa.pulse.data.usage.UsageRepository(appContext, json)
    }

    /** Virtual cerebellum: procedural-skill learning + forward-model error correction (on-device). */
    val cerebellumStore: dev.mascwa.pulse.data.cerebellum.CerebellumStore by lazy {
        dev.mascwa.pulse.data.cerebellum.CerebellumStore(appContext, json)
    }
    /** Mnemosyne procedure library ("skills"): learned multi-step tool sequences, fed by agent runs. */
    val procedureStore: dev.mascwa.pulse.data.procedure.ProcedureStore by lazy {
        dev.mascwa.pulse.data.procedure.ProcedureStore(appContext, json)
    }

    /** Structured user profile: durable preferences / interests / projects, injected into context. */
    val profileStore: dev.mascwa.pulse.data.profile.ProfileStore by lazy {
        dev.mascwa.pulse.data.profile.ProfileStore(appContext, json)
    }

    /** Task board: the user's ongoing tasks / goals, with pending ones injected into context each turn. */
    val taskStore: dev.mascwa.pulse.data.tasks.TaskStore by lazy {
        dev.mascwa.pulse.data.tasks.TaskStore(appContext, json)
    }
    /** The S.P.E.C.I.A.L. game save (STAT-tab wasteland RPG) — character sheet + current encounter. */
    val specialGameStore: dev.mascwa.pulse.data.game.SpecialGameStore by lazy {
        dev.mascwa.pulse.data.game.SpecialGameStore(appContext, json)
    }

    /** The Pokémon-Go layer: real nearby shops → game locations + real-world travel tracking. */
    val gameWorldStore: dev.mascwa.pulse.data.game.GameWorldStore by lazy {
        dev.mascwa.pulse.data.game.GameWorldStore(appContext, json, overpassRepository)
    }

    /** Persists + tracks the personalised quest log (completion + rewards) for the life-sim. */
    val questStore: dev.mascwa.pulse.data.game.QuestStore by lazy {
        dev.mascwa.pulse.data.game.QuestStore(appContext, json)
    }

    /** The smart self-care check-in scheduler's state (last done/asked per check-in + last meal) — picks the
     *  next full-screen check-in via CareSchedule; synced from the auto-care sensed stream. */
    val careCheckinStore: dev.mascwa.pulse.data.game.CareCheckinStore by lazy {
        dev.mascwa.pulse.data.game.CareCheckinStore(
            appContext, json, specialGameStore, activityEvidenceStore, settingsRepository,
        )
    }

    /** Turns neglected survival needs into reversible Device-Owner phone locks (opt-in "neglect bites the
     *  phone"). Reconciled on app foreground + from RefreshWorker; releases everything when the toggle is off. */
    val phonePenaltyController: dev.mascwa.pulse.security.PhonePenaltyController by lazy {
        dev.mascwa.pulse.security.PhonePenaltyController(
            appContext, settingsRepository, dev.mascwa.pulse.security.DevicePolicyController(appContext), notifier,
        )
    }

    /** On-device ambient hearing (MediaPipe YAMNet) → the life-sim's perceived SceneContext. */
    val ambientPerceptionSampler: dev.mascwa.pulse.data.perception.AmbientPerceptionSampler by lazy {
        dev.mascwa.pulse.data.perception.AmbientPerceptionSampler(appContext, http)
    }

    /** On-device ambient seeing (MediaPipe EfficientNet-Lite via CameraX) → the life-sim's SceneContext. */
    val cameraPerceptionSampler: dev.mascwa.pulse.data.perception.CameraPerceptionSampler by lazy {
        dev.mascwa.pulse.data.perception.CameraPerceptionSampler(appContext, http)
    }

    /** Auto-care: when the camera/mic catch you drinking/eating/washing/brushing, restore the matching game
     *  need automatically (rate-limited, gated by ambient sensing). Started from PulseApplication's app scope. */
    val needAutoCare: dev.mascwa.pulse.data.perception.NeedAutoCare by lazy {
        dev.mascwa.pulse.data.perception.NeedAutoCare(
            activityEvidenceStore.evidenceFlow, specialGameStore, settingsRepository, notifier,
        )
    }

    /** Runs ActivitySensing over the mic + camera labels → a persisted history of real self-care (shower /
     *  meal / bathroom), which the habit check-in reads to catch a lie. On-device (text evidence only). */
    val activityEvidenceStore: dev.mascwa.pulse.data.perception.ActivityEvidenceStore by lazy {
        dev.mascwa.pulse.data.perception.ActivityEvidenceStore(
            appContext, json, ambientPerceptionSampler.soundLabels, cameraPerceptionSampler.sceneLabels,
        )
    }

    /** The habit check-in loop: asks "showered/ate/drank yet?", verifies the claim against the evidence
     *  history, and tops up the matching real-life need on a truthful yes (no lockout). */
    val habitStore: dev.mascwa.pulse.data.game.HabitStore by lazy {
        dev.mascwa.pulse.data.game.HabitStore(appContext, json, activityEvidenceStore, specialGameStore, settingsRepository)
    }

    /** Real calendar → the life-sim's agenda (upcoming events become wasteland objectives; on-device only). */
    val calendarRepository: dev.mascwa.pulse.data.calendar.CalendarRepository by lazy {
        dev.mascwa.pulse.data.calendar.CalendarRepository(appContext)
    }

    /** Library / NOTES: the user's notes + saved information, sorted into Fallout-style categories. */
    val notesStore: dev.mascwa.pulse.data.notes.NotesStore by lazy {
        dev.mascwa.pulse.data.notes.NotesStore(appContext, json)
    }

    /** DIARY: the user's dated personal journal (J.A.R.V.I.S. can journal here via the `diary` tool). */
    val diaryStore: dev.mascwa.pulse.data.diary.DiaryStore by lazy {
        dev.mascwa.pulse.data.diary.DiaryStore(appContext, json)
    }

    /** Standing INTERESTS: the owner's standing orders + J.A.R.V.I.S.'s own emergent curiosities to monitor. */
    val interestStore: dev.mascwa.pulse.data.interests.InterestStore by lazy {
        dev.mascwa.pulse.data.interests.InterestStore(appContext, json)
    }

    /** FINDINGS: what J.A.R.V.I.S. curates from his gathering + brings to the owner conversationally. */
    val findingStore: dev.mascwa.pulse.data.findings.FindingStore by lazy {
        dev.mascwa.pulse.data.findings.FindingStore(appContext, json)
    }

    /** Episodic memory stream: timestamped observations, recalled by recency·importance·relevance. */
    val memoryStream: dev.mascwa.pulse.data.memory.MemoryStreamStore by lazy {
        dev.mascwa.pulse.data.memory.MemoryStreamStore(appContext, json)
    }

    private val worldBank: WorldBankClient by lazy { WorldBankClient(http) }

    val newsRepository: NewsRepository by lazy {
        NewsRepository(http, diskCache, settingsRepository)
    }
    val breakingCoverageRepository: dev.mascwa.pulse.data.breaking.BreakingCoverageRepository by lazy {
        dev.mascwa.pulse.data.breaking.BreakingCoverageRepository(newsRepository, diskCache)
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

    /** Real OSM building footprints for the AR wasteland (geo-anchored wireframe buildings). */
    val buildingRepository: dev.mascwa.pulse.data.ar.BuildingRepository by lazy {
        dev.mascwa.pulse.data.ar.BuildingRepository(http)
    }

    /** Real DEM elevation for the AR wasteland floor (the invisible ground anchor). */
    val elevationRepository: dev.mascwa.pulse.data.ar.ElevationRepository by lazy {
        dev.mascwa.pulse.data.ar.ElevationRepository(http)
    }

    /** Road-snapped routing (free, keyless OSRM) for the NAV navigation path. */
    val routingRepository: dev.mascwa.pulse.data.places.RoutingRepository by lazy {
        dev.mascwa.pulse.data.places.RoutingRepository(http)
    }

    /** Local / regional internet radio (Radio Browser community API; free, keyless). */
    val tuneInRepository: dev.mascwa.pulse.data.radio.TuneInRepository by lazy {
        dev.mascwa.pulse.data.radio.TuneInRepository(http)
    }
    val radioBrowserRepository: dev.mascwa.pulse.data.radio.RadioBrowserRepository by lazy {
        dev.mascwa.pulse.data.radio.RadioBrowserRepository(http)
    }

    /** PIP-BOY music: Spotify Web API (OAuth PKCE, tokens in settings). */
    val spotifyRepository: dev.mascwa.pulse.data.spotify.SpotifyRepository by lazy {
        dev.mascwa.pulse.data.spotify.SpotifyRepository(settingsRepository)
    }

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
                            // Cap the reply length so credit-metered providers (OpenRouter) don't
                            // pre-authorize the model's full output (e.g. 64k) and 402 on a small balance.
                            maxTokens = j.maxTokens,
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
    /** Uploads scrubbed crash/debug reports to the repo's `debug-reports` branch (opt-in) for remote
     *  reading. Reuses the repo-scoped GitHub token; never touches main/dev or opens a PR. */
    /** Tamper-evident audit ledger (blackbox). Producers record into it; verify() re-checks the chain.
     *  The head is signed by a secure-element EC key so the chain's tip is non-repudiable. */
    val auditLedgerStore: dev.mascwa.pulse.data.blackbox.AuditLedgerStore by lazy {
        dev.mascwa.pulse.data.blackbox.AuditLedgerStore(
            appContext, json, dev.mascwa.pulse.security.KeystoreLedgerSigner(),
            dev.mascwa.pulse.data.blackbox.TsaClient(http),
        )
    }
    /** Runtime self-test for the blackbox ledger (chain · secure-element signature · encryption · TSA). */
    val ledgerSelfTest: dev.mascwa.pulse.data.blackbox.LedgerSelfTest by lazy {
        dev.mascwa.pulse.data.blackbox.LedgerSelfTest(
            dev.mascwa.pulse.security.KeystoreLedgerSigner(),
            dev.mascwa.pulse.data.blackbox.TsaClient(http),
        )
    }
    /** Stateless hardware key-attestation probe (StrongBox-backed). Read-only; used to record the device's
     *  security posture into the audit ledger when it changes. */
    val deviceAttestation: dev.mascwa.pulse.core.device.DeviceAttestation by lazy {
        dev.mascwa.pulse.core.device.DeviceAttestation()
    }
    val debugUploader: dev.mascwa.pulse.data.diagnostics.DebugUploader by lazy {
        dev.mascwa.pulse.data.diagnostics.DebugUploader(
            appContext, gitHubRepo, crashReporter, usageRepository, settingsRepository, auditLedgerStore,
        )
    }
    /** Read-only, on-device tools J.A.R.V.I.S. can invoke (web/GitHub-read/device/memory). */
    val agentTools: List<dev.mascwa.pulse.jarvis.agent.JarvisTool> by lazy {
        listOf(
            dev.mascwa.pulse.jarvis.agent.WebSearchTool(http),
            dev.mascwa.pulse.jarvis.agent.WebFetchTool(http),
            dev.mascwa.pulse.jarvis.agent.DownloadTool(appContext, http),
            dev.mascwa.pulse.jarvis.agent.RepoReadTool(http, settingsRepository),
            dev.mascwa.pulse.jarvis.agent.RememberTool(jarvisMemory),
            dev.mascwa.pulse.jarvis.agent.RecallTool(jarvisMemory),
            dev.mascwa.pulse.jarvis.agent.HistoryTool(memoryStream),
            dev.mascwa.pulse.jarvis.agent.KnowledgeTool(knowledgeStore),
            dev.mascwa.pulse.jarvis.agent.ArchitectureTool(knowledgeStore, gitHubRepo),
            dev.mascwa.pulse.jarvis.agent.CiTool(gitHubRepo),
            dev.mascwa.pulse.jarvis.agent.DeviceTool(deviceContextProvider),
            dev.mascwa.pulse.jarvis.agent.UsageInsightsTool(usageRepository),
            dev.mascwa.pulse.jarvis.agent.ActivityLogTool(usageRepository),
            dev.mascwa.pulse.jarvis.agent.ReflexTool(cerebellumStore),
            dev.mascwa.pulse.jarvis.agent.ProcedureTool(procedureStore),
            dev.mascwa.pulse.jarvis.agent.ProfileTool(profileStore),
            dev.mascwa.pulse.jarvis.agent.TaskTool(taskStore),
            dev.mascwa.pulse.jarvis.agent.SelfCareTool(specialGameStore, habitStore, activityEvidenceStore) { habit ->
                // Fire the check-in per the owner's switches: aggressive full-screen if enabled, else a soft
                // reminder. Master switch gates it (no self-care check-ins at all when off).
                val prefs = runCatching { settingsRepository.current().notifications }.getOrNull()
                if (prefs == null || prefs.selfCareCheckins) {
                    if (prefs?.aggressiveCheckin == true) notifier.notifyCheckin(habit)
                    else notifier.notifySurvival(91838, "Check-in · ${habit.label}", habit.question, urgent = false)
                }
            },
            dev.mascwa.pulse.jarvis.agent.NotesTool(notesStore),
            dev.mascwa.pulse.jarvis.agent.DiaryTool(diaryStore),
            dev.mascwa.pulse.jarvis.agent.InterestTool(interestStore),
            dev.mascwa.pulse.jarvis.agent.FindingTool(findingStore),
            dev.mascwa.pulse.jarvis.agent.WeatherTool(weatherRepository, locationProvider, settingsRepository),
            dev.mascwa.pulse.jarvis.agent.LocationTool(locationProvider),
            // Device-action tools — each opens the relevant app pre-filled (you confirm the final step).
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
            commitCode = { action ->
                val result = selfCoder.commit(action)
                // Record shipped self-changes to durable memory so J.A.R.V.I.S. can recall what it has
                // changed about itself ("what have you changed?").
                if (result.startsWith("Opened PR")) {
                    runCatching {
                        jarvisMemory.remember(
                            "Self-code change you shipped: ${action.payload["goal"].orEmpty().take(120)} — $result",
                            dev.mascwa.pulse.data.jarvis.db.NoteSource.INFERENCE,
                        )
                    }
                    // Record the human-gated self-mod in the tamper-evident ledger (goal + which PR; no secrets).
                    runCatching {
                        auditLedgerStore.record(
                            dev.mascwa.pulse.core.telemetry.AuditEventType.SELF_CODE,
                            "selfcode.apply",
                            "${action.payload["goal"].orEmpty().take(120)} — ${result.substringBefore(" — ").take(40)}",
                        )
                    }
                }
                result
            },
            recordSelfChange = { note ->
                runCatching {
                    jarvisMemory.remember(note, dev.mascwa.pulse.data.jarvis.db.NoteSource.INFERENCE)
                }
                Unit
            },
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
        // Self-coding tools (read your own source + propose a change) — only when self-coding is on.
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
            // Manage its OWN pull requests: list them with CI status + close dead/duplicate ones (scoped
            // to jarvis/ branches; never merges, never touches a human's PR).
            dev.mascwa.pulse.jarvis.agent.PullRequestTool(gitHubRepo),
        ) else emptyList()
        // Wrap every tool so each invocation lands in the activity log AND trains the cerebellum.
        val verboseLog = runCatching { settingsRepository.current().jarvis.verboseActivityLog }.getOrDefault(true)
        (agentTools + (if (selfOn) selfEditTools else emptyList()) + authored + codeTools)
            .map { dev.mascwa.pulse.jarvis.agent.LoggingTool(it, usageRepository, cerebellumStore, verboseLog) }
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
    /** Mnemosyne reflection: synthesise recent episodic observations into higher-level REFLECTION memories
     *  (cloud-gated + throttled). Driven periodically from RefreshWorker. */
    val reflectionEngine: dev.mascwa.pulse.jarvis.reflection.ReflectionEngine by lazy {
        dev.mascwa.pulse.jarvis.reflection.ReflectionEngine(memoryStream, inferenceEngine, settingsRepository)
    }

    /** Compass is stateful per-screen, so hand out a fresh controller each time. [cameraUpright] = AR mode. */
    fun newCompassController(cameraUpright: Boolean = false): CompassController =
        CompassController(appContext, cameraUpright)

    /** Telemetry is stateful per-screen (sensor lifecycle), so hand out fresh. */
    fun newTelemetryController(): dev.mascwa.pulse.data.sensors.TelemetryController =
        dev.mascwa.pulse.data.sensors.TelemetryController(appContext)

    val notifier: Notifier by lazy { Notifier(appContext) }
    val notificationScheduler: NotificationScheduler by lazy { NotificationScheduler(appContext) }

    // --- On-device security auditor (read-only, local-only defender's tool) ---
    val securityAuditor: dev.mascwa.pulse.data.security.SecurityAuditor by lazy {
        dev.mascwa.pulse.data.security.SecurityAuditor(appContext)
    }
    val securityAuditStore: dev.mascwa.pulse.data.security.SecurityAuditStore by lazy {
        dev.mascwa.pulse.data.security.SecurityAuditStore(appContext, json)
    }

    // --- Network security: Trusted Network Mode (Device-Owner-gated Wi-Fi control) ---
    val wifiPolicyController: dev.mascwa.pulse.security.WifiPolicyController by lazy {
        dev.mascwa.pulse.security.WifiPolicyController(appContext)
    }
    val trustedNetworkMonitor: dev.mascwa.pulse.security.TrustedNetworkMonitor by lazy {
        dev.mascwa.pulse.security.TrustedNetworkMonitor(
            appContext, settingsRepository, usageRepository, notifier, wifiPolicyController,
        )
    }
}
