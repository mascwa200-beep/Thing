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
 * On-device, privacy-preserving usage store. Two views, one store:
 *
 *  1. AGGREGATES — per-feature visit counts + a 24-slot hour-of-day histogram (for tailored
 *     recommendations); and
 *  2. a real-time ACTIVITY LOG — a capped, time-ordered ring buffer of recent app events (navigation,
 *     the assistant's tool calls, lifecycle), so J.A.R.V.I.S. can see what's been happening just now.
 *
 * Both record AGGREGATED / operational signals only — short category + label strings. No screen
 * content, no message text, no credentials, no precise location, no PII; and nothing ever leaves the
 * device here. That keeps "log everything about the app and usage" honest without turning the log into
 * a surveillance trail.
 *
 * Performance: [record] / [log] are cheap in-memory updates; persistence is debounced ([FLUSH_DELAY_MS])
 * and writes both views in a single DataStore edit, so a burst of activity is one disk write.
 */
class UsageRepository(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    // ---- aggregates ----
    @Serializable
    private data class Stored(val features: Map<String, Feature> = emptyMap())

    @Serializable
    private data class Feature(
        val count: Int = 0,
        val lastEpochMs: Long = 0L,
        val hours: List<Int> = List(24) { 0 },
    )

    // ---- activity log ----
    @Serializable
    private data class LogEntry(val t: Long, val cat: String, val label: String)

    @Serializable
    private data class StoredLog(val entries: List<LogEntry> = emptyList())

    /** One real-time activity event (newest-first when returned). */
    data class Event(val epochMs: Long, val category: String, val label: String)

    private val prefsKey = stringPreferencesKey("usage_json")
    private val logKey = stringPreferencesKey("activity_json")
    private val mutex = Mutex()
    private var cache: MutableMap<String, Feature>? = null
    private var logCache: ArrayDeque<LogEntry>? = null
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

    private suspend fun ensureLogLoaded(): ArrayDeque<LogEntry> = mutex.withLock {
        logCache ?: run {
            val raw = context.usageDataStore.data.first()[logKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(StoredLog.serializer(), it).entries }.getOrNull() }
                ?: emptyList()
            ArrayDeque(loaded.takeLast(LOG_CAP)).also { logCache = it }
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

    /** Append a real-time activity event. [label] is truncated and kept content-free by the caller. */
    fun log(category: String, label: String) {
        val cat = category.trim().lowercase().ifBlank { "event" }
        scope.launch {
            val buf = ensureLogLoaded()
            mutex.withLock {
                buf.addLast(LogEntry(System.currentTimeMillis(), cat, label.trim().take(LABEL_MAX)))
                while (buf.size > LOG_CAP) buf.removeFirst()
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
        val aggregate = mutex.withLock { cache?.let { Stored(HashMap(it)) } }
        val log = mutex.withLock { logCache?.let { StoredLog(it.toList()) } }
        if (aggregate == null && log == null) return
        runCatching {
            context.usageDataStore.edit { prefs ->
                if (aggregate != null) prefs[prefsKey] = json.encodeToString(Stored.serializer(), aggregate)
                if (log != null) prefs[logKey] = json.encodeToString(StoredLog.serializer(), log)
            }
        }
    }

    /** A point-in-time snapshot of aggregates for the recommendation engine / assistant tool. */
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

    /** The most recent [limit] activity events, newest first. */
    suspend fun recentActivity(limit: Int = 40): List<Event> {
        val buf = ensureLogLoaded()
        return mutex.withLock {
            buf.asReversed().take(limit).map { Event(it.t, it.cat, it.label) }
        }
    }

    /** Forget everything recorded — both aggregates and the activity log. */
    suspend fun clear() {
        mutex.withLock {
            cache = HashMap()
            logCache = ArrayDeque()
        }
        runCatching {
            context.usageDataStore.edit { it.remove(prefsKey); it.remove(logKey) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
        const val LOG_CAP = 300
        const val LABEL_MAX = 120
    }
}
