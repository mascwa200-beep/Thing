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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.plateDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pulse_plate")

/**
 * A meal being assembled, before it is written into the record.
 *
 * ⚠️ **Persisted rather than held in the view model, and that is the decision the whole store rests
 * on.** A plate is put together over several minutes — reading a packet, weighing a portion, going
 * back for the thing that was forgotten — and Android kills a backgrounded process whenever it
 * pleases. An in-memory plate lost to an incoming telephone call is worse than having no plate at
 * all, because the alternative was five entries that were already safely in the log.
 *
 * ⚠️ **What is staged here is not in the record and does not count.** Nothing reads this to work out
 * a day's intake, and [Expenditure] never sees it — the twenty-eight-day window is built from the
 * food LOG. That is the point of staging: it is a draft, and a draft that quietly counted would be
 * worse than one that never existed, because the expenditure measurement would move on food nobody
 * has decided to eat.
 *
 * ⚠️ **Each item is stamped with the day it was staged FOR, and committing honours that stamp.** The
 * surfaces let somebody page back and forth through days while a plate is standing, so a plate
 * assembled on Monday and committed on Tuesday must still land on Monday — that is what the person
 * meant. `MealDraft.daysCovered` is how a surface finds out and says so.
 */
class PlateStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /**
     * One staged item.
     *
     * ⚠️ Every field of [NutritionDay.Entry] is carried, including the sparse [micros] and [extras],
     * because committing must produce the entry that the direct path would have produced. A store
     * that kept only the four macros would silently drop a scanned product's calcium between staging
     * and committing, and the loss would be invisible: the entry would look complete.
     */
    @Serializable
    private data class Staged(
        val id: String,
        val dayStartMs: Long,
        val atMs: Long,
        val name: String,
        val grams: Double,
        val kcal: Double,
        val proteinG: Double,
        val fatG: Double,
        val carbG: Double,
        val fibreG: Double = 0.0,
        val sugarG: Double = 0.0,
        val satFatG: Double = 0.0,
        val sodiumMg: Double = 0.0,
        val brand: String = "",
        val servingLabel: String = "",
        val meal: String = NutritionDay.Meal.SNACK.name,
        val source: String = NutritionDay.Source.CUSTOM.name,
        val foodId: String = "",
        val micros: Map<String, Double> = emptyMap(),
        val extras: Map<String, Double> = emptyMap(),
    )

    @Serializable
    private data class Stored(val items: List<Staged> = emptyList())

    private val prefsKey = stringPreferencesKey("plate_json")
    private val mutex = Mutex()
    private var loaded: Stored? = null
    private var flushJob: Job? = null

    private val _items = MutableStateFlow<List<NutritionDay.Entry>>(emptyList())

    /** What is on the plate, in the order it was put there. */
    val items: StateFlow<List<NutritionDay.Entry>> = _items.asStateFlow()

    /**
     * ⚠️ **`withContext`, not merely `suspend`.** A `suspend` function runs on whatever dispatcher its
     * caller is on, and `HealthViewModel.refresh()` — which runs on every foreground — calls into here
     * from `viewModelScope`, which is `Dispatchers.Main.immediate`. `DataStore` reads the preferences
     * FILE on its own IO scope, and a note in this repository once took that to mean the whole read was
     * off the frame thread; it is not. A flow emission is delivered in the COLLECTOR's context, so
     * `first()` resumes on the caller's dispatcher and the `kotlinx.serialization` decode below it — the
     * expensive half, over a record that grows for as long as somebody uses this — ran between two
     * frames. Invisible on a fast phone, a dropped frame every launch on a slow one.
     */
    private suspend fun loadLocked(): Stored = loaded ?: run {
        val s = withContext(Dispatchers.IO) {
            val raw = context.plateDataStore.data.first()[prefsKey]
            raw?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?: Stored()
        }
        loaded = s
        publish(s)
        s
    }

    /**
     * Read the plate in, so [items] describes what is standing rather than an empty plate.
     *
     * ⚠️ Worth calling before anything collects [items]. Every flow in this app starts empty and
     * fills on first read, and that has silently hidden whole categories of data on a cold screen
     * more than once — so a plate assembled before a process death would appear to have been thrown
     * away until something else happened to touch the store.
     */
    suspend fun load() {
        mutex.withLock { loadLocked() }
    }

    // ------------------------------------------------------------------------------------ writing

    /**
     * Put one thing on the plate.
     *
     * ⚠️ **Nothing is merged.** Staging the same food twice is two helpings, which is a thing people
     * genuinely do, and folding them into one row would silently halve the count while leaving the
     * total right — a discrepancy nobody would ever find.
     */
    suspend fun stage(entry: NutritionDay.Entry) {
        mutex.withLock {
            val s = loadLocked()
            if (s.items.size >= MAX_ITEMS) return@withLock
            val next = s.copy(items = s.items + entry.toStaged())
            loaded = next
            publish(next)
        }
        scheduleFlush()
    }

    /** Take one thing off it. */
    suspend fun unstage(id: String) {
        mutex.withLock {
            val s = loadLocked()
            if (s.items.none { it.id == id }) return@withLock
            val next = s.copy(items = s.items.filterNot { it.id == id })
            loaded = next
            publish(next)
        }
        scheduleFlush()
    }

    /**
     * Take everything off, and hand back what was on it.
     *
     * ⚠️ **Emptying and reading are one operation on purpose.** A caller that read the plate, wrote
     * the entries to the log and then cleared would double-log everything if a second tap landed in
     * between — and a commit button is exactly the control people double-tap. Draining under the
     * lock means the second call finds nothing and writes nothing.
     *
     * ⚠️ The flush is cancelled first, for the same reason [clear] cancels it: a buffered write
     * holding the pre-drain snapshot would put the plate back seconds after it was committed, and
     * the person would then commit it a second time believing it had failed.
     */
    suspend fun drain(): List<NutritionDay.Entry> {
        flushJob?.cancel()
        val taken = mutex.withLock {
            val s = loadLocked()
            val out = _items.value
            if (s.items.isEmpty()) return@withLock emptyList()
            loaded = Stored()
            publish(Stored())
            out
        }
        scheduleFlush()
        return taken
    }

    // ---------------------------------------------------------------------------------- lifecycle

    suspend fun clear() {
        // Cancel first, or a buffered write resurrects the plate after this returns.
        flushJob?.cancel()
        mutex.withLock {
            loaded = Stored()
            _items.value = emptyList()
        }
        runCatching { context.plateDataStore.edit { it.remove(prefsKey) } }
    }

    /**
     * The outcome of the most recent write, so an explicit [flushNow] can report a failure it would
     * otherwise swallow.
     *
     * ⚠️ **Both callers of [flushNow] already wrap it in a reporter that could never fire.** Every
     * store of this shape catches its own DataStore edit and discards the `Result`, so the "the
     * store could not be written to disk; anything recorded since is lost" report in `MainActivity`
     * and `NutritionContainer` was structurally unreachable — a claim in a KDoc that nothing could
     * make true. The debounced background flush still swallows, deliberately: an exception thrown
     * there escapes into a launched coroutine and takes the process with it.
     */
    @Volatile
    private var lastWrite: Result<*>? = null

    suspend fun flushNow() {
        flushJob?.cancel()
        // ⚠️ Cleared first: [flush] returns early when nothing is owed, and a stale failure
        // from an earlier write would then be reported against a write no longer outstanding.
        lastWrite = null
        flush()
        lastWrite?.getOrThrow()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    /**
     * ⚠️ On IO for the same reason [loadLocked] is: the debounced path already launches on this store's
     * own IO scope, but [flushNow] is called from `onStop` through the container's flush-everything, and
     * that runs in an activity scope on the main thread. Encoding a whole store to JSON there is the
     * worst moment to do it — the system is timing how quickly the app backgrounds.
     */
    private suspend fun flush(): Unit = withContext(Dispatchers.IO) {
        val snapshot = mutex.withLock { loaded } ?: return@withContext
        lastWrite = runCatching {
            context.plateDataStore.edit {
                it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot)
            }
        }
    }

    private fun publish(s: Stored) {
        _items.value = s.items.map { it.toEntry() }
    }

    // ------------------------------------------------------------------------------- conversion

    private fun NutritionDay.Entry.toStaged() = Staged(
        id = id,
        dayStartMs = dayStartMs,
        atMs = atMs,
        name = name,
        grams = grams,
        kcal = nutrients.kcal,
        proteinG = nutrients.proteinG,
        fatG = nutrients.fatG,
        carbG = nutrients.carbG,
        fibreG = nutrients.fibreG,
        sugarG = nutrients.sugarG,
        satFatG = nutrients.satFatG,
        sodiumMg = nutrients.sodiumMg,
        brand = brand,
        servingLabel = servingLabel,
        meal = meal.name,
        source = source.name,
        foodId = foodId,
        // ⚠️ Keyed by enum NAME rather than ordinal. An ordinal is a promise never to reorder an
        // enum, and both of these are lists that grow; a name that no longer resolves is dropped on
        // the way back, which loses one nutrient rather than shifting every one of them by a place.
        micros = micros.values.mapKeys { it.key.name },
        extras = extras.values.mapKeys { it.key.name },
    )

    private fun Staged.toEntry() = NutritionDay.Entry(
        id = id,
        dayStartMs = dayStartMs,
        atMs = atMs,
        name = name,
        grams = grams,
        nutrients = NutritionDay.Nutrients(
            kcal = kcal,
            proteinG = proteinG,
            fatG = fatG,
            carbG = carbG,
            fibreG = fibreG,
            sugarG = sugarG,
            satFatG = satFatG,
            sodiumMg = sodiumMg,
        ),
        brand = brand,
        servingLabel = servingLabel,
        meal = runCatching { NutritionDay.Meal.valueOf(meal) }.getOrDefault(NutritionDay.Meal.SNACK),
        source = runCatching { NutritionDay.Source.valueOf(source) }
            .getOrDefault(NutritionDay.Source.CUSTOM),
        foodId = foodId,
        micros = Micronutrients.Amounts(
            micros.mapNotNull { (k, v) ->
                Micronutrients.Micro.entries.firstOrNull { it.name == k }?.let { it to v }
            }.toMap(),
        ),
        extras = NutrientSet.Amounts(
            extras.mapNotNull { (k, v) ->
                NutrientSet.Nutrient.entries.firstOrNull { it.name == k }?.let { it to v }
            }.toMap(),
        ),
    )

    companion object {
        /**
         * ⚠️ A backstop against a stuck surface, not a limit anybody should ever meet. The largest
         * real plate is a dozen ingredients; this is high enough that a person assembling a genuine
         * Christmas dinner never notices, and low enough that a repeating tap cannot grow the blob
         * without bound. Reaching it stages nothing further rather than evicting — silently dropping
         * the FIRST thing somebody put on the plate would be the worst of both.
         */
        const val MAX_ITEMS = 60

        private const val FLUSH_DELAY_MS = 2_000L
    }
}
