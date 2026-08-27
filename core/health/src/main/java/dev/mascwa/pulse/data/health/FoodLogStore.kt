package dev.mascwa.pulse.data.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Expenditure
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
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

private val Context.foodDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_food")

/**
 * The food log — every entry, and a resident index of what each day came to.
 *
 * ## Why this one is sharded when nothing else in the app is
 *
 * Every other store here is a single blob because every other store holds tens or hundreds of small
 * things. This holds five or so entries a day forever. Measured by encoding a real [StoredEntry]
 * through the shipped serializer rather than estimating it:
 *
 *     one entry, as this writes them today  1,029 bytes   (286 bare; the micronutrients and the
 *                                                          29 further nutrients are most of it)
 *     five a day, one year                   1.88 MB
 *     five a day, five years                 9.39 MB
 *     the INDEX for one year                   27 kB      <- about 1.4% of the log
 *
 * So entries live in **monthly shards**, and a small resident **index** carries each day's totals. The
 * index is what the daily rings, the charts and [Expenditure] read, so the hot path never opens a
 * shard at all.
 *
 * ## ⚠️ Why the shards are plain files and not more preference keys
 *
 * They were preference keys, and **that made the sharding buy almost nothing.** DataStore Preferences
 * keeps every key of a store in ONE file, so `data.first()[shardKey(month)]` read and protobuf-parsed
 * the *whole* file — every month's JSON string — to pick one out, and `edit {}` rewrote all of it. The
 * consequences, none of which the old note here admitted:
 *
 *  - **Launch** read and parsed the entire log to get the 27 kB index.
 *  - **Every month's JSON stayed resident** for the process lifetime, because DataStore caches the
 *    `Preferences` object it read.
 *  - **Every meal logged rewrote the whole history** — 1.88 MB after a year, 9.4 MB after five.
 *
 * Sharding did save the JSON *decoding*, which is real. It saved nothing at all on IO or on the bytes
 * held in memory, which is what the old text claimed.
 *
 * A shard is now `filesDir/food_log/<month>.json`, read on demand and written by the same temp-file
 * -then-rename that DataStore itself uses. Logging a meal writes one month plus the index; opening
 * the app reads the index alone.
 *
 * ⚠️ **All of that file IO goes through [Dispatchers.IO] explicitly.** The preference read did its own
 * IO on DataStore's internal scope, so the old code could be called from the main thread and get away
 * with it; a bare `File.readText` in the same place would not.
 *
 * ⚠️ **The index is derived, and is always recomputed from the shard that changed — never patched.**
 * A cache that is incrementally updated alongside its source drifts the first time an edge case is
 * missed, and a drifted calorie total is invisible: the number is plausible, it is simply not the sum of
 * what is under it. Recomputing the one affected day makes disagreement impossible by construction and
 * costs nothing, because the shard is already in hand at that point.
 *
 * ⚠️ **The shard key is derived in UTC**, though the *day* an entry belongs to is the caller's local one.
 * A shard is a filing decision and nothing more; taking its key from the device zone would move entries
 * between files when somebody flies, which is a whole class of bug bought for no benefit at all.
 */
class FoodLogStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    // ⚠️ `internal` rather than `private` so a unit test can decode a REAL blob through the REAL
    // class. These two describe the on-disk shape of a year of somebody's food log, and the risk
    // worth gating is that a new field makes every prior month undecodable — which a test against a
    // hand-written copy of this shape could not detect, because the copy would be the thing that
    // changed. The same reasoning made `Directory` internal for the layout harness.
    @Serializable
    internal data class StoredNutrients(
        val kcal: Double = 0.0,
        val p: Double = 0.0,
        val f: Double = 0.0,
        val c: Double = 0.0,
        val fibre: Double = 0.0,
        val sugar: Double = 0.0,
        val satFat: Double = 0.0,
        val sodium: Double = 0.0,
    )

    @Serializable
    internal data class StoredEntry(
        val id: String,
        val day: Long,
        val at: Long,
        val name: String,
        val grams: Double,
        val n: StoredNutrients,
        val brand: String = "",
        val serving: String = "",
        val meal: String = "SNACK",
        val source: String = "CUSTOM",
        val foodId: String = "",

        /**
         * Vitamins and minerals in this portion, keyed by `Micronutrients.Micro` name.
         *
         * ⚠️ **Defaulted, so every entry logged before this field existed still decodes** — the
         * whole log is one JSON blob per month, and a required field would make every prior month
         * unreadable at once. Empty is also the honest value for those entries: nothing recorded
         * their micronutrients, which is exactly what an absent map means everywhere else.
         *
         * ⚠️ Keyed by String rather than the enum for the same reason `Food.micros` is: an
         * enum-keyed serializer throws on a name it does not know, so renaming a micronutrient
         * would make a year of logs undecodable. An unknown key is dropped on the way back in.
         */
        val micros: Map<String, Double> = emptyMap(),

        /**
         * The further nutrients in this portion, keyed by `NutrientSet.Nutrient` name.
         *
         * ⚠️ Defaulted and String-keyed for exactly the reasons above — a required field would make
         * every month logged before this existed unreadable in one go, and an enum-keyed serializer
         * would do the same the day a nutrient is renamed.
         */
        val extras: Map<String, Double> = emptyMap(),
    )

    @Serializable
    private data class Shard(val entries: List<StoredEntry> = emptyList())

    @Serializable
    private data class DayRow(
        val day: Long,
        val kcal: Double,
        val p: Double,
        val f: Double,
        val c: Double,
        val count: Int,
    )

    @Serializable
    private data class Index(
        val days: List<DayRow> = emptyList(),
        /**
         * Day-starts the user explicitly marked as a fast.
         *
         * ⚠️ Deliberately NOT a field on [DayRow]. A fasted day has no entries, and
         * [reindexLocked] removes a day from the index the moment its entry list is empty — so a
         * flag living on the row would be deleted by the very code that keeps the row honest. A
         * fast is orthogonal to entries, so it is stored orthogonally.
         *
         * Defaulted, so every blob written before this existed still decodes.
         */
        val fasted: List<Long> = emptyList(),
    )

    /**
     * How many fast marks are kept. Two years, oldest dropped first.
     *
     * A fast older than the longest expenditure window cannot change any answer, and this is the
     * only day-keyed structure here that is not already bounded by shard pruning.
     */
    private val MAX_FASTED_DAYS = 730

    private val indexKey = stringPreferencesKey("food_index")
    /** Legacy only — the months live in files now. Kept so [clear] and the migration can name them. */
    private fun shardKey(month: String) =
        stringPreferencesKey(FoodLogFiling.SHARD_PREFIX + month)

    private val mutex = Mutex()
    private var index: MutableMap<Long, DayRow>? = null

    /**
     * Decoded shards held in memory, most-recently-used last, **bounded**.
     *
     * ⚠️ This was an unbounded `mutableMapOf`, which is the more expensive half of the same defect
     * the class note describes: scrolling back through five years left sixty decoded month graphs
     * resident forever, and a decoded entry is several times its JSON — roughly 2 kB once its two
     * nutrient maps are objects, so ~300 kB a month, ~18 MB for five years.
     *
     * [FoodLogFiling.MAX_RESIDENT_SHARDS] of 4 covers every read path with headroom ([recentFoods] opens two,
     * [entriesFor] one) at about 1.2 MB.
     *
     * ⚠️ **A dirty shard is never evicted**, so an import that touches sixty months holds sixty until
     * they are flushed. That is not a leak — those entries exist nowhere else yet.
     */
    private val shards: LinkedHashMap<String, Shard> = LinkedHashMap(16, 0.75f, true)

    /**
     * Legacy shards that could not be moved out of the preference store, kept for this session.
     *
     * Normally null. See [migrateLegacyShardsLocked]: a month is only removed from the old store once
     * its file is on disk, so anything left here is a month whose file write failed and which must
     * still be readable until the next launch retries.
     */
    private var legacyShards: MutableMap<String, String>? = null
    private var flushJob: Job? = null

    /**
     * Bumped by [clear]. A [flush] that took its snapshot before the clear must not write it back.
     *
     * ⚠️ [Job.cancel] is not enough on its own: it stops a flush still waiting on its delay, and does
     * nothing about one already past the snapshot and part-way through writing files. That window is
     * a few milliseconds, but what it leaves behind is a month of somebody's food log on disk after
     * they asked for all of it to be gone — unreachable, because the index went with it, and still
     * there. A generation check costs one comparison and closes it.
     */
    private var clearGeneration = 0
    private val dirtyShards = mutableSetOf<String>()
    private var indexDirty = false

    /**
     * Days explicitly marked as a fast. Loaded with the index and written with it.
     *
     * ⚠️ Capped, because it is the one structure here that only ever grows — every other day-keyed
     * thing is bounded by the shards being pruned. [MAX_FASTED_DAYS] is a couple of years, and the
     * oldest go first; a fast older than the longest expenditure window cannot affect any answer.
     */
    private var fastedDays: MutableSet<Long>? = null

    private val _days = MutableStateFlow<Map<Long, NutritionDay.Nutrients>>(emptyMap())

    /** Every day that has anything logged, keyed by its local day-start. The cheap view. */
    val days: StateFlow<Map<Long, NutritionDay.Nutrients>> = _days.asStateFlow()

    // ------------------------------------------------------------------------------------ mapping

    private fun NutritionDay.Nutrients.stored() =
        StoredNutrients(kcal, proteinG, fatG, carbG, fibreG, sugarG, satFatG, sodiumMg)

    private fun StoredNutrients.domain() =
        NutritionDay.Nutrients(kcal, p, f, c, fibre, sugar, satFat, sodium)

    private fun NutritionDay.Entry.stored() = StoredEntry(
        id, dayStartMs, atMs, name, grams, nutrients.stored(), brand, servingLabel,
        meal.name, source.name, foodId,
        micros.values.entries.associate { it.key.name to it.value },
        extras.values.entries.associate { it.key.name to it.value },
    )

    private fun StoredEntry.domain() = NutritionDay.Entry(
        id = id,
        dayStartMs = day,
        atMs = at,
        name = name,
        grams = grams,
        nutrients = n.domain(),
        brand = brand,
        servingLabel = serving,
        meal = runCatching { NutritionDay.Meal.valueOf(meal) }.getOrDefault(NutritionDay.Meal.SNACK),
        source = runCatching { NutritionDay.Source.valueOf(source) }.getOrDefault(NutritionDay.Source.CUSTOM),
        foodId = foodId,
        micros = Micronutrients.Amounts(
            micros.mapNotNull { (k, v) ->
                runCatching { Micronutrients.Micro.valueOf(k) }.getOrNull()?.let { it to v }
            }.toMap()
        ),
        // ⚠️ An unrecognised name is dropped, never guessed at. The log outlives any one build, so
        // a figure whose nutrient this version does not know is a figure with no unit.
        extras = NutrientSet.Amounts(
            extras.mapNotNull { (k, v) ->
                runCatching { NutrientSet.Nutrient.valueOf(k) }.getOrNull()?.let { it to v }
            }.toMap()
        ),
    )

    /** `2026-08`, in UTC. See the class note: this is a filing decision, not a calendar one. */
    private fun monthOf(dayStartMs: Long): String =
        Instant.ofEpochMilli(dayStartMs).atZone(ZoneOffset.UTC)
            .let { String.format(java.util.Locale.US, "%04d-%02d", it.year, it.monthValue) }

    // ------------------------------------------------------------------------------------ loading

    private suspend fun indexLocked(): MutableMap<Long, DayRow> = index ?: run {
        val prefs = context.foodDataStore.data.first()
        // ⚠️ Decoded ONCE. This used to parse the same JSON twice — once for `days`, once for
        // `fasted` — which on a five-year index is two passes over 135 kB on the launch path.
        val parsed = prefs[indexKey]
            ?.let { runCatching { json.decodeFromString(Index.serializer(), it) }.getOrNull() }
        val map = parsed?.days.orEmpty().associateBy { it.day }.toMutableMap()
        index = map
        fastedDays = parsed?.fasted.orEmpty().toMutableSet()
        // Driven off the snapshot already in hand, so migrating costs no extra read.
        migrateLegacyShardsLocked(prefs)
        publishLocked(map)
        map
    }

    /** The fasted set, loading the index first if it has not been read yet. */
    private suspend fun fastedLocked(): MutableSet<Long> {
        indexLocked()
        return fastedDays ?: mutableSetOf<Long>().also { fastedDays = it }
    }

    private suspend fun shardLocked(month: String): Shard = shards[month] ?: run {
        val raw = readShardFile(month) ?: legacyShards?.get(month)
        val s = raw
            ?.let { runCatching { json.decodeFromString(Shard.serializer(), it) }.getOrNull() }
            ?: Shard()
        shards[month] = s
        evictCleanShardsLocked()
        s
    }

    /**
     * Drop the least-recently-used **clean** shards until the cache is back within its bound.
     *
     * ⚠️ Clean only. A dirty shard holds entries that exist nowhere else until [flush] writes them,
     * so evicting one would lose a logged meal — silently, because the next read would find the file
     * without it and report a plausible smaller day.
     */
    private fun evictCleanShardsLocked() {
        // ⚠️ `shards` is access-ordered, so `keys` really is least-recently-used first — which is the
        // ordering [FoodLogFiling.evictable] is documented to expect and cannot check for itself.
        FoodLogFiling
            .evictable(shards.keys.toList(), dirtyShards, FoodLogFiling.MAX_RESIDENT_SHARDS)
            .forEach { shards.remove(it) }
    }

    // -------------------------------------------------------------------------------- shard files

    private fun shardDir(): File = File(context.filesDir, FoodLogFiling.SHARD_DIR)

    /**
     * ⚠️ [month] is always `%04d-%02d` from [monthOf], so it can hold no path separator and no `..`.
     * Nothing outside this class supplies one; every caller derives it from a day-start Long.
     */
    private fun shardFile(month: String): File = File(shardDir(), "$month.json")

    private suspend fun readShardFile(month: String): String? = withContext(Dispatchers.IO) {
        runCatching { shardFile(month).takeIf { it.isFile }?.readText() }.getOrNull()
    }

    /**
     * Write one month, atomically. Returns whether it landed.
     *
     * ⚠️ Temp file then rename, which is the same shape `SingleProcessDataStore` uses for its own
     * writes: on Linux `rename(2)` replaces the target atomically, so a reader either sees the whole
     * previous month or the whole new one and never a half-written file. A `writeText` straight over
     * the target would leave a truncated month behind if the process died mid-write.
     */
    private suspend fun writeShardFile(month: String, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = shardDir()
            dir.mkdirs()
            val tmp = File(dir, "$month.json.tmp")
            tmp.writeText(text)
            if (tmp.renameTo(shardFile(month))) true else { tmp.delete(); false }
        }.getOrDefault(false)
    }

    /**
     * Move any month still living as a preference key out into its own file, once.
     *
     * ⚠️ **Ordering is the whole safety argument: the file is written, and its rename reports success,
     * BEFORE the key is removed.** If the process dies between the two, the key is still there and this runs again next
     * launch over identical content — so the migration is idempotent and cannot lose a month. A month
     * whose file could not be written keeps its key AND is served from [legacyShards] for the rest of
     * this session, so nothing disappears while the write is failing either.
     *
     * ⚠️ **The index key starts with the same prefix** (`food_index` against `food_2026-08`), so the
     * scan matches the month SHAPE rather than the prefix. Matching the prefix would have written the
     * index JSON out as a month called "index" and then deleted the real index — every day's totals
     * gone, and the log itself intact behind them, which is about the worst shape a bug here could
     * take.
     */
    private suspend fun migrateLegacyShardsLocked(prefs: Preferences) {
        val legacy = prefs.asMap().mapNotNull { (k, v) ->
            val month = FoodLogFiling.legacyMonth(k.name)
            if (month != null && v is String) month to v else null
        }
        if (legacy.isEmpty()) return

        val moved = mutableListOf<String>()
        val stuck = mutableMapOf<String, String>()
        for ((month, text) in legacy) {
            // An existing file is by construction the same content or newer, so it wins and the key
            // is safe to drop.
            val onDisk = withContext(Dispatchers.IO) { shardFile(month).isFile }
            if (onDisk || writeShardFile(month, text)) moved += month else stuck[month] = text
        }
        legacyShards = stuck.takeIf { it.isNotEmpty() }
        if (moved.isNotEmpty()) {
            runCatching {
                context.foodDataStore.edit { p -> moved.forEach { p.remove(shardKey(it)) } }
            }
        }
    }

    private fun publishLocked(map: Map<Long, DayRow>) {
        _days.value = map.mapValues { (_, r) ->
            NutritionDay.Nutrients(kcal = r.kcal, proteinG = r.p, fatG = r.f, carbG = r.c)
        }
    }

    /** Recompute one day's index row from the shard it lives in. The only way the index ever changes. */
    private fun reindexLocked(dayStartMs: Long, shard: Shard, map: MutableMap<Long, DayRow>) {
        val forDay = shard.entries.filter { it.day == dayStartMs }
        if (forDay.isEmpty()) {
            map.remove(dayStartMs)
        } else {
            val t = NutritionDay.total(forDay.map { it.domain() })
            map[dayStartMs] = DayRow(dayStartMs, t.kcal, t.proteinG, t.fatG, t.carbG, forDay.size)
        }
        indexDirty = true
        publishLocked(map)
    }

    // ------------------------------------------------------------------------------------ writing

    /** Log something. An id that already exists in that day's month is replaced; see [update] to move one. */
    suspend fun add(entry: NutritionDay.Entry) {
        mutex.withLock {
            val map = indexLocked()
            // ⚠️ Eating clears the fast, and it has to be here rather than left to the user. The two
            // are contradictory claims about the same day, and a stale mark would send the day to
            // the expenditure measurement as zero calories while the index also carried what was
            // eaten — the same day counted twice, once wrongly.
            fastedLocked().remove(entry.dayStartMs)
            val month = monthOf(entry.dayStartMs)
            val shard = shardLocked(month)
            val next = shard.copy(entries = shard.entries.filterNot { it.id == entry.id } + entry.stored())
            shards[month] = next
            dirtyShards += month
            reindexLocked(entry.dayStartMs, next, map)
        }
        scheduleFlush()
    }

    /**
     * Replace an entry, which may move it to another day.
     *
     * ⚠️ [previousDayStartMs] is required rather than searched for, and that is the difference between
     * an edit and a duplicate. The old copy lives in whichever shard its old day belongs to, and a
     * version of this that scanned the *loaded* shards for the id would quietly fail whenever that shard
     * had not been opened yet — leaving the original in place beside the edit, both counted. The caller
     * is editing an entry it is already showing, so it has the old day; being handed it means exactly one
     * shard is opened and the removal cannot miss.
     */
    suspend fun update(entry: NutritionDay.Entry, previousDayStartMs: Long) {
        mutex.withLock {
            val map = indexLocked()
            val oldMonth = monthOf(previousDayStartMs)
            val old = shardLocked(oldMonth)
            if (old.entries.any { it.id == entry.id }) {
                val trimmed = old.copy(entries = old.entries.filterNot { it.id == entry.id })
                shards[oldMonth] = trimmed
                dirtyShards += oldMonth
                reindexLocked(previousDayStartMs, trimmed, map)
            }
            val month = monthOf(entry.dayStartMs)
            val shard = shardLocked(month)
            val next = shard.copy(entries = shard.entries.filterNot { it.id == entry.id } + entry.stored())
            shards[month] = next
            dirtyShards += month
            reindexLocked(entry.dayStartMs, next, map)
        }
        scheduleFlush()
    }

    suspend fun remove(id: String, dayStartMs: Long) {
        mutex.withLock {
            val map = indexLocked()
            val month = monthOf(dayStartMs)
            val shard = shardLocked(month)
            if (shard.entries.none { it.id == id }) return@withLock
            val next = shard.copy(entries = shard.entries.filterNot { it.id == id })
            shards[month] = next
            dirtyShards += month
            reindexLocked(dayStartMs, next, map)
        }
        scheduleFlush()
    }

    /**
     * Put back a whole record read from a file. Returns how many were new.
     *
     * ⚠️ **NOT [add] in a loop, and the difference is not a micro-optimisation.** Every `add` takes the
     * mutex, re-indexes its day, publishes the day map and schedules a flush; a year of logging is
     * several thousand entries, so the loop would be several thousand mutex round-trips and several
     * thousand index publications for one action somebody is standing there waiting on. This takes the
     * lock once, groups by the shard each entry belongs in, and re-indexes each touched day exactly
     * once.
     *
     * ⚠️ **Deduped by id against the days being written into**, so importing the same file twice adds
     * nothing the second time. The check reads `shardLocked` for the months involved — never
     * [allEntries], which opens every month there has ever been and is exactly what the sharding
     * exists to avoid.
     */
    suspend fun importEntries(entries: List<NutritionDay.Entry>): Int {
        if (entries.isEmpty()) return 0
        var added = 0
        mutex.withLock {
            val map = indexLocked()
            val touchedDays = mutableSetOf<Long>()
            for ((month, batch) in entries.groupBy { monthOf(it.dayStartMs) }) {
                val shard = shardLocked(month)
                val existing = shard.entries.mapTo(HashSet()) { it.id }
                // ⚠️ Also deduped WITHIN the batch, not only against what is on disk. A file holding
                // the same id twice would otherwise land twice and be counted twice.
                val fresh = batch.filter { existing.add(it.id) }
                if (fresh.isEmpty()) continue
                shards[month] = shard.copy(entries = shard.entries + fresh.map { it.stored() })
                dirtyShards += month
                added += fresh.size
                fresh.forEach { touchedDays += it.dayStartMs }
            }
            // ⚠️ After every shard is in place, so a day whose entries arrived in more than one batch
            // is counted once and correctly. Re-indexing inside the loop would publish a half-built day.
            touchedDays.forEach { day -> reindexLocked(day, shardLocked(monthOf(day)), map) }
        }
        if (added > 0) scheduleFlush()
        return added
    }

    /** Copy a whole day onto another, which is how "same as yesterday" works. Returns how many landed. */
    suspend fun copyDay(fromDayStartMs: Long, toDayStartMs: Long, nowMs: Long, newId: () -> String): Int {
        val source = entriesFor(fromDayStartMs)
        if (source.isEmpty()) return 0
        source.forEach { e ->
            add(e.copy(id = newId(), dayStartMs = toDayStartMs, atMs = nowMs))
        }
        return source.size
    }

    // ------------------------------------------------------------------------------------ reading

    /** Everything logged on a day, in the order it was logged. Opens exactly one shard. */
    suspend fun entriesFor(dayStartMs: Long): List<NutritionDay.Entry> = mutex.withLock {
        indexLocked()
        shardLocked(monthOf(dayStartMs)).entries
            .filter { it.day == dayStartMs }
            .sortedBy { it.at }
            .map { it.domain() }
    }

    /**
     * Distinct foods eaten recently, most recent first, each carrying the portion last used.
     *
     * ⚠️ **Two shards at most, and which two is derived from [nowMs] rather than from the clock.**
     * Shards are filed by UTC month, so on the first of a month everything eaten "recently" is in
     * the one before — reading only the current shard would empty this list overnight, every month,
     * and look like the log had been wiped. Two is also the ceiling: somebody who has logged for a
     * year has twelve shards, and reading them all to fill a row of chips would open the whole
     * history for a screen that shows twenty entries.
     */
    suspend fun recentFoods(nowMs: Long, limit: Int = 20): List<NutritionDay.Entry> = mutex.withLock {
        indexLocked()
        val months = linkedSetOf(monthOf(nowMs), monthOf(nowMs - THIRTY_ONE_DAYS_MS))
        val entries = months.flatMap { shardLocked(it).entries }.map { it.domain() }
        NutritionDay.recentFoods(entries, limit)
    }

    /** A day's totals, from the index — no shard is opened. */
    suspend fun totalsFor(dayStartMs: Long): NutritionDay.Nutrients = mutex.withLock {
        val row = indexLocked()[dayStartMs] ?: return@withLock NutritionDay.Nutrients()
        NutritionDay.Nutrients(kcal = row.kcal, proteinG = row.p, fatG = row.f, carbG = row.c)
    }

    /**
     * Every logged day in the window, as [Expenditure] wants them.
     *
     * ⚠️ Days with nothing logged are **absent** rather than present with zero. Zero calories is a
     * measurement nobody has ever made, and handing it to [Expenditure] as one would drag its intake
     * mean toward the floor and report a starving person's expenditure for anybody who skipped a
     * weekend. Absent is what an unlogged day is, and the completeness gate is what prices it.
     */
    suspend fun intakeDays(fromMs: Long, toMs: Long): List<Expenditure.IntakeDay> = mutex.withLock {
        val eaten = indexLocked().values
            .filter { it.day in fromMs..toMs && it.kcal > 0.0 }
            .map { Expenditure.IntakeDay(it.day, it.kcal) }
        // ⚠️ A deliberate fast is a RECORD of what was eaten, not a gap, and passing it through is
        // what stops the expenditure measurement treating a disciplined faster like somebody who
        // forgot. It reaches the core as a real day worth zero calories.
        val fasts = fastedLocked()
            .filter { it in fromMs..toMs && !indexLocked().containsKey(it) }
            .map { Expenditure.IntakeDay(it, 0.0, fasted = true) }
        (eaten + fasts).sortedBy { it.dayStartMs }
    }

    /**
     * Mark or unmark a day as a deliberate fast.
     *
     * ⚠️ Refused on a day that already has entries, and the refusal is the honest answer rather than
     * an inconvenience: marking a fast on a day with food logged asserts two contradictory things
     * about it. Delete the entries first if the day really was a fast. Returns whether it took.
     */
    suspend fun setFasted(dayStartMs: Long, fasted: Boolean): Boolean = mutex.withLock {
        val set = fastedLocked()
        if (fasted && indexLocked().containsKey(dayStartMs)) return@withLock false
        val changed = if (fasted) set.add(dayStartMs) else set.remove(dayStartMs)
        if (changed) {
            indexDirty = true
            scheduleFlush()
        }
        true
    }

    /** Whether [dayStartMs] is marked as a deliberate fast. */
    suspend fun isFasted(dayStartMs: Long): Boolean = mutex.withLock {
        fastedLocked().contains(dayStartMs)
    }

    /** How many of the last [days] days have anything logged — the streak-ish number the coach shows. */
    suspend fun loggedDayCount(fromMs: Long, toMs: Long): Int = mutex.withLock {
        val eaten = indexLocked().values.count { it.day in fromMs..toMs && it.kcal > 0.0 }
        // Same reasoning as intakeDays: a day somebody told us about is a day they logged.
        val fasts = fastedLocked().count { it in fromMs..toMs && !indexLocked().containsKey(it) }
        eaten + fasts
    }

    /**
     * Every entry ever logged. **For export only.**
     *
     * ⚠️ This opens every shard, which is exactly what the sharding exists to avoid — five years of
     * logging is around sixty files and a couple of megabytes held at once. That is a fine price for
     * something a person asks for by name and waits on, and a terrible one for a screen. Nothing on a
     * hot path may call this; [totalsFor] and [entriesFor] are what those want.
     *
     * ⚠️ The month list comes from the **index**, not from the shards already in memory. A cold
     * process has opened none of them, so exporting from `shards.keys` would silently write out only
     * whatever the session happened to touch — a file that looks complete and is not, which is worse
     * than one that fails.
     */
    suspend fun allEntries(): List<NutritionDay.Entry> = mutex.withLock {
        val months = indexLocked().keys.map { monthOf(it) }.toSortedSet()
        months.flatMap { shardLocked(it).entries }
            .sortedWith(compareBy({ it.day }, { it.at }))
            .map { it.domain() }
    }

    // ---------------------------------------------------------------------------------- lifecycle

    /**
     * Read the index in, so [days] describes the record rather than an empty map.
     *
     * ⚠️ Worth calling before anything collects [days]. Every flow here starts empty and fills on the
     * first read, which has already made whole categories of data silently invisible elsewhere in this
     * app when a cold screen collected a flow nothing had touched.
     */
    suspend fun load() {
        mutex.withLock { indexLocked() }
    }

    suspend fun clear() {
        flushJob?.cancel()
        val months = mutex.withLock {
            // ⚠️ The index is loaded first on purpose. Without it, a clear on a cold process knows only
            // the shards it happens to have opened, and every other month stays on disk — so the log
            // comes back the next time one of those days is opened, after the user asked for it to be
            // gone.
            val known = shards.keys.toSet() + indexLocked().keys.map { monthOf(it) }
            clearGeneration++
            index = mutableMapOf()
            shards.clear()
            dirtyShards.clear()
            indexDirty = false
            legacyShards = null
            fastedDays = mutableSetOf()
            publishLocked(emptyMap())
            known
        }
        runCatching {
            context.foodDataStore.edit { prefs ->
                prefs.remove(indexKey)
                // Any month that never got migrated out of the old store.
                months.forEach { prefs.remove(shardKey(it)) }
            }
        }
        // ⚠️ The whole directory, not the index-derived month list. The list exists because a
        // preference key could only be named, never enumerated; a directory can be, so a month the
        // index somehow did not know about goes too. A clear the user asked for has to be complete.
        withContext(Dispatchers.IO) {
            runCatching { shardDir().listFiles()?.forEach { it.delete() } }
        }
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

    /**
     * Trailing throttle: one flush per window.
     *
     * ⚠️ **`isActive` is true while the write is RUNNING, not only while it is waiting** — the job
     * covers the delay and the flush together. So a meal logged during a write armed nothing, and
     * since [flush] takes its snapshot before that meal existed, it stayed on disk-owed until some
     * unrelated change happened to arm the next window. Flush-on-stop usually caught it, which is
     * why it never showed as lost data; it is still a change with no timer behind it.
     *
     * ⚠️ Closed by re-arming AFTER the write rather than by clearing the job before it. Clearing it
     * first is the shorter fix and lets an explicit [flushNow] run concurrently with a debounced
     * one, and neither the snapshots nor the two `edit` calls are ordered with respect to each
     * other — a stale snapshot could land last. Re-arming keeps exactly one write in flight.
     *
     * ⚠️ The dirty flags are the right thing to test because [flush] clears them at snapshot time
     * and every later change sets them again, so "anything still owed" is already tracked here and
     * needs no second flag to go stale against it. The other 21 stores of this shape have the same
     * window and no such flags; they are left alone rather than each given new state to get wrong.
     */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
            // ⚠️ Only after a write that WORKED. A failed shard marks itself dirty again, so
            // re-arming on that would spin every window for as long as the disk keeps refusing —
            // and a busy loop on a full disk is a worse failure than the deferred write this closes.
            // A failure falls back to the documented behaviour: retried by the next change.
            val owed = lastWrite?.isFailure != true &&
                mutex.withLock { indexDirty || dirtyShards.isNotEmpty() }
            if (owed) {
                flushJob = null // ⚠️ or the call below sees this very job and returns
                scheduleFlush()
            }
        }
    }

    private suspend fun flush() {
        val (generation, idxJson, shardJson) = mutex.withLock {
            val idx = if (indexDirty) index?.let { m ->
                json.encodeToString(
                    Index.serializer(),
                    Index(
                        days = m.values.sortedBy { it.day },
                        fasted = fastedDays.orEmpty().sorted().takeLast(MAX_FASTED_DAYS),
                    ),
                )
            } else null
            val out = dirtyShards.mapNotNull { m ->
                shards[m]?.let { m to json.encodeToString(Shard.serializer(), it) }
            }
            // ⚠️ Cleared only after the snapshot is taken, not after the write: a write that fails is
            // retried by the next change, and a flag cleared before the write would lose that change
            // silently. Anything arriving during the write marks itself dirty again.
            indexDirty = false
            dirtyShards.clear()
            Triple(clearGeneration, idx, out)
        }
        if (idxJson == null && shardJson.isEmpty()) return

        // ⚠️ A month whose file would not write is marked dirty AGAIN, so the next change retries it.
        // The comment above has always said a failed write is retried by the next change; with the
        // whole flush inside one swallowed `runCatching` that was not actually true — the dirty flags
        // were already cleared, so the failure was silent and permanent until that month was touched.
        val failed = shardJson.mapNotNull { (m, text) ->
            if (stale(generation)) return else m.takeIf { !writeShardFile(m, text) }
        }
        if (failed.isNotEmpty()) mutex.withLock { dirtyShards += failed }

        if (stale(generation)) return
        idxJson?.let { text ->
            lastWrite = runCatching { context.foodDataStore.edit { prefs -> prefs[indexKey] = text } }
        }
        // ⚠️ **The months hold the meals; the index is only their table of contents.** A shard that
        // would not write is the loss worth reporting, and it happens BEFORE the index edit above —
        // so without this the index's success would overwrite it and [flushNow] would report a clean
        // write over a day that is not on disk. Last, so it wins whatever the index did.
        if (failed.isNotEmpty()) {
            // ⚠️ The type argument is explicit because it cannot be inferred: `lastWrite` is a
            // `Result<*>?`, and a star projection gives the compiler nothing to infer `T` from.
            lastWrite = Result.failure<Unit>(
                IOException("could not write ${failed.size} month file(s): ${failed.joinToString()}"),
            )
        }
    }

    /** Whether a [clear] has landed since this flush took its snapshot. */
    private suspend fun stale(generation: Int): Boolean =
        mutex.withLock { clearGeneration != generation }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L

        /**
         * ⚠️ Thirty-ONE, so stepping back from any date in any month lands in the previous one.
         * Thirty would leave the 31st of a 31-day month pointing at itself, which is the one day of
         * the month the second shard would have been silently the same as the first.
         */
        const val THIRTY_ONE_DAYS_MS = 31L * 24 * 60 * 60 * 1000
    }
}
