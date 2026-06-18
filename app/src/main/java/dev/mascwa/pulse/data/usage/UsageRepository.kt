package dev.mascwa.pulse.data.usage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.FeatureUsage
import dev.mascwa.pulse.core.telemetry.UsageSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

private val Context.usageDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_usage")

/**
 * On-device, privacy-preserving usage store. Records only AGGREGATED signals — a per-feature visit
 * count, the last-seen time, and a 24-slot hour-of-day histogram. No screen content, no locations, no
 * PII; nothing ever leaves the device here.
 *
 * Performance: writes are coalesced. [record] is a cheap in-memory update; persistence is debounced
 * ([FLUSH_DELAY_MS]) so a burst of navigation produces a single DataStore write. The in-memory cache
 * also serves [snapshot] without touching disk after the first load.
 */
class UsageRepository(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class Stored(val features: Map<String, Feature> = emptyMap())

    @Serializable
    private data class Feature(
        val count: Int = 0,
        val lastEpochMs: Long = 0L,
        val hours: List<Int> = List(24) { 0 },
    )

    private val prefsKey = stringPreferencesKey("usage_json")
    private val mutex = Mutex()
    private var cache: MutableMap<String, Feature>? = null
    private var flushJob: Job? = null

    private suspend fun ensureLoaded(): MutableMap<String, Feature> = mutex.withLock {
        cache ?: run {
            val raw = context.usageDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it).features }.getOrNull() }
                ?: emptyMap()
            HashMap(loaded).also { cache = it }
        }
    }

    /** Note one visit/use of [event] (e.g. a nav route key). Cheap + fire-and-forget. */
    fun record(event: String) {
        val keyName = event.trim().lowercase()
        if (keyName.isBlank()) return
        scope.launch {
            val map = ensureLoaded()
            mutex.withLock {
                val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
                val cur = map[keyName] ?: Feature()
                val hours = (if (cur.hours.size == 24) cur.hours.toMutableList() else MutableList(24) { 0 })
                    .also { it[hour] = it[hour] + 1 }
                map[keyName] = cur.copy(
                    count = cur.count + 1,
                    lastEpochMs = System.currentTimeMillis(),
                    hours = hours,
                )
            }
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val toWrite = mutex.withLock { cache?.let { Stored(HashMap(it)) } } ?: return
        runCatching {
            context.usageDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), toWrite) }
        }
    }

    /** A point-in-time snapshot for the recommendation engine / assistant tool. */
    suspend fun snapshot(): UsageSnapshot {
        val map = ensureLoaded()
        val features = mutex.withLock {
            map.entries.map { (k, v) -> FeatureUsage(k, v.count, v.lastEpochMs, v.hours) }
        }
        return UsageSnapshot(
            features = features.sortedByDescending { it.count },
            totalEvents = features.sumOf { it.count },
        )
    }

    /** Forget everything recorded (the user's "reset" for usage data). */
    suspend fun clear() {
        mutex.withLock { cache = HashMap() }
        runCatching { context.usageDataStore.edit { it.remove(prefsKey) } }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
