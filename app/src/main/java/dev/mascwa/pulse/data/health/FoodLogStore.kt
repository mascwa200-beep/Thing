package dev.mascwa.pulse.data.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.Micronutrients
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset

private val Context.foodDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_food")

/**
 * The food log — every entry, and a resident index of what each day came to.
 *
 * ## Why this one is sharded when nothing else in the app is
 *
 * Every other store here is a single blob because every other store holds tens or hundreds of small
 * things. This holds five or so entries a day forever: five years of ordinary logging is around nine
 * thousand entries and a couple of megabytes, written back several times a day. One blob would mean
 * re-encoding the whole history on every meal and holding all of it in memory to show today.
 *
 * So entries live in **monthly shards**, loaded only when that month is actually being read, and a small
 * resident **index** carries each day's totals. The index is what the daily rings, the charts and
 * [Expenditure] read, so the hot path never opens a shard at all.
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
    private data class Index(val days: List<DayRow> = emptyList())

    private val indexKey = stringPreferencesKey("food_index")
    private fun shardKey(month: String) = stringPreferencesKey("food_$month")

    private val mutex = Mutex()
    private var index: MutableMap<Long, DayRow>? = null
    private val shards = mutableMapOf<String, Shard>()
    private var flushJob: Job? = null
    private val dirtyShards = mutableSetOf<String>()
    private var indexDirty = false

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
    )

    /** `2026-08`, in UTC. See the class note: this is a filing decision, not a calendar one. */
    private fun monthOf(dayStartMs: Long): String =
        Instant.ofEpochMilli(dayStartMs).atZone(ZoneOffset.UTC)
            .let { String.format(java.util.Locale.US, "%04d-%02d", it.year, it.monthValue) }

    // ------------------------------------------------------------------------------------ loading

    private suspend fun indexLocked(): MutableMap<Long, DayRow> = index ?: run {
        val raw = context.foodDataStore.data.first()[indexKey]
        val loaded = raw
            ?.let { runCatching { json.decodeFromString(Index.serializer(), it) }.getOrNull() }
            ?.days.orEmpty()
        val map = loaded.associateBy { it.day }.toMutableMap()
        index = map
        publishLocked(map)
        map
    }

    private suspend fun shardLocked(month: String): Shard = shards[month] ?: run {
        val raw = context.foodDataStore.data.first()[shardKey(month)]
        val s = raw
            ?.let { runCatching { json.decodeFromString(Shard.serializer(), it) }.getOrNull() }
            ?: Shard()
        shards[month] = s
        s
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
        indexLocked().values
            .filter { it.day in fromMs..toMs && it.kcal > 0.0 }
            .sortedBy { it.day }
            .map { Expenditure.IntakeDay(it.day, it.kcal) }
    }

    /** How many of the last [days] days have anything logged — the streak-ish number the coach shows. */
    suspend fun loggedDayCount(fromMs: Long, toMs: Long): Int = mutex.withLock {
        indexLocked().values.count { it.day in fromMs..toMs && it.kcal > 0.0 }
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
            index = mutableMapOf()
            shards.clear()
            dirtyShards.clear()
            indexDirty = false
            publishLocked(emptyMap())
            known
        }
        runCatching {
            context.foodDataStore.edit { prefs ->
                prefs.remove(indexKey)
                months.forEach { prefs.remove(shardKey(it)) }
            }
        }
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
        val (idxJson, shardJson) = mutex.withLock {
            val idx = if (indexDirty) index?.let { m -> json.encodeToString(Index.serializer(), Index(m.values.sortedBy { it.day })) } else null
            val out = dirtyShards.mapNotNull { m ->
                shards[m]?.let { m to json.encodeToString(Shard.serializer(), it) }
            }
            // ⚠️ Cleared only after the snapshot is taken, not after the write: a write that fails is
            // retried by the next change, and a flag cleared before the write would lose that change
            // silently. Anything arriving during the write marks itself dirty again.
            indexDirty = false
            dirtyShards.clear()
            idx to out
        }
        if (idxJson == null && shardJson.isEmpty()) return
        runCatching {
            context.foodDataStore.edit { prefs ->
                idxJson?.let { prefs[indexKey] = it }
                shardJson.forEach { (m, s) -> prefs[shardKey(m)] = s }
            }
        }
    }

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
