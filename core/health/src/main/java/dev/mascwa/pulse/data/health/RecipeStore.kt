package dev.mascwa.pulse.data.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Micronutrients
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.core.telemetry.Recipes
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

/**
 * ⚠️ Its own DataStore name, not a second key in `pulse_food`.
 *
 * `preferencesDataStore` keeps one instance per file name per process and **throws** on a second
 * delegate for the same name — so sharing the log's store would mean living in its file, which is
 * the one file in this app that is sharded and rewritten several times a day. Recipes are a
 * few dozen small records that change rarely; they have no business being re-encoded with every
 * meal, and clearing one should not risk the other.
 */
private val Context.recipeDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_recipes")

/**
 * The dishes you make more than once, and the groups of foods you eat together.
 *
 * ⚠️ **Both, in one store, deliberately.** A saved meal is the same data as a recipe — a named list
 * of foods at weights — and differs only in what logging it means (see [Recipes.Kind]). A second
 * store would mean a second DataStore file, a second builder and a second food picker for a
 * difference of one branch at one call site. ⚠️ The file name stays `pulse_recipes`: it is
 * **identity**, and renaming it would orphan everything already saved.
 *
 * A single blob, unlike [FoodLogStore] beside it, and that is the ordinary case rather than the
 * exception: this holds tens of records that change when somebody edits a recipe, where the log
 * holds thousands that change at every meal. The sharding over there exists for a reason that does
 * not apply here.
 *
 * Mirrors the app's ProfileStore template — in-memory state is
 * authoritative, a [Mutex] serialises every read-modify-write, and the disk write is debounced so a
 * builder session that touches ten ingredients writes once. [flushNow] is called from
 * `MainActivity.onStop`, so a half-built recipe survives the app being swiped away.
 *
 * ⚠️ **[StoredRecipe] is a flat mirror of [Recipes.Recipe], for the module boundary rather than for
 * style.** `:core:telemetry` deliberately carries no kotlinx-serialization dependency, so a
 * `@Serializable` class cannot hold one of its types. `Food` and `SessionHours` already solve the
 * same problem the same way. Conversion happens here and nowhere else.
 */
class RecipeStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    @Serializable
    private data class StoredComponent(
        val foodId: String = "",
        val name: String = "",
        val grams: Double = 0.0,
        val kcal: Double = 0.0,
        val p: Double = 0.0,
        val f: Double = 0.0,
        val c: Double = 0.0,
        val fibre: Double = 0.0,
        val sugar: Double = 0.0,
        val satFat: Double = 0.0,
        val sodium: Double = 0.0,
        /**
         * The ingredient's vitamins and minerals per 100 g, keyed by [Micronutrients.Micro] NAME.
         *
         * ⚠️ Defaulted, so every recipe already on disk decodes unchanged — asserted by a test that
         * decodes a blob written before this field existed.
         *
         * ⚠️ Keyed by String rather than by the enum, for exactly the reason `Food.micros` is: an
         * enum-keyed serializer THROWS on a value it does not know, so renaming a micronutrient would
         * make somebody's whole recipe book undecodable. An unknown key here is simply dropped.
         */
        val micros: Map<String, Double> = emptyMap(),
        /**
         * The ingredient's TWENTY-NINE further nutrients per 100 g, keyed by `NutrientSet.Nutrient`
         * NAME.
         *
         * ⚠️ Same shape and same two reasons as [micros], one tier sparser. Defaulted so every
         * recipe already on disk decodes unchanged, and keyed by String because an enum-keyed
         * serializer throws on a name it does not know — renaming a nutrient would otherwise make
         * somebody's whole recipe book undecodable.
         */
        val extras: Map<String, Double> = emptyMap(),
    )

    @Serializable
    private data class StoredRecipe(
        val id: String = "",
        val name: String = "",
        val components: List<StoredComponent> = emptyList(),
        val cookedYieldG: Double? = null,
        val servings: Int = 1,
        val note: String = "",
        /** When it was last saved, so the list can lead with what you are actually cooking. */
        val savedAtMs: Long = 0L,
        /**
         * `RECIPE` or `MEAL` — see [Recipes.Kind].
         *
         * ⚠️ Defaulted, so every recipe already on disk decodes as what it was. And a **String**
         * rather than the enum, for the reason stated on [StoredComponent.micros] and on the log's
         * own `source`: an enum-valued serializer THROWS on a name it does not know, so a future
         * third kind read by an older build would make somebody's whole recipe book undecodable
         * rather than merely puzzling. An unrecognised value falls back to `RECIPE` below.
         */
        val kind: String = "RECIPE",
    )

    @Serializable
    private data class Book(val recipes: List<StoredRecipe> = emptyList())

    private val bookKey = stringPreferencesKey("recipes")

    private val mutex = Mutex()

    /** Null until the first read; a loaded empty book is not the same as never having looked. */
    private var book: MutableList<StoredRecipe>? = null
    private var dirty = false
    private var flushJob: Job? = null

    private val _recipes = MutableStateFlow<List<Recipes.Recipe>>(emptyList())

    /** Every saved recipe, most recently saved first. */
    val recipes: StateFlow<List<Recipes.Recipe>> = _recipes.asStateFlow()

    /** Loads on first use. Call before reading [recipes] if the screen needs it populated. */
    suspend fun load(): List<Recipes.Recipe> = mutex.withLock { bookLocked().map { it.domain() } }

    suspend fun byId(id: String): Recipes.Recipe? =
        mutex.withLock { bookLocked().firstOrNull { it.id == id }?.domain() }

    /**
     * Save a new recipe or replace an existing one.
     *
     * Upserts on [Recipes.Recipe.id], so a builder that edits and saves repeatedly leaves one
     * record rather than a pile of near-duplicates.
     */
    suspend fun save(recipe: Recipes.Recipe, nowMs: Long = System.currentTimeMillis()) {
        mutex.withLock {
            val b = bookLocked()
            b.removeAll { it.id == recipe.id }
            b.add(recipe.stored(nowMs))
            markLocked(b)
        }
        scheduleFlush()
    }

    suspend fun remove(id: String) {
        mutex.withLock {
            val b = bookLocked()
            if (!b.removeAll { it.id == id }) return@withLock
            markLocked(b)
        }
        scheduleFlush()
    }

    suspend fun clear() {
        mutex.withLock {
            book = mutableListOf()
            dirty = false
            // ⚠️ Cancel the pending flush before wiping, or a write scheduled a moment ago restores
            // what was just deleted. Every store here carries this and it is the same reason.
            flushJob?.cancel()
            publishLocked(emptyList<StoredRecipe>())
        }
        runCatching { context.recipeDataStore.edit { it.remove(bookKey) } }
    }

    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
    }

    // ------------------------------------------------------------------------------- internals

    private suspend fun bookLocked(): MutableList<StoredRecipe> {
        book?.let { return it }
        val raw = context.recipeDataStore.data.first()[bookKey]
        val loaded = raw
            ?.let { runCatching { json.decodeFromString(Book.serializer(), it) }.getOrNull() }
            ?.recipes
            ?: emptyList()
        val b = loaded.toMutableList()
        book = b
        publishLocked(b)
        return b
    }

    private fun markLocked(b: List<StoredRecipe>) {
        dirty = true
        publishLocked(b)
    }

    /**
     * Newest first, because the thing you last saved is nearly always the thing you want.
     *
     * ⚠️ Takes the STORED rows and does both the sort and the conversion, rather than taking domain
     * objects and reaching back for the timestamps. `savedAtMs` exists only in the stored shape, so
     * splitting the two halves means one of them can be handed a list the other did not order.
     */
    private fun publishLocked(rows: List<StoredRecipe>) {
        _recipes.value = rows.sortedByDescending { it.savedAtMs }.map { it.domain() }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val payload = mutex.withLock {
            if (!dirty) return
            val b = book ?: return
            // ⚠️ Cleared after the snapshot, not after the write. A failed write is retried by the
            // next change; a flag cleared before it would lose that change silently.
            dirty = false
            json.encodeToString(Book.serializer(), Book(b.toList()))
        }
        runCatching { context.recipeDataStore.edit { it[bookKey] = payload } }
    }

    private fun StoredRecipe.domain() = Recipes.Recipe(
        id = id,
        name = name,
        components = components.map {
            Recipes.Component(
                foodId = it.foodId,
                name = it.name,
                per100g = NutritionDay.Nutrients(
                    kcal = it.kcal, proteinG = it.p, fatG = it.f, carbG = it.c,
                    fibreG = it.fibre, sugarG = it.sugar, satFatG = it.satFat, sodiumMg = it.sodium,
                ),
                grams = it.grams,
                micros = Micronutrients.Amounts(
                    it.micros.mapNotNull { (k, v) ->
                        runCatching { Micronutrients.Micro.valueOf(k) }.getOrNull()?.let { m -> m to v }
                    }.toMap(),
                ),
                extras = NutrientSet.Amounts(
                    it.extras.mapNotNull { (k, v) ->
                        runCatching { NutrientSet.Nutrient.valueOf(k) }.getOrNull()?.let { n -> n to v }
                    }.toMap(),
                ),
            )
        },
        cookedYieldG = cookedYieldG,
        servings = servings,
        note = note,
        kind = runCatching { Recipes.Kind.valueOf(kind) }.getOrDefault(Recipes.Kind.RECIPE),
    )

    private fun Recipes.Recipe.stored(nowMs: Long) = StoredRecipe(
        id = id,
        name = name,
        components = components.map {
            StoredComponent(
                foodId = it.foodId, name = it.name, grams = it.grams,
                kcal = it.per100g.kcal, p = it.per100g.proteinG, f = it.per100g.fatG,
                c = it.per100g.carbG, fibre = it.per100g.fibreG, sugar = it.per100g.sugarG,
                satFat = it.per100g.satFatG, sodium = it.per100g.sodiumMg,
                micros = it.micros.values.entries.associate { (m, v) -> m.name to v },
                extras = it.extras.values.entries.associate { (n, v) -> n.name to v },
            )
        },
        cookedYieldG = cookedYieldG,
        servings = servings,
        note = note,
        savedAtMs = nowMs,
        kind = kind.name,
    )

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
