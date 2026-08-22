package dev.mascwa.pulse.data.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.BodyTrend
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

private val Context.bodyDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_body")

/**
 * Every weigh-in and body measurement, on this device only.
 *
 * In-memory state (authoritative) + a debounced flush, exactly as [dev.mascwa.pulse.data.profile.ProfileStore]
 * does. The store keeps raw readings and nothing derived: the trend, the rate and their uncertainty all
 * come from [BodyTrend], which is pure and tested, so there is nothing here that can disagree with what
 * the screen shows.
 *
 * ⚠️ **Nothing is capped, and that is deliberate.** Every other store in this app evicts to bound its
 * blob, and every one of those holds observed or derived data that can be recomputed. This holds what a
 * person recorded about their own body over years, the whole point of it is the long run, and silently
 * dropping the oldest weigh-in would quietly shorten the very history the trend exists to read. A daily
 * weigh-in for a decade is about 150 kB of JSON, which is not a problem worth creating one for.
 */
class BodyStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** A body measurement other than weight. [cm] because every one of these is a circumference. */
    enum class MeasureKind(val label: String) {
        WAIST("Waist"),
        HIPS("Hips"),
        CHEST("Chest"),
        NECK("Neck"),
        THIGH("Thigh"),
        ARM("Arm"),
    }

    data class Measurement(val atMs: Long, val kind: MeasureKind, val cm: Double)

    @Serializable
    private data class StoredWeighin(val atMs: Long, val kg: Double, val note: String = "")

    @Serializable
    private data class StoredMeasurement(val atMs: Long, val kind: String, val cm: Double)

    @Serializable
    private data class Stored(
        val weighins: List<StoredWeighin> = emptyList(),
        val measurements: List<StoredMeasurement> = emptyList(),
    )

    private val prefsKey = stringPreferencesKey("body_json")
    private val mutex = Mutex()
    private var loaded: Stored? = null
    private var flushJob: Job? = null

    private val _weighins = MutableStateFlow<List<BodyTrend.Weighin>>(emptyList())

    /** Oldest first, which is the order [BodyTrend.estimate] and every chart wants. */
    val weighins: StateFlow<List<BodyTrend.Weighin>> = _weighins.asStateFlow()

    private val _measurements = MutableStateFlow<List<Measurement>>(emptyList())
    val measurements: StateFlow<List<Measurement>> = _measurements.asStateFlow()

    private suspend fun ensureLoaded(): Stored = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): Stored = loaded ?: run {
        val raw = context.bodyDataStore.data.first()[prefsKey]
        val s = raw
            ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            ?: Stored()
        loaded = s
        publish(s)
        s
    }

    private fun publish(s: Stored) {
        _weighins.value = s.weighins.sortedBy { it.atMs }.map { BodyTrend.Weighin(it.atMs, it.kg) }
        _measurements.value = s.measurements.sortedBy { it.atMs }.mapNotNull { m ->
            val kind = runCatching { MeasureKind.valueOf(m.kind) }.getOrNull() ?: return@mapNotNull null
            Measurement(m.atMs, kind, m.cm)
        }
    }

    // ------------------------------------------------------------------------------------ writing

    /**
     * Record a weigh-in.
     *
     * ⚠️ A second reading on the same *day* replaces the first rather than adding to it, keyed on the
     * day the caller says it belongs to. Weighing twice in a morning is a correction, not two data
     * points, and treating it as two lets somebody double the weight of one morning in the trend by
     * fiddling. Two readings genuinely hours apart on different days are both kept.
     */
    fun record(atMs: Long, kg: Double, dayStartMs: Long, note: String = "") {
        if (!kg.isFinite() || kg <= 0.0) return
        scope.launch {
            mutex.withLock {
                val s = loadLocked()
                val dayEnd = dayStartMs + DAY_MS
                val kept = s.weighins.filterNot { it.atMs in dayStartMs until dayEnd }
                val next = s.copy(weighins = (kept + StoredWeighin(atMs, kg, note)).sortedBy { it.atMs })
                loaded = next
                publish(next)
            }
            scheduleFlush()
        }
    }

    fun removeWeighin(atMs: Long) {
        scope.launch {
            mutex.withLock {
                val s = loadLocked()
                val next = s.copy(weighins = s.weighins.filterNot { it.atMs == atMs })
                loaded = next
                publish(next)
            }
            scheduleFlush()
        }
    }

    fun recordMeasurement(atMs: Long, kind: MeasureKind, cm: Double) {
        if (!cm.isFinite() || cm <= 0.0) return
        scope.launch {
            mutex.withLock {
                val s = loadLocked()
                val next = s.copy(measurements = (s.measurements + StoredMeasurement(atMs, kind.name, cm))
                    .sortedBy { it.atMs })
                loaded = next
                publish(next)
            }
            scheduleFlush()
        }
    }

    fun removeMeasurement(atMs: Long, kind: MeasureKind) {
        scope.launch {
            mutex.withLock {
                val s = loadLocked()
                val next = s.copy(measurements = s.measurements.filterNot { it.atMs == atMs && it.kind == kind.name })
                loaded = next
                publish(next)
            }
            scheduleFlush()
        }
    }

    // ------------------------------------------------------------------------------------ reading

    suspend fun all(): List<BodyTrend.Weighin> {
        ensureLoaded()
        return _weighins.value
    }

    /** The trend over everything recorded. The one place any part of the app should get it from. */
    suspend fun trend(): BodyTrend.Trend = BodyTrend.estimate(all())

    suspend fun allMeasurements(): List<Measurement> {
        ensureLoaded()
        return _measurements.value
    }

    /** The newest reading of each kind, which is what a measurements panel shows. */
    suspend fun latestMeasurements(): Map<MeasureKind, Measurement> =
        allMeasurements().groupBy { it.kind }.mapValues { (_, v) -> v.maxBy { it.atMs } }

    /** The note left with a weigh-in, if any — kept out of [weighins] so the trend sees only numbers. */
    suspend fun noteAt(atMs: Long): String {
        val s = ensureLoaded()
        return s.weighins.firstOrNull { it.atMs == atMs }?.note.orEmpty()
    }

    // ----------------------------------------------------------------------------------- lifecycle

    suspend fun clear() {
        // Cancel first, or a buffered write can resurrect the record after this returns.
        flushJob?.cancel()
        mutex.withLock {
            loaded = Stored()
            publish(Stored())
        }
        runCatching { context.bodyDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (app stop). */
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
            context.bodyDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
        const val DAY_MS = 86_400_000L
    }
}
