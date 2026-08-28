package dev.mascwa.pulse.di

import android.content.Context
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.data.economy.EconomyRepository
import dev.mascwa.pulse.data.economy.WorldBankClient
import dev.mascwa.pulse.data.fuel.FuelRepository
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.emergency.EmergencyService
import dev.mascwa.pulse.data.news.NewsRepository
import dev.mascwa.pulse.data.orbital.LaunchRepository
import dev.mascwa.pulse.data.orbital.OrbitalRepository
import dev.mascwa.pulse.data.orbital.TleRepository
import dev.mascwa.pulse.data.places.OverpassRepository
import dev.mascwa.pulse.data.sensors.CompassController
import dev.mascwa.pulse.data.sensors.SurvivalTools
import dev.mascwa.pulse.data.settings.FuelPreferences
import dev.mascwa.pulse.data.settings.MarketPreferences
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.settings.WeatherPreferences
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

    /**
     * Screen-open requests from the Computer's tools. PulseApp collects it and navigates through
     * its one openApp idiom; the tool side emits a route and knows nothing about navigation.
     *
     * ⚠️ replay = 0 ON PURPOSE: a navigation request is an imperative, not state — replaying the
     * last route to every future collector would re-navigate on each Activity recreation. The
     * buffer only absorbs a slow collector; with NO collector an emit is dropped, which is why
     * the tool checks subscriptionCount and answers honestly instead of claiming "Opening…"
     * into the void.
     */
    val navigationBus = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * Whether the Activity is between onStart and onStop. The navigation bus's collector is a
     * composition-lifetime LaunchedEffect, which survives a STOPPED Activity — so subscriptionCount
     * alone reads "someone is listening" while the screen is off, and a spoken "open the radar"
     * would navigate an invisible NavController and claim success. This flag is the visible half.
     */
    val appForeground = kotlinx.coroutines.flow.MutableStateFlow(false)

    val json: Json by lazy { HttpClient.defaultJson() }
    /**
     * The one HTTP client every feed shares.
     *
     * ⚠️ The fan-out is scaled to the phone. Sixty-four concurrent calls on a 2 GB device is
     * sixty-four threads, sockets and read buffers — a low-memory kill, not a slow screen — so a
     * MINIMAL phone gets twenty-one. `durableBudget()` rather than the live one because a
     * `Dispatcher` is built once with the client and nothing here could re-tune it as the phone
     * cooled; see the reasoning at the call it makes.
     */
    val http: HttpClient by lazy {
        HttpClient.create(json, appContext.cacheDir, parallelism = deviceProbe.durableBudget().parallelism)
    }
    // ⚠️ `filesDir`, not the `Context` itself. The cache is shared with the desktop companion now,
    // and reading a directory was the only thing it ever wanted a `Context` for.
    val diskCache: DiskCache by lazy { DiskCache(appContext.filesDir, json) }

    /**
     * Every rebuildable cache, so Settings can report and clear all of them rather than one.
     *
     * ⚠️ The image loader and the HTTP client are passed as lambdas, not instances. Building this
     * eagerly forces both, and [imageLoader] in particular has no business existing in a process
     * that never draws a picture — a background worker among them.
     */
    val appCaches: dev.mascwa.pulse.data.cache.AppCaches by lazy {
        dev.mascwa.pulse.data.cache.AppCaches(appContext, diskCache, { imageLoader }, { http })
    }
    /** Checks the CI `latest` GitHub release for a newer build and downloads the APK (in-app updater). */
    val updateRepository: dev.mascwa.pulse.data.update.UpdateRepository by lazy {
        dev.mascwa.pulse.data.update.UpdateRepository(
            appContext,
            http,
            tag = dev.mascwa.pulse.data.update.UpdateRepository.LCARS_TAG,
            workflow = dev.mascwa.pulse.data.update.UpdateRepository.LCARS_WORKFLOW,
            currentVersionCode = dev.mascwa.pulse.BuildConfig.VERSION_CODE,
            currentVersionName = dev.mascwa.pulse.BuildConfig.VERSION_NAME,
            token = { settingsRepository.current().jarvis.githubToken },
        )
    }

    /**
     * The same checker pointed at the standalone nutrition app's own release, so this app can put
     * the companion on the phone in the first place — there is otherwise no way to obtain it.
     *
     * ⚠️ **`currentVersionCode = 0` is a statement, not a placeholder**: it treats every published
     * build as newer than nothing and offers the newest, which is what the manual control wants —
     * somebody who taps GET THE NUTRITION APP is asking for the app, and installing over an equal
     * or older build is harmless because the platform refuses a downgrade itself.
     *
     * ⚠️ **It is NOT because the version cannot be read, which this note used to claim.** This app
     * holds `QUERY_ALL_PACKAGES`, so `getPackageInfo` answers for the companion perfectly well —
     * and that wrong sentence is exactly what made keeping the companion updated automatically look
     * impossible. `RefreshWorker.updateCompanion` reads the installed version and compares against
     * it there, which is why this instance can stay deliberately permissive without that pass ever
     * re-installing a build the phone already has.
     */
    val nutritionUpdateRepository: dev.mascwa.pulse.data.update.UpdateRepository by lazy {
        dev.mascwa.pulse.data.update.UpdateRepository(
            appContext,
            http,
            tag = dev.mascwa.pulse.data.update.UpdateRepository.NUTRITION_TAG,
            workflow = dev.mascwa.pulse.data.update.UpdateRepository.NUTRITION_WORKFLOW,
            currentVersionCode = 0,
            currentVersionName = "",
            token = { settingsRepository.current().jarvis.githubToken },
        )
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
     * What this phone can actually be asked to do — RAM, heap class, cores, thermal state.
     *
     * ⚠️ Held here rather than constructed per call site, and that is not tidiness. The reader keeps
     * a high-water mark of the core count, because `availableProcessors()` reports cores that are
     * ONLINE and a big.LITTLE governor parks them when idle: a fresh instance takes one reading and
     * can class an eight-core flagship as a two-core phone. One instance per process accumulates.
     *
     * Placed above [imageLoader] deliberately — the cache sizes below become tier-scaled, and a
     * `by lazy` that referenced a member declared later would be an initialisation-order trap.
     */
    val deviceProbe: dev.mascwa.pulse.device.DeviceProbeReader by lazy {
        dev.mascwa.pulse.device.DeviceProbeReader(appContext)
    }

    /**
     * Bounded Coil image loader so thumbnail-heavy screens (news/markets/images/social) can't grow
     * the heap without limit — a key part of stopping the OS low-memory kills. Installed as the app's
     * singleton loader via [PulseApplication.newImageLoader], so every `AsyncImage` uses it.
     */
    val imageLoader: coil.ImageLoader by lazy {
        coil.ImageLoader.Builder(appContext)
            // Decode bundled .svg survival diagrams (crisp at any size) alongside the default raster fetchers.
            .components {
                add(coil.decode.SvgDecoder.Factory())
                // Bound every decode to what this phone can afford, re-read per request through the
                // cached accessor so a phone that gets hot decodes smaller from the next image on.
                // See DecodeCapInterceptor: the case it exists for is a dimension the layout left
                // open, which Coil's own size resolver cannot bound.
                add(dev.mascwa.pulse.device.DecodeCapInterceptor { deviceProbe.budgetCached().imageDecodePx })
            }
            // ⚠️ The share is the DURABLE budget — hardware only, thermal excluded. The cache is
            // sized once and kept for the life of the process, so folding a momentary reading into
            // it would leave a phone that happened to be warm at launch holding a small cache all
            // day. Pressure arriving later is already covered: onTrimMemory clears this.
            //
            // 0.06 was measured, and it is what this returns at every tier down to MODEST; a LEAN
            // phone gets 0.04 and a MINIMAL one 0.03, which on a 2 GB phone is the difference
            // between a thumbnail cache and a low-memory kill.
            .memoryCache {
                coil.memory.MemoryCache.Builder(appContext)
                    .maxSizePercent(deviceProbe.durableBudget().imageCacheShare)
                    .build()
            }
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

    /** What the Oracle has learned about which of its own rules you actually act on. */
    val oracleLearningStore: dev.mascwa.pulse.data.oracle.OracleLearningStore by lazy {
        dev.mascwa.pulse.data.oracle.OracleLearningStore(appContext, json)
    }
    /** Real calendar (on-device only) — feeds ORACLE's calendar-aware signal. */
    val calendarRepository: dev.mascwa.pulse.data.calendar.CalendarRepository by lazy {
        dev.mascwa.pulse.data.calendar.CalendarRepository(appContext)
    }

    /** Library / NOTES: the user's notes + saved information, sorted into named categories. */
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
    /** Persisted per-article LLM cache (a story is analyzed at most once ever — real API cost). */
    val newsAnalysisStore: dev.mascwa.pulse.data.news.NewsAnalysisStore by lazy {
        dev.mascwa.pulse.data.news.NewsAnalysisStore(appContext, json)
    }
    /** Cloud-gated, per-article "what's really going on" synthesis for the MARKET REACTION/MOOD copy. */
    val newsAnalysisEngine: dev.mascwa.pulse.data.news.NewsAnalysisEngine by lazy {
        dev.mascwa.pulse.data.news.NewsAnalysisEngine(inferenceEngine, settingsRepository)
    }
    /** Fetches a page and hands it to the DOM decimator. */
    val readerRepository: dev.mascwa.pulse.data.reader.ReaderRepository by lazy {
        dev.mascwa.pulse.data.reader.ReaderRepository(http)
    }
    val breakingCoverageRepository: dev.mascwa.pulse.data.breaking.BreakingCoverageRepository by lazy {
        dev.mascwa.pulse.data.breaking.BreakingCoverageRepository(newsRepository, diskCache)
    }
    /** The opt-in community TV catalogue. Nothing fetches through this unless the switch is on. */
    val liveCatalogRepository: dev.mascwa.pulse.data.live.LiveCatalogRepository by lazy {
        dev.mascwa.pulse.data.live.LiveCatalogRepository(http, diskCache)
    }
    val marketsRepository: MarketsRepository by lazy {
        MarketsRepository(http, diskCache) {
            val v = settingsRepository.current()
            MarketPreferences(v.currencyCode, v.watchlist, v.cryptoList)
        }
    }
    val economyRepository: EconomyRepository by lazy {
        EconomyRepository(worldBank, diskCache) { settingsRepository.current().countryCode }
    }
    val fuelRepository: FuelRepository by lazy {
        FuelRepository(http, marketsRepository, worldBank, diskCache) {
            val v = settingsRepository.current()
            FuelPreferences(v.countryCode, v.apiKeys.eia)
        }
    }
    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(http, diskCache) {
            val v = settingsRepository.current()
            WeatherPreferences(v.temperatureUnit, v.windUnit, v.precipUnit)
        }
    }
    val spaceWeatherRepository: SpaceWeatherRepository by lazy {
        SpaceWeatherRepository(http, diskCache)
    }
    val orbitalRepository: OrbitalRepository by lazy {
        OrbitalRepository(http, diskCache) { settingsRepository.current().apiKeys.nasaOrDemo }
    }
    val tleRepository: TleRepository by lazy { TleRepository(http, diskCache) }
    val launchRepository: LaunchRepository by lazy { LaunchRepository(http, diskCache) }
    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }

    val connectivityObserver: dev.mascwa.pulse.core.connectivity.ConnectivityObserver by lazy {
        dev.mascwa.pulse.core.connectivity.ConnectivityObserver(appContext)
    }

    val overpassRepository: OverpassRepository by lazy { OverpassRepository(http, diskCache) }

    /** Road-snapped routing (free, keyless OSRM) for the NAV navigation path. */
    val routingRepository: dev.mascwa.pulse.data.places.RoutingRepository by lazy {
        dev.mascwa.pulse.data.places.RoutingRepository(http)
    }

    /** The breadcrumb trail behind you on the NAV map (on-device only, never transmitted). */
    val trackStore: dev.mascwa.pulse.data.nav.TrackStore by lazy {
        dev.mascwa.pulse.data.nav.TrackStore(appContext, json)
    }

    /** Ground height for a batch of coordinates — the NAV map's route elevation profile. */
    val elevationRepository: dev.mascwa.pulse.data.maps.ElevationRepository by lazy {
        dev.mascwa.pulse.data.maps.ElevationRepository(http)
    }

    /** Live precipitation-radar frames for the NAV map's rain overlay (keyless). */
    val rainViewerRepository: dev.mascwa.pulse.data.maps.RainViewerRepository by lazy {
        dev.mascwa.pulse.data.maps.RainViewerRepository(http)
    }

    /** Local / regional internet radio (Radio Browser community API; free, keyless). */
    val tuneInRepository: dev.mascwa.pulse.data.radio.TuneInRepository by lazy {
        dev.mascwa.pulse.data.radio.TuneInRepository(http)
    }
    val radioBrowserRepository: dev.mascwa.pulse.data.radio.RadioBrowserRepository by lazy {
        dev.mascwa.pulse.data.radio.RadioBrowserRepository(http)
    }

    /** LCARS music: Spotify Web API (OAuth PKCE, tokens in settings). */
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
    /**
     * Live official alerts for the emergency watch — uncached by design, unlike [safetyRepository].
     * A warning served from a ten-minute cache is a warning that can be ten minutes late.
     */
    val emergencyAlertRepository: dev.mascwa.pulse.data.safety.EmergencyAlertRepository by lazy {
        dev.mascwa.pulse.data.safety.EmergencyAlertRepository(http)
    }
    /**
     * The one place that asks the bundled library whether it has anything to say about a question.
     *
     * Shared by the voice service and the chat console: each having its own copy of "which guide,
     * which section, how much of it" is how two surfaces quietly start answering the same question
     * differently.
     */
    val libraryLookup: dev.mascwa.pulse.data.survival.LibraryLookup by lazy {
        dev.mascwa.pulse.data.survival.LibraryLookup(survivalContentRepository)
    }

    val survivalContentRepository: SurvivalContentRepository by lazy {
        SurvivalContentRepository(appContext, json, packStore)
    }

    /**
     * Installed expansion packs.
     *
     * Passed into the library repository rather than consulted by anything else: the merge belongs at
     * the one place that answers "what is in the library", so the reader, search, study and the
     * assistant's tools see one corpus without knowing packs exist.
     */
    val packStore: dev.mascwa.pulse.data.survival.PackStore by lazy {
        dev.mascwa.pulse.data.survival.PackStore(appContext, json)
    }

    /** Browsing and fetching published packs. Only the wire between the format, the archive and the store. */
    val packRepository: dev.mascwa.pulse.data.survival.PackRepository by lazy {
        dev.mascwa.pulse.data.survival.PackRepository(http, settingsRepository, packStore, appContext.cacheDir)
    }

    /**
     * The large optional payloads, fetching themselves on Wi-Fi rather than waiting to be pressed.
     *
     * ⚠️ A lazy, so a phone that never reaches an unmetered pass never constructs it — and, more to
     * the point, never touches [llamaEngine], whose own construction is what a device with no
     * interest in the interrogator should be spared.
     */
    val payloadProvisioner: dev.mascwa.pulse.data.provision.PayloadProvisioner by lazy {
        dev.mascwa.pulse.data.provision.PayloadProvisioner(
            appContext, packRepository, llamaEngine, usageRepository, deviceProbe,
        )
    }

    /**
     * What the reader is learning from the bundled library, and when they are next due to be asked.
     *
     * Separate from [libraryLookup]: that one answers a question, this one decides what to teach and
     * keeps the schedule that turns being told into learning.
     */
    val studyStore: dev.mascwa.pulse.data.study.StudyStore by lazy {
        dev.mascwa.pulse.data.study.StudyStore(appContext, json, survivalContentRepository)
    }
    /**
     * Weigh-ins and body measurements. The trend, the rate and their intervals are never stored — they
     * come from the pure [dev.mascwa.pulse.core.telemetry.BodyTrend], so nothing here can disagree with
     * what the screen draws.
     */
    val bodyStore: dev.mascwa.pulse.data.health.BodyStore by lazy {
        dev.mascwa.pulse.data.health.BodyStore(appContext, json)
    }

    /**
     * The food log — monthly shards plus a resident index of daily totals, because this is the one
     * health dataset that grows for ever and is written several times a day.
     */
    val foodLogStore: dev.mascwa.pulse.data.health.FoodLogStore by lazy {
        dev.mascwa.pulse.data.health.FoodLogStore(appContext, json)
    }

    /**
     * The whole health record as a zip of CSVs.
     *
     * ⚠️ Lazy, like every store here, and for a sharper reason than usual: it exists to open every
     * shard at once, which is exactly what the log's sharding avoids. Nothing constructs it until
     * somebody asks for their data by name.
     */
    val healthExporter: dev.mascwa.pulse.data.health.HealthExporter by lazy {
        dev.mascwa.pulse.data.health.HealthExporter(appContext, foodLogStore, bodyStore)
    }

    /** The other direction: reading a record back in. Lazy for the same reason as the exporter. */
    val healthImporter: dev.mascwa.pulse.data.health.HealthImporter by lazy {
        dev.mascwa.pulse.data.health.HealthImporter(appContext, foodLogStore, bodyStore)
    }

    /**
     * Health Connect, behind a capability check.
     *
     * ⚠️ Stateless and cheap to construct — every call re-reads whether a provider is there, because
     * Health Connect can be installed, updated or removed while this app is alive.
     */
    val healthConnect: dev.mascwa.pulse.data.health.HealthConnectBridge by lazy {
        dev.mascwa.pulse.data.health.HealthConnectBridge(appContext)
    }

    /**
     * Progress photographs — app-private, never in the camera roll, and in `filesDir` rather than
     * the cache so the OS cannot reclaim somebody's "before" without telling them.
     */
    val progressPhotoStore: dev.mascwa.pulse.data.health.ProgressPhotoStore by lazy {
        dev.mascwa.pulse.data.health.ProgressPhotoStore(appContext, json)
    }

    /** Dishes made more than once, so a bolognese is one entry rather than eleven. */
    val recipeStore: dev.mascwa.pulse.data.health.RecipeStore by lazy {
        dev.mascwa.pulse.data.health.RecipeStore(appContext, json)
    }

    /** A meal being assembled, kept on disk so a telephone call cannot lose it. */
    val plateStore: dev.mascwa.pulse.data.health.PlateStore by lazy {
        dev.mascwa.pulse.data.health.PlateStore(appContext, json)
    }

    /** What was lifted, and the personal bests that outlive the sessions they were set in. */
    val trainingStore: dev.mascwa.pulse.data.health.TrainingStore by lazy {
        dev.mascwa.pulse.data.health.TrainingStore(appContext, json)
    }

    /** Packaged food by barcode or name, from the keyless Open Food Facts community database. */
    val openFoodFacts: dev.mascwa.pulse.data.food.OpenFoodFactsRepository by lazy {
        dev.mascwa.pulse.data.food.OpenFoodFactsRepository(http, diskCache)
    }

    /**
     * The bundled barcode database — ~4.5M retail products, answerable with no network.
     *
     * ⚠️ **Lazy, and that is what keeps a 296 MB asset from costing anything on a phone that never
     * scans.** Room's `createFromAsset` unpacks the whole thing out of the APK the first time the
     * database is opened, so touching this eagerly would spend that on every cold start. Nothing
     * reaches it until a barcode or a food search actually needs it.
     *
     * ⚠️ Null on a build where the asset never arrived. It is fetched by CI rather than committed,
     * so a local developer build genuinely has none — see `FoodDatabase.open`.
     */
    val offlineFoodStore: dev.mascwa.pulse.data.health.OfflineFoodStore? by lazy {
        val opened = dev.mascwa.pulse.data.food.db.FoodDatabase.open(appContext)
        if (opened == null) {
            // ⚠️ Null used to mean one thing — the asset never arrived — and now means two. A phone
            // that refused to unpack for want of room is a different situation entirely, and this
            // report is the only place either becomes visible.
            crashReporter.reportNonFatal(
                "food.db.open",
                note = "The bundled barcode database did not open — every scan falls back to the " +
                    "network. Reason: " +
                    (dev.mascwa.pulse.data.food.db.FoodDatabase.lastOpenNote ?: "not stated"),
            )
        }
        opened
            ?.let {
                dev.mascwa.pulse.data.health.OfflineFoodStore(it) { op, t ->
                    // ⚠️ A query that THREW is not a query that found nothing, and until now both
                    // arrived on screen as "not in the database". The unpack above is where this
                    // fails in practice — a phone with no room left throws on the first query and
                    // every one after it — so without this the offline half is dead for good with
                    // the network path quietly covering for it.
                    crashReporter.reportNonFatal(
                        "food.db.$op",
                        t,
                        note = "The bundled barcode database could not answer a '$op' query. Every " +
                            "scan and offline search falls back to the network until this is fixed; " +
                            "the usual cause is no room left to unpack the database.",
                    )
                }
            }
    }

    /**
     * Everything findable: your own foods, then the bundled USDA seed, then ~4.5M bundled retail
     * products, then the network. The one place the sources are joined, so nothing downstream needs
     * to know there are four.
     */
    val foodRepository: dev.mascwa.pulse.data.health.FoodRepository by lazy {
        dev.mascwa.pulse.data.health.FoodRepository(
            appContext, openFoodFacts, customFoodStore, offlineFoodStore,
        )
    }

    /**
     * A photograph of a plate, read into proposals.
     *
     * ⚠️ The one part of the food half that cannot work offline — it needs a vision-capable cloud
     * model — and it says so rather than failing quietly. The model names the foods; every number
     * comes from [foodRepository]'s bundled records.
     */
    val mealPhotoReader: dev.mascwa.pulse.data.health.MealPhotoReader by lazy {
        dev.mascwa.pulse.data.health.MealPhotoReader(inferenceEngine, foodRepository)
    }

    /**
     * Foods somebody typed in themselves. Searched ahead of both databases, because a short list you
     * named yourself is more likely to be what you meant than one of thirteen thousand generic rows.
     */
    /**
     * The health view model's whole world, assembled from the members above.
     *
     * ⚠️ **This is the seam that lets one view model serve two applications.** Everything it names
     * lives in `:core:health` except the last three, which are exactly the things the two
     * applications do differently: this one keeps its health preferences as one section of a much
     * larger settings blob, watches connectivity through its own observer, and has a vision model to
     * read a photograph with. The standalone nutrition app has its own answers to the first two and
     * passes null for the third.
     */
    val healthDeps: dev.mascwa.pulse.data.health.HealthDeps by lazy {
        dev.mascwa.pulse.data.health.HealthDeps(
            foodLogStore = foodLogStore,
            bodyStore = bodyStore,
            progressPhotoStore = progressPhotoStore,
            healthConnect = healthConnect,
            customFoodStore = customFoodStore,
            recipeStore = recipeStore,
            plateStore = plateStore,
            trainingStore = trainingStore,
            foodRepository = foodRepository,
            healthExporter = healthExporter,
            healthImporter = healthImporter,
            readerRepository = readerRepository,
            healthSettings = settingsRepository.settings.map { it.health },
            // ⚠️ A read-modify-write, not a setter: `health` is one field of forty on AppSettings and
            // the other thirty-nine have to survive the write.
            updateHealth = { block -> settingsRepository.update { it.copy(health = block(it.health)) } },
            isOnline = { connectivityObserver.isOnline.value },
            mealPhotoReader = mealPhotoReader,
            crumb = dev.mascwa.pulse.crash.Breadcrumbs::drop,
        )
    }

    val customFoodStore: dev.mascwa.pulse.data.health.CustomFoodStore by lazy {
        dev.mascwa.pulse.data.health.CustomFoodStore(appContext, json)
    }

    val emergencyService: EmergencyService by lazy { EmergencyService(appContext) }
    val survivalTools: SurvivalTools by lazy { SurvivalTools(appContext) }
    val socialRepository: dev.mascwa.pulse.data.social.SocialRepository by lazy {
        dev.mascwa.pulse.data.social.SocialRepository(
            http,
            diskCache,
            lemmyInstance = { settingsRepository.current().lemmyInstance },
            mastodonInstance = { settingsRepository.current().mastodonInstance },
        )
    }
    val radarRepository: dev.mascwa.pulse.data.radar.RadarRepository by lazy {
        dev.mascwa.pulse.data.radar.RadarRepository(http, diskCache, tleRepository)
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
    val deviceContextProvider: dev.mascwa.pulse.core.device.DeviceContextProvider by lazy {
        dev.mascwa.pulse.core.device.DeviceContextProvider(appContext)
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
    /**
     * The chosen TTS voice, kept fresh by [observeVoicePreference].
     *
     * A plain field rather than a settings read because the engine selects its voice inside the
     * platform's init callback, which is not a coroutine and cannot suspend — and
     * `SettingsRepository.current()` is suspend. Blank until the first emission arrives, which the
     * engine handles by auto-selecting and then correcting itself on the first emission.
     */
    @Volatile private var voicePreference: String = ""

    private val ttsLazy = lazy {
        dev.mascwa.pulse.jarvis.voice.TextToSpeechEngine(appContext, preferredVoice = { voicePreference })
    }

    /** On-device text-to-speech so the computer can speak replies (AOSP, no Play Services). */
    val textToSpeech: dev.mascwa.pulse.jarvis.voice.TextToSpeechEngine by ttsLazy

    /**
     * Track the voice choice from the application scope.
     *
     * Deliberately does **not** touch [textToSpeech] unless it already exists: binding a TTS service
     * costs about a second and most launches never speak, so the engine stays lazy and only an engine
     * that is already up gets told to re-select.
     */
    fun observeVoicePreference(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            settingsRepository.settings
                .map { it.jarvis.voiceName }
                .distinctUntilChanged()
                .collect { name ->
                    voicePreference = name
                    if (ttsLazy.isInitialized()) runCatching { textToSpeech.useVoice(name) }
                }
        }
    }
    /** Offline on-device speech recognition (Vosk) for tap-to-talk and the wake word. */
    val voskSpeech: dev.mascwa.pulse.jarvis.voice.VoskSpeech by lazy {
        dev.mascwa.pulse.jarvis.voice.VoskSpeech(appContext)
    }

    // ---- Sensorium: ambient environment sensing (classify-then-discard; labels only) ----

    /**
     * The Sensorium's ears — one YAMNet mic sip at a time; skips while the console holds the mic or
     * the computer is talking.
     *
     * ⚠️ The second condition is not about contention, it is about **truthfulness**: the microphone
     * and the speaker are on the same phone, so a sip taken while a reply is being spoken hears the
     * computer and labels it `speech`, which distils to "there are voices around you". That would
     * feed the scene read, the ORACLE rules built on it, and — worst of all — the *learned* nightly
     * baseline, teaching the Sensorium that 3 a.m. is normally noisy because that is when it answered
     * a question. Yielding costs almost nothing; speech is a tiny fraction of the day.
     *
     * [ttsLazy] is checked rather than [textToSpeech] read, for the reason given on
     * [observeVoicePreference]: binding a TTS engine costs about a second, and an engine that has
     * never been built cannot be speaking.
     */
    val ambientAudioSampler: dev.mascwa.pulse.data.sensing.AmbientAudioSampler by lazy {
        dev.mascwa.pulse.data.sensing.AmbientAudioSampler(
            appContext,
            http,
            micBusy = {
                voskSpeech.consoleActive.value ||
                    (ttsLazy.isInitialized() && textToSpeech.isSpeaking)
            },
        )
    }

    /** The Sensorium's eyes — one headless back-camera EfficientNet burst at a time. */
    val ambientCameraSampler: dev.mascwa.pulse.data.sensing.AmbientCameraSampler by lazy {
        dev.mascwa.pulse.data.sensing.AmbientCameraSampler(appContext, http)
    }

    /** The Sensorium's continuous type-free senses (motion EWMA, light, barometer trend, magnetics,
     *  proximity) + on-demand WiFi/BLE density bursts. */
    val sensorFusion: dev.mascwa.pulse.data.sensing.SensorFusionController by lazy {
        dev.mascwa.pulse.data.sensing.SensorFusionController(appContext)
    }

    /**
     * The acoustic interrogator's rolling transcript.
     *
     * ⚠️ Its own Room database, not a table in the shared one — see [TranscriptDatabase] for the two
     * reasons, both load-bearing: bumping the shared database's version destroys the user's ingested
     * knowledge docs, and a one-tap purge of a separate file removes the bytes rather than leaving
     * them in freed SQLite pages.
     *
     * `by lazy`, so nothing is created until the interrogator is actually switched on: a user who
     * never enables it never has a transcript database on disk at all.
     */
    val transcriptStore: dev.mascwa.pulse.data.interrogator.TranscriptStore by lazy {
        dev.mascwa.pulse.data.interrogator.TranscriptStore(appContext)
    }

    /** Offline transcription. `by lazy` for the same reason as above: no model is fetched until used. */
    val whisperEngine: dev.mascwa.pulse.data.interrogator.WhisperEngine by lazy {
        dev.mascwa.pulse.data.interrogator.WhisperEngine(appContext, http)
    }

    /**
     * The interrogator's adjudicator.
     *
     * ⚠️ **A LOCAL ENGINE, PINNED, AND NEVER [inferenceEngine].** That router prefers the cloud
     * whenever an API key is set, so wiring the interrogator through it would ship ambient
     * conversation — other people's conversation — to a third party the moment a key exists. The
     * whole feature's privacy rests on this one line staying as it is.
     */
    val llamaEngine: dev.mascwa.pulse.data.interrogator.LlamaEngine by lazy {
        dev.mascwa.pulse.data.interrogator.LlamaEngine(appContext, http)
    }

    /** Stages 1–6: transcribe, record, screen, reference, adjudicate, compose. */
    val interrogatorCascade: dev.mascwa.pulse.data.interrogator.InterrogatorCascade by lazy {
        dev.mascwa.pulse.data.interrogator.InterrogatorCascade(
            whisperEngine, transcriptStore, libraryLookup, llamaEngine,
        )
    }

    /** Learned normality + the 48 h event log (baseline must survive restarts or anomaly detection
     *  restarts amnesiac). */
    val sensoriumStore: dev.mascwa.pulse.data.sensing.SensoriumStore by lazy {
        dev.mascwa.pulse.data.sensing.SensoriumStore(appContext, json)
    }

    /** The Sensorium's conductor: fuses sampler output each heartbeat, learns the baseline, extracts
     *  events, dispatches alerts/memories. Driven by [dev.mascwa.pulse.data.sensing.SensoriumService]. */
    val sensoriumEngine: dev.mascwa.pulse.data.sensing.SensoriumEngine by lazy {
        dev.mascwa.pulse.data.sensing.SensoriumEngine(
            sensoriumStore, ambientAudioSampler, ambientCameraSampler, sensorFusion,
            memoryStream, notifier, settingsRepository,
        )
    }
    /** Android's on-device Google recognizer for the (more accurate) post-wake command; private,
     *  no network. Falls back to Vosk when on-device recognition isn't available on a device. */
    val deviceSpeech: dev.mascwa.pulse.jarvis.voice.DeviceSpeechRecognizer by lazy {
        dev.mascwa.pulse.jarvis.voice.DeviceSpeechRecognizer(appContext)
    }

    /**
     * App-wide crash reporter backing the global handler + the SYS crash console.
     *
     * ⚠️ The build identity is passed in rather than read inside: the reporter is shared with the
     * standalone nutrition application now, and each one has to name its own `BuildConfig`. Which
     * build a fault came from is the most load-bearing line in a report, so there is no default to
     * fall through to.
     */
    val crashReporter: dev.mascwa.pulse.crash.CrashReporter by lazy {
        dev.mascwa.pulse.crash.CrashReporter(
            appContext,
            appLabel = "LCARS",
            versionName = dev.mascwa.pulse.BuildConfig.VERSION_NAME,
            versionCode = dev.mascwa.pulse.BuildConfig.VERSION_CODE,
        )
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
    /**
     * The embedded CPython interpreter.
     *
     * ⚠️ Lazy for a reason that matters: starting Python extracts the standard library out of the
     * APK's assets on first run, so a user who never reaches anything Python-backed never pays that
     * cost and never has the unpacked copy on disk. Constructing this object does not start it —
     * `ensureStarted()` does, and only when something asks.
     */
    val pythonRuntime: dev.mascwa.pulse.data.python.PythonRuntime by lazy {
        dev.mascwa.pulse.data.python.PythonRuntime(appContext)
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
    /**
     * The layered search behind the `web` tool: the offline library, then Wikipedia, then the open
     * web when a key is set.
     *
     * A single instance rather than one per call site, so the three places that reach for `web`
     * (the tool registry, an authored Lua tool's `web` capability, and the approval gate's research
     * step) cannot end up searching differently from each other.
     */
    val webSearchRepository: dev.mascwa.pulse.data.search.WebSearchRepository by lazy {
        dev.mascwa.pulse.data.search.WebSearchRepository(http, settingsRepository, libraryLookup)
    }
    /** Page address → playable item, via the bundled yt-dlp. Lazy so Python never starts unasked. */
    val mediaExtractor: dev.mascwa.pulse.data.media.MediaExtractor by lazy {
        dev.mascwa.pulse.data.media.MediaExtractor(pythonRuntime)
    }
    /** The community skip database, behind the hash-prefix privacy endpoint. */
    val sponsorBlockRepository: dev.mascwa.pulse.data.media.SponsorBlockRepository by lazy {
        dev.mascwa.pulse.data.media.SponsorBlockRepository(http)
    }
    /** The hardware data harvester: held volume key → yt-dlp download into sandboxed storage. */
    val mediaHarvester: dev.mascwa.pulse.data.media.MediaHarvester by lazy {
        dev.mascwa.pulse.data.media.MediaHarvester(pythonRuntime, appContext)
    }
    /** Flat video listings — the browse half of the Theater; resolves nothing until a tap. */
    val mediaBrowser: dev.mascwa.pulse.data.media.MediaBrowser by lazy {
        dev.mascwa.pulse.data.media.MediaBrowser(pythonRuntime)
    }
    /** The CONTINUE WATCHING shelf's memory. On-device only; viewing history never leaves. */
    val viewingLedger: dev.mascwa.pulse.data.media.ViewingLedgerStore by lazy {
        dev.mascwa.pulse.data.media.ViewingLedgerStore(appContext)
    }

    /** Read-only, on-device tools J.A.R.V.I.S. can invoke (web/GitHub-read/device/memory). */
    val agentTools: List<dev.mascwa.pulse.jarvis.agent.JarvisTool> by lazy {
        listOf(
            dev.mascwa.pulse.jarvis.agent.OpenScreenTool(navigationBus, appForeground),
            dev.mascwa.pulse.jarvis.agent.WebSearchTool(webSearchRepository),
            dev.mascwa.pulse.jarvis.agent.WebFetchTool(http),
            dev.mascwa.pulse.jarvis.agent.DownloadTool(appContext, http),
            dev.mascwa.pulse.jarvis.agent.RepoReadTool(http, settingsRepository),
            dev.mascwa.pulse.jarvis.agent.RememberTool(jarvisMemory),
            dev.mascwa.pulse.jarvis.agent.RecallTool(jarvisMemory),
            dev.mascwa.pulse.jarvis.agent.HistoryTool(memoryStream),
            dev.mascwa.pulse.jarvis.agent.KnowledgeTool(knowledgeStore),
            dev.mascwa.pulse.jarvis.agent.ArchitectureTool(knowledgeStore, gitHubRepo),
            dev.mascwa.pulse.jarvis.agent.CiTool(gitHubRepo),
            dev.mascwa.pulse.jarvis.agent.HealthTool(foodLogStore, bodyStore, settingsRepository),
            dev.mascwa.pulse.jarvis.agent.DeviceTool(deviceContextProvider),
            dev.mascwa.pulse.jarvis.agent.UsageInsightsTool(usageRepository),
            dev.mascwa.pulse.jarvis.agent.ActivityLogTool(usageRepository),
            dev.mascwa.pulse.jarvis.agent.EnvironmentTool(sensoriumEngine, sensoriumStore),
            dev.mascwa.pulse.jarvis.agent.OracleTool(this, oracleLearningStore),
            dev.mascwa.pulse.jarvis.agent.ReflexTool(cerebellumStore),
            dev.mascwa.pulse.jarvis.agent.ProcedureTool(procedureStore),
            dev.mascwa.pulse.jarvis.agent.ProfileTool(profileStore),
            dev.mascwa.pulse.jarvis.agent.TaskTool(taskStore),
            dev.mascwa.pulse.jarvis.agent.NotesTool(notesStore),
            dev.mascwa.pulse.jarvis.agent.DiaryTool(diaryStore),
            dev.mascwa.pulse.jarvis.agent.InterestTool(interestStore),
            dev.mascwa.pulse.jarvis.agent.FindingTool(findingStore),
            dev.mascwa.pulse.jarvis.agent.WeatherTool(weatherRepository, locationProvider, settingsRepository),
            // The app's own world, which the console could previously only reach by searching the web
            // for what was already sitting in these repositories.
            dev.mascwa.pulse.jarvis.agent.LibraryTool(survivalContentRepository, studyStore),
            // Retrieval is not teaching. This one decides what to teach and holds the schedule.
            dev.mascwa.pulse.jarvis.agent.StudyTool(studyStore, profileStore, taskStore),
            // One question — "what do I know about X" — over every store at once, so answering it
            // does not mean guessing which one holds the answer and guessing again when wrong.
            dev.mascwa.pulse.jarvis.agent.DeviceSearchTool(this),
            dev.mascwa.pulse.jarvis.agent.MarketsTool(marketsRepository),
            dev.mascwa.pulse.jarvis.agent.NewsTool(newsRepository),
            dev.mascwa.pulse.jarvis.agent.DayTool(this, settingsRepository),
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
            dev.mascwa.pulse.jarvis.agent.PlayMediaTool(
                appContext, mediaExtractor, sponsorBlockRepository, settingsRepository, mediaBrowser,
            ),
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
            "web" to { q -> dev.mascwa.pulse.jarvis.agent.WebSearchTool(webSearchRepository).run(q) },
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
            research = { topic -> dev.mascwa.pulse.jarvis.agent.WebSearchTool(webSearchRepository).run(topic) },
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
    val notificationScheduler: NotificationScheduler by lazy {
        NotificationScheduler(appContext, deviceProbe)
    }

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
