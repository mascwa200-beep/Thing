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
            HashMap(foldLegacyKeys(loaded)).also { cache = it }
        }
    }

    /**
     * One-time repair of the pattern-key bug: visits used to be recorded under the raw route
     * PATTERN ("survival?guide={guide}"), minting junk keys that never matched a real feature.
     * Each `?`-bearing key's history merges into its base route — counts add, hour histograms add
     * element-wise, the newer last-visit wins — so nothing a user did is thrown away.
     */
    private fun foldLegacyKeys(loaded: Map<String, Feature>): Map<String, Feature> {
        if (loaded.keys.none { '?' in it }) return loaded
        val folded = HashMap<String, Feature>()
        for ((key, f) in loaded) {
            val base = key.substringBefore('?')
            val cur = folded[base]
            folded[base] = if (cur == null) f else Feature(
                count = cur.count + f.count,
                lastEpochMs = maxOf(cur.lastEpochMs, f.lastEpochMs),
                hours = List(24) { i ->
                    (cur.hours.getOrElse(i) { 0 }) + (f.hours.getOrElse(i) { 0 })
                },
            )
        }
        return folded
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

    /**
     * Append a real-time activity event. [label] may contain user content (when detailed logging is on);
     * raw credentials are always scrubbed here as a last line of defence, then it's truncated.
     */
    fun log(category: String, label: String) {
        val cat = category.trim().lowercase().ifBlank { "event" }
        scope.launch {
            val buf = ensureLogLoaded()
            mutex.withLock {
                buf.addLast(LogEntry(System.currentTimeMillis(), cat, scrubSecrets(label.trim()).take(LABEL_MAX)))
                while (buf.size > LOG_CAP) buf.removeFirst()
            }
            scheduleFlush()
        }
    }

    /** Mask anything that looks like an API key / token, so a credential is never persisted even when
     *  full-content logging is on. Targeted to known key shapes to avoid mangling ordinary text. */
    private fun scrubSecrets(s: String): String {
        var out = s
        for (p in SECRET_PATTERNS) out = p.replace(out, "[redacted]")
        return out
    }

    /** Trailing throttle: arm one flush per window. Unlike cancel-and-reschedule, a sustained burst
     *  still flushes every [FLUSH_DELAY_MS] instead of being deferred indefinitely (which would lose
     *  the whole burst on a process kill). */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    /** Force buffered changes to disk now (e.g. on app stop). */
    suspend fun flushNow() {
        flushJob?.cancel()
        flush()
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
        flushJob?.cancel() // so a buffered write can't resurrect cleared data after this returns
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
        const val LABEL_MAX = 200

        /** Known API-key / token shapes (OpenAI/OpenRouter, GitHub, Google, Slack) + Bearer headers.
         *  Kept in step with [dev.mascwa.pulse.core.util.SecretScrub.PATTERNS] (hyphenated sk- keys, PATs). */
        val SECRET_PATTERNS = listOf(
            Regex("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{10,}"),
            Regex("\\bsk-[A-Za-z0-9\\-]{12,}"),
            Regex("\\bgh[pousr]_[A-Za-z0-9]{16,}"),
            Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}"),
            Regex("\\bAIza[0-9A-Za-z_\\-]{20,}"),
            Regex("\\bxox[baprs]-[A-Za-z0-9\\-]{10,}"),
        )
    }
}
