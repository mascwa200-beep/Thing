package dev.mascwa.nutrition.data

import android.content.Context
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
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
import dev.mascwa.pulse.data.health.ProgressPhotoStore
import dev.mascwa.pulse.data.health.RecipeStore
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

    val settings: HealthSettingsStore by lazy { HealthSettingsStore(appContext, json) }

    /**
     * This app keeping itself current.
     *
     * ⚠️ Lazy like everything else here, so a launch that never opens the update card never
     * constructs it — but the activity DOES ask on every foreground, so in practice it is built
     * early. That is deliberate: an updater nobody has to remember is the point.
     */
    val updates: NutritionUpdates by lazy { NutritionUpdates(appContext, http, settings) }

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
        FoodDatabase.open(appContext)?.let { OfflineFoodStore(it) }
    }

    val bodyStore: BodyStore by lazy { BodyStore(appContext, json) }
    val foodLogStore: FoodLogStore by lazy { FoodLogStore(appContext, json) }
    val customFoodStore: CustomFoodStore by lazy { CustomFoodStore(appContext, json) }
    val recipeStore: RecipeStore by lazy { RecipeStore(appContext, json) }
    val progressPhotoStore: ProgressPhotoStore by lazy { ProgressPhotoStore(appContext, json) }
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
            foodRepository = foodRepository,
            healthExporter = exporter,
            healthImporter = importer,
            healthSettings = settings.settings,
            updateHealth = { block -> settings.update(block) },
            isOnline = { online() },
            mealPhotoReader = null,
        )
    }
}
