package dev.mascwa.pulse.data.sensing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.BaselineCell
import dev.mascwa.pulse.core.telemetry.BaselineState
import dev.mascwa.pulse.core.telemetry.EventSeverity
import dev.mascwa.pulse.core.telemetry.SenseEvent
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

private val Context.sensoriumDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_sensorium")

/**
 * Persistence for the Sensorium: the learned [BaselineState] (weeks of accumulated normality — the
 * one thing that must survive restarts, or the anomaly engine restarts life amnesiac) and the last
 * ~48 h of [SenseEvent]s (the scanner's event log). House store pattern (ProfileStore et al.):
 * in-memory authoritative + Mutex + debounced flush; clear cancels a pending flush so a buffered
 * write can't resurrect cleared data. The `@Serializable` DTOs live HERE — core:telemetry stays
 * serialization-free by design.
 */
class SensoriumStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredCell(
        val nm: Float = 0f, val nd: Float = 0f, val lm: Float = 0f, val ld: Float = 0f,
        val mm: Float = 0f, val md: Float = 0f, val cm: Float = 0f, val cd: Float = 0f,
        val n: Int = 0,
    )

    @Serializable
    data class StoredEvent(
        val key: String, val title: String, val detail: String,
        val severity: String, val atMs: Long,
    )

    @Serializable
    private data class Stored(
        val cells: Map<Int, StoredCell> = emptyMap(),
        val events: List<StoredEvent> = emptyList(),
    )

    private val prefsKey = stringPreferencesKey("sensorium_json")
    private val mutex = Mutex()
    private var baseline: BaselineState? = null
    private var events: MutableList<StoredEvent> = mutableListOf()
    private var flushJob: Job? = null

    private val _eventsFlow = MutableStateFlow<List<StoredEvent>>(emptyList())
    /** Newest-first event log for the scanner screen. */
    val eventsFlow: StateFlow<List<StoredEvent>> = _eventsFlow.asStateFlow()

    private suspend fun ensureLoaded() {
        if (baseline != null) return
        val raw = context.sensoriumDataStore.data.first()[prefsKey]
        val stored = raw?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            ?: Stored()
        baseline = BaselineState(
            stored.cells.mapValues { (_, c) ->
                BaselineCell(c.nm, c.nd, c.lm, c.ld, c.mm, c.md, c.cm, c.cd, c.n)
            },
        )
        events = stored.events.toMutableList()
        _eventsFlow.value = events.sortedByDescending { it.atMs }
    }

    suspend fun baseline(): BaselineState = mutex.withLock { ensureLoaded(); baseline!! }

    suspend fun updateBaseline(state: BaselineState) = mutex.withLock {
        ensureLoaded()
        baseline = state
        scheduleFlush()
    }

    suspend fun recordEvent(event: SenseEvent, nowMs: Long) = mutex.withLock {
        ensureLoaded()
        events += StoredEvent(event.key, event.title, event.detail, event.severity.name, nowMs)
        val cutoff = nowMs - EVENT_KEEP_MS
        events.retainAll { it.atMs >= cutoff }
        if (events.size > EVENT_CAP) {
            events = events.sortedByDescending { it.atMs }.take(EVENT_CAP).toMutableList()
        }
        _eventsFlow.value = events.sortedByDescending { it.atMs }
        scheduleFlush()
    }

    suspend fun clear() = mutex.withLock {
        flushJob?.cancel()
        baseline = BaselineState()
        events = mutableListOf()
        _eventsFlow.value = emptyList()
        runCatching { context.sensoriumDataStore.edit { it.remove(prefsKey) } }
    }

    suspend fun flushNow() = flush()

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock {
            val b = baseline ?: return
            Stored(
                cells = b.cells.mapValues { (_, c) ->
                    StoredCell(
                        c.noiseMean, c.noiseDev, c.lightMean, c.lightDev,
                        c.motionMean, c.motionDev, c.crowdMean, c.crowdDev, c.samples,
                    )
                },
                events = events.toList(),
            )
        }
        runCatching {
            context.sensoriumDataStore.edit {
                it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot)
            }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 5_000L
        const val EVENT_KEEP_MS = 48 * 60 * 60 * 1000L
        const val EVENT_CAP = 200
    }
}

/** Alert-severity ordering helper for the scanner (LOG < NOTABLE < ALERT). */
fun SensoriumStore.StoredEvent.severityRank(): Int =
    runCatching { EventSeverity.valueOf(severity).ordinal }.getOrDefault(0)
