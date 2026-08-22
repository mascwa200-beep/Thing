package dev.mascwa.pulse.data.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.FoodSearch
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.food.Food
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

private val Context.customFoodDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pulse_customfood")

/**
 * Foods somebody typed in themselves, kept so they only have to type them once.
 *
 * ## Why this is not optional
 *
 * Between the bundled USDA seed and Open Food Facts the app can find most things. Most is not all:
 * a local bakery's sourdough, a takeaway's portion, a supplement, anything home-made and anything
 * from a shop nobody has photographed. Before this, the only way to record one was QUICK ADD, which
 * writes a single log entry and remembers nothing — so eating the same thing on Tuesday meant
 * reading the same label and typing the same four numbers again.
 *
 * ⚠️ **Nothing is capped here, deliberately, and this is the same call [BodyStore] makes.** Every
 * derived store in this app evicts to bound its blob because what it holds can be recomputed. This
 * holds things a person wrote down; a few hundred over a decade is tens of kilobytes, and silently
 * dropping the oldest would quietly delete a food somebody's whole routine is logged against.
 *
 * ⚠️ **Ids are prefixed [ID_PREFIX] and minted here.** A `Food.id` is a barcode where there is one,
 * so an unprefixed identifier could collide with a real product and the log would then attribute a
 * home-made entry to a supermarket record. The prefix also means `entry.foodId.startsWith(...)` is
 * enough to tell where a logged entry came from without consulting this store.
 */
class CustomFoodStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    @Serializable
    private data class Stored(val foods: List<Food> = emptyList())

    private val prefsKey = stringPreferencesKey("custom_foods_json")
    private val mutex = Mutex()
    private var loaded: Stored? = null
    private var flushJob: Job? = null

    private val _foods = MutableStateFlow<List<Food>>(emptyList())

    /** Newest first, which is the order somebody wants to see their own list in. */
    val foods: StateFlow<List<Food>> = _foods.asStateFlow()

    private suspend fun loadLocked(): Stored = loaded ?: run {
        val raw = context.customFoodDataStore.data.first()[prefsKey]
        val s = raw
            ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            ?: Stored()
        loaded = s
        _foods.value = s.foods.reversed()
        s
    }

    /**
     * Read the list in, so [foods] describes the record rather than an empty one.
     *
     * ⚠️ Worth calling before anything collects [foods]. Every flow in this app starts empty and
     * fills on first read, and that has silently hidden whole categories of data on a cold screen
     * more than once.
     */
    suspend fun load() {
        mutex.withLock { loadLocked() }
    }

    // ------------------------------------------------------------------------------------ writing

    /**
     * Remember a food.
     *
     * @param per100g the nutrition per one hundred grams — the unit every source in this app is
     *   normalised to at its parser. A caller holding label figures for some other weight converts
     *   through `FoodPortion.per100gFrom`, which refuses when the weight is unknown rather than
     *   inventing a density.
     * @return the stored food, so the caller can log a portion of it straight away.
     */
    suspend fun save(
        name: String,
        per100g: NutritionDay.Nutrients,
        brand: String = "",
        servingGrams: Double? = null,
        servingLabel: String = "",
    ): Food {
        val food = Food.of(
            id = ID_PREFIX + UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Unnamed food" },
            per100g = per100g,
            brand = brand.trim(),
            servingGrams = servingGrams?.takeIf { it.isFinite() && it > 0.0 },
            servingLabel = servingLabel.trim(),
            source = NutritionDay.Source.CUSTOM,
        )
        mutex.withLock {
            val s = loadLocked()
            val next = s.copy(foods = s.foods + food)
            loaded = next
            _foods.value = next.foods.reversed()
        }
        scheduleFlush()
        return food
    }

    suspend fun remove(id: String) {
        mutex.withLock {
            val s = loadLocked()
            if (s.foods.none { it.id == id }) return@withLock
            val next = s.copy(foods = s.foods.filterNot { it.id == id })
            loaded = next
            _foods.value = next.foods.reversed()
        }
        scheduleFlush()
    }

    // ------------------------------------------------------------------------------------ reading

    /**
     * The saved foods that could answer [query], best first.
     *
     * ⚠️ Ranked by the SAME [FoodSearch.score] the bundled seed goes through, rather than a
     * substring test. Two rankings over one search box would put a weak match above a strong one
     * whenever the two disagreed, and the reader would have no way to tell why.
     */
    suspend fun search(query: String, limit: Int = SEARCH_LIMIT): List<Food> {
        val all = mutex.withLock { loadLocked() }.foods
        if (all.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        return FoodSearch
            .rank(all.asSequence().map { FoodSearch.Entry(it.id, it.name, it.brand) }, query, limit)
            .mapNotNull { byId[it.entry.id] }
    }

    suspend fun byId(id: String): Food? =
        mutex.withLock { loadLocked() }.foods.firstOrNull { it.id == id }

    // ---------------------------------------------------------------------------------- lifecycle

    suspend fun clear() {
        // Cancel first, or a buffered write resurrects the list after this returns.
        flushJob?.cancel()
        mutex.withLock {
            loaded = Stored()
            _foods.value = emptyList()
        }
        runCatching { context.customFoodDataStore.edit { it.remove(prefsKey) } }
    }

    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { loaded } ?: return
        runCatching {
            context.customFoodDataStore.edit {
                it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot)
            }
        }
    }

    companion object {
        /** See the class note: a bare id could collide with a real barcode. */
        const val ID_PREFIX = "own:"

        /**
         * ⚠️ Small on purpose. This list is a handful of things somebody wrote down, and it is put
         * AHEAD of the database in the merged results — a cap is what stops a long personal list
         * ever burying the thirteen thousand foods behind it.
         */
        const val SEARCH_LIMIT = 8

        private const val FLUSH_DELAY_MS = 2_000L
    }
}
