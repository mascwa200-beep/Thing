package dev.mascwa.nutrition.data

import android.content.Context
import dev.mascwa.nutrition.BuildConfig
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.crash.Breadcrumbs
import dev.mascwa.pulse.crash.CrashReporter
import dev.mascwa.pulse.crash.CrashUploader
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.data.reader.ReaderRepository
import dev.mascwa.pulse.data.update.SelfUpdate
import dev.mascwa.pulse.data.update.UpdateRepository
import dev.mascwa.pulse.device.DeviceProbeReader
import dev.mascwa.pulse.data.food.OpenFoodFactsRepository
import dev.mascwa.pulse.data.food.db.FoodDatabase
import dev.mascwa.pulse.data.health.BodyStore
import dev.mascwa.pulse.data.health.CustomFoodStore
import dev.mascwa.pulse.data.health.FoodLogStore
import dev.mascwa.pulse.data.health.FoodRepository
import dev.mascwa.pulse.data.health.HealthConnectBridge
import dev.mascwa.pulse.data.health.HealthDeps
import dev.mascwa.pulse.data.health.HealthExporter
import dev.mascwa.pulse.data.health.HealthImporter
import dev.mascwa.pulse.data.health.OfflineFoodStore
import dev.mascwa.pulse.data.health.PlateStore
import dev.mascwa.pulse.data.health.ProgressPhotoStore
import dev.mascwa.pulse.data.health.RecipeStore
import dev.mascwa.pulse.data.health.TrainingStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Everything this application is made of, built once and lazily.
 *
 * ⚠️ **Deliberately tiny, and the comparison is the point.** The LCARS container wires an inference
 * engine, a speech recogniser, a device-policy controller, a native audio cascade and forty
 * repositories. This one holds seven stores, an HTTP client and a food database, because that is all
 * a food and body log needs. Every line here had to justify itself against "does a nutrition tracker
 * require this".
 *
 * ⚠️ Lazy throughout, so nothing is constructed until something asks. That matters most for
 * [offline]: opening the bundled database unpacks a third of a gigabyte out of the assets on a first
 * run, and doing it during `Application.onCreate` would stall the launch.
 */
class NutritionContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * ⚠️ `ignoreUnknownKeys` because these blobs outlive the code that wrote them: a record saved by
     * a newer build and read by an older one must not throw, it must drop what it does not
     * understand. `coerceInputValues` for the same reason one tier down — a field whose enum value
     * has been removed falls back to the default rather than failing the whole decode.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * What this phone can actually be asked to do. The same reader the LCARS application uses, from
     * `:core:update` — one definition rather than a second copy in each app.
     *
     * ⚠️ One instance per process, because the reader keeps a high-water mark of the core count:
     * `availableProcessors()` reports only cores that are ONLINE, and a fresh instance reading once
     * during an idle moment can class an eight-core phone as a two-core one.
     */
    val deviceProbe: DeviceProbeReader by lazy { DeviceProbeReader(appContext) }

    val settings: HealthSettingsStore by lazy { HealthSettingsStore(appContext, json) }

    /**
     * Faults recorded on this phone.
     *
     * ⚠️ The build identity is passed in rather than read inside: the reporter is shared with the
     * LCARS application, so each names its own `BuildConfig`. Which build a fault came from is the
     * most load-bearing line in a report — a fixed bug and a live one look identical without it —
     * so there is no default to fall through to.
     */
    val crashReporter: CrashReporter by lazy {
        CrashReporter(
            appContext,
            appLabel = "Nutrition",
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
    }

    /**
     * Faults leaving this phone, so a failure here can be read off the repository.
     *
     * ⚠️ **The only credential this application holds is the update token**, so that is the whole of
     * what the scrubber is given to match by exact value — on top of the shape patterns it applies
     * regardless. If a second secret is ever added, it belongs in that list on the same commit.
     */
    val crashUploader: CrashUploader by lazy {
        CrashUploader(
            appContext,
            reporter = crashReporter,
            stream = "nutrition",
            appLabel = "Nutrition",
            buildLabel = "${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
            token = { settings.currentUpdateToken() },
            autoSendEnabled = { settings.currentAutoSendReports() },
            secrets = { listOfNotNull(settings.currentUpdateToken()) },
        )
    }

    /**
     * This app keeping itself current.
     *
     * ⚠️ Lazy like everything else here, so a launch that never opens the update card never
     * constructs it — but the activity DOES ask on every foreground, so in practice it is built
     * early. That is deliberate: an updater nobody has to remember is the point.
     */
    val updates: SelfUpdate by lazy {
        SelfUpdate(
            appContext,
            UpdateRepository(
                appContext,
                http,
                tag = UpdateRepository.NUTRITION_TAG,
                workflow = UpdateRepository.NUTRITION_WORKFLOW,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                token = { settings.currentUpdateToken() },
            ),
            saveToken = { settings.setUpdateToken(it) },
            pendingInstall = { settings.pendingInstall() },
            setPendingInstall = { settings.setPendingInstall(it) },
            // ⚠️ On a phone that also carries LCARS, none of this app's own updater is the route
            // that runs: that application is a device owner, holds a token already, and reinstalls
            // this one whenever a newer build is published. Naming it here is what lets the card say
            // so instead of insisting on a token nobody needs today.
            companionPackage = UpdateRepository.LCARS_PACKAGE,
        )
    }

    private val http: HttpClient by lazy {
        HttpClient(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build(),
            json,
        )
    }

    private val cache: DiskCache by lazy { DiskCache(appContext.cacheDir, json) }

    /**
     * Whether this phone has a usable network right now.
     *
     * ⚠️ Defensive to `false` rather than `true`. A thrown or unanswerable query means we do not
     * know, and claiming a network we cannot confirm turns "you are offline" into a lookup that
     * hangs and then fails with a less useful message.
     */
    private fun online(): Boolean = runCatching {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /**
     * The bundled barcode database, or null.
     *
     * ⚠️ Null is a real state and the whole app has to work through it — a build where CI could not
     * fetch the database still installs and still logs food, it simply cannot recognise a barcode
     * without a network. [FoodRepository] takes it as nullable for exactly that reason.
     */
    private val offline: OfflineFoodStore? by lazy {
        val db = FoodDatabase.open(appContext)
        if (db == null) {
            // ⚠️ **The single most valuable non-fatal report this app can file.** A database that
            // will not open makes every barcode miss and every offline search return nothing, and
            // there is no screen anywhere that could tell you that is what happened — a scan that
            // finds no product and a scan that could not look identical. Nothing crashes, so
            // without this the app is simply "not working" with no evidence at all.
            crashReporter.reportNonFatal(
                "food.db.open",
                note = "The bundled barcode database did not open — every scan falls back to the " +
                    "network. Reason: " + (FoodDatabase.lastOpenNote ?: "not stated"),
            )
        }
        db?.let {
            OfflineFoodStore(it) { op, t ->
                // ⚠️ **The other half of the report above, and the half that actually fires on a
                // cheap phone.** `open` returning null is the asset never having shipped; THIS is
                // the asset shipping and the unpack failing — Room copies 424 MB out of the APK on
                // the first query, and the ordinary way that fails is a phone with no room left. It
                // throws from then on, every scan reported "not in the database", every offline
                // search returned nothing, and the network path covered for it well enough that
                // nobody would ever have found out.
                crashReporter.reportNonFatal(
                    "food.db.$op",
                    t,
                    note = "The bundled barcode database could not answer a '$op' query. Scanning " +
                        "and offline search fall back to the network until this is fixed; the usual " +
                        "cause is no room left on the phone to unpack the database.",
                )
            }
        }
    }

    private val lazyBody = lazy { BodyStore(appContext, json) }
    val bodyStore: BodyStore by lazyBody
    private val lazyFoodLog = lazy { FoodLogStore(appContext, json) }
    val foodLogStore: FoodLogStore by lazyFoodLog
    private val lazyCustomFood = lazy { CustomFoodStore(appContext, json) }
    val customFoodStore: CustomFoodStore by lazyCustomFood
    private val lazyRecipe = lazy { RecipeStore(appContext, json) }
    val recipeStore: RecipeStore by lazyRecipe
    private val lazyPlate = lazy { PlateStore(appContext, json) }
    val plateStore: PlateStore by lazyPlate
    private val lazyTraining = lazy { TrainingStore(appContext, json) }
    val trainingStore: TrainingStore by lazyTraining
    val progressPhotoStore: ProgressPhotoStore by lazy { ProgressPhotoStore(appContext, json) }

    /**
     * Write every pending edit to disk, now.
     *
     * ⚠️ Every store here debounces its write by a couple of seconds so a builder session that
     * touches ten ingredients writes once — which means an app swiped away, or a process torn down
     * to install an update, can be holding somebody's last few entries in memory only. This is
     * called from `onStop` BEFORE the installer is handed anything, for exactly that reason.
     *
     * ⚠️ `lazy` matters here: reading a store to flush it would CREATE it, so a launch that never
     * opened a screen would build every DataStore on the way out. Only the ones already
     * initialised are asked, which is why this checks rather than simply calling.
     */
    suspend fun flushAll() {
        flush("body", lazyBody) { bodyStore.flushNow() }
        flush("foodlog", lazyFoodLog) { foodLogStore.flushNow() }
        flush("customfood", lazyCustomFood) { customFoodStore.flushNow() }
        flush("recipe", lazyRecipe) { recipeStore.flushNow() }
        flush("plate", lazyPlate) { plateStore.flushNow() }
        flush("training", lazyTraining) { trainingStore.flushNow() }
    }

    /**
     * One store's write, and a record of it when it does not happen.
     *
     * ⚠️ **This is the worst place in the app for a swallowed failure and it swallowed six.** These
     * calls used to be bare `runCatching { }` with the result discarded, and the call site is
     * `onStop` — so a write that fails takes the day's logging with it at the one moment when no
     * screen exists to say so, and the very next thing `onStop` may do is commit an update that
     * tears the process down and the in-memory copy with it. Nothing crashes; the entries are
     * simply not there in the morning, with no evidence anywhere of what happened. This record is
     * the only thing that could ever explain it.
     *
     * ⚠️ **And until now nothing it wrapped could fail.** All six stores caught their own DataStore
     * edit and discarded the `Result`, so no exception could reach here and this reporter had never
     * once been able to fire. Each store now keeps its last write's outcome and `flushNow` rethrows
     * it; the debounced background flush still swallows, because an exception thrown there escapes
     * into a launched coroutine and takes the process with it.
     *
     * ⚠️ Still never throws. A failing flush must not stop the five stores behind it from writing,
     * and `reportNonFatal` is documented never to throw either — its own `write` is wrapped whole,
     * which matters because the likeliest cause of a failed flush is a disk that cannot take
     * another byte, and that would fail this write too.
     *
     * ⚠️ The tag carries the store name and nothing variable, so the per-tag rate limit means one
     * report per store per process rather than one per backgrounding. A store that fails every time
     * says so once; six failing stores are six distinct reports rather than one ambiguous line.
     */
    private inline fun flush(name: String, lazy: Lazy<*>, write: () -> Unit) {
        if (!lazy.isInitialized()) return
        runCatching(write).onFailure { failure ->
            crashReporter.reportNonFatal(
                "flush.$name",
                failure,
                note = "The $name store could not be written to disk. Anything recorded since the " +
                    "last successful write is lost.",
            )
        }
    }
    val healthConnect: HealthConnectBridge by lazy { HealthConnectBridge(appContext) }

    private val openFoodFacts: OpenFoodFactsRepository by lazy { OpenFoodFactsRepository(http, cache) }

    val foodRepository: FoodRepository by lazy {
        FoodRepository(appContext, openFoodFacts, customFoodStore, offline)
    }

    private val exporter: HealthExporter by lazy { HealthExporter(appContext, foodLogStore, bodyStore) }
    private val importer: HealthImporter by lazy { HealthImporter(appContext, foodLogStore, bodyStore) }

    /**
     * What the shared view model reads.
     *
     * ⚠️ **[HealthDeps.mealPhotoReader] is null and that is a decision, not an omission.** Reading a
     * photograph of a plate needs a vision model, which needs a cloud key and a network — the two
     * things this application is built to do without. The shared view model reports it as "no
     * vision", which is an honest state rather than a button that quietly does nothing.
     *
     * ⚠️ [HealthDeps.isOnline] is a real reading now, and the note it replaces was written when it
     * could not be: it said answering truthfully would cost ACCESS_NETWORK_STATE, "a permission this
     * app has so far not needed". The updater needs that permission anyway — it refuses to pull a
     * very large APK over a metered connection — so the honest answer became free, and a food search
     * can skip a round-trip it already knows will fail.
     *
     * ⚠️ It is a **snapshot, not a subscription**: this asks the connectivity service each time
     * rather than watching a callback. A stale answer here costs one wasted request, which is
     * exactly what the previous unconditional `true` cost on every single lookup.
     */
    val healthDeps: HealthDeps by lazy {
        HealthDeps(
            foodLogStore = foodLogStore,
            bodyStore = bodyStore,
            progressPhotoStore = progressPhotoStore,
            healthConnect = healthConnect,
            customFoodStore = customFoodStore,
            recipeStore = recipeStore,
            plateStore = plateStore,
            trainingStore = trainingStore,
            foodRepository = foodRepository,
            healthExporter = exporter,
            healthImporter = importer,
            readerRepository = ReaderRepository(http),
            healthSettings = settings.settings,
            updateHealth = { block -> settings.update(block) },
            isOnline = { online() },
            mealPhotoReader = null,
            crumb = Breadcrumbs::drop,
        )
    }
}
