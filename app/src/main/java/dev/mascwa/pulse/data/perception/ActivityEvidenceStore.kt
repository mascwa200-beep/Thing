package dev.mascwa.pulse.data.perception

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.ActivityEvidence
import dev.mascwa.pulse.core.telemetry.ActivitySensing
import dev.mascwa.pulse.core.telemetry.PerceptLabel
import dev.mascwa.pulse.core.telemetry.RealActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

private val Context.activityEvidenceDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_activity_evidence")

/**
 * Runs the [ActivitySensing] detector over the live on-device perception — the mic sampler's sound labels +
 * the camera sampler's scene labels — and keeps a persisted, queryable history of what it ACTUALLY sensed.
 * This is the "sensor history" the habit check-in reads to catch a lie ("you say you showered, but I never
 * heard the water"). Mirrors ProfileStore: an in-memory ring (authoritative) + Mutex + debounced flush.
 *
 * Privacy: it only ever stores TEXT evidence (activity + confidence + time), never audio or pixels — the
 * samplers already discard the raw signal and hand over labels.
 *
 * [start]/[stop] gate the sampling loop; it runs alongside the perception samplers, which for now are only
 * live while a game screen is open (the always-on foreground service is a later slice).
 */
class ActivityEvidenceStore(
    private val context: Context,
    private val json: Json,
    private val soundLabels: StateFlow<List<PerceptLabel>>,
    private val sceneLabels: StateFlow<List<PerceptLabel>>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    @Serializable
    private data class Stored(val evidence: List<StoredEvidence> = emptyList())

    @Serializable
    private data class StoredEvidence(val activity: String, val confidence: Float, val atMs: Long)

    private val prefsKey = stringPreferencesKey("activity_evidence_json")
    private val mutex = Mutex()
    private var evidence: MutableList<ActivityEvidence>? = null
    private var flushJob: Job? = null
    private var sampleJob: Job? = null

    // A shower is sustained running water; a tap is brief — track how long water has been heard continuously.
    private var waterSinceMs = 0L
    // Throttle per activity so one long shower doesn't log dozens of identical events.
    private val lastRecorded = HashMap<RealActivity, Long>()

    private val _evidenceFlow = MutableStateFlow<List<ActivityEvidence>>(emptyList())
    /** The live history (newest last), for the check-in and a future Memory-screen surface. */
    val evidenceFlow: StateFlow<List<ActivityEvidence>> = _evidenceFlow.asStateFlow()

    /** Begin sensing: sample the perception flows on a cadence and record what's detected. Idempotent. */
    fun start() {
        if (sampleJob?.isActive == true) return
        sampleJob = scope.launch {
            ensureLoaded()
            while (isActive) {
                runCatching { sample() }
                delay(POLL_MS)
            }
        }
    }

    /** Stop sensing (releases nothing itself — the samplers own the mic/camera). */
    fun stop() {
        sampleJob?.cancel(); sampleJob = null
        waterSinceMs = 0L
    }

    private suspend fun sample() {
        val now = clock()
        val sounds = soundLabels.value
        val scenes = sceneLabels.value
        if (sounds.isEmpty() && scenes.isEmpty()) return // samplers idle → nothing to sense
        val waterNow = sounds.any { l -> WATER_HINTS.any { l.label.lowercase().contains(it) } }
        waterSinceMs = if (waterNow) (if (waterSinceMs == 0L) now else waterSinceMs) else 0L
        val waterMs = if (waterSinceMs == 0L) 0L else now - waterSinceMs

        val found = ActivitySensing.detect(sounds, scenes, waterMs, atHome = false, hourOfDay = hourOfDay(now), nowMs = now)
        for (ev in found) {
            if (ev.confidence < RECORD_FLOOR) continue
            val last = lastRecorded[ev.activity] ?: 0L
            if (now - last < RECORD_GAP_MS) continue
            lastRecorded[ev.activity] = now
            record(ev)
        }
    }

    private suspend fun record(ev: ActivityEvidence) {
        mutex.withLock {
            val list = evidence ?: mutableListOf<ActivityEvidence>().also { evidence = it }
            list.add(ev)
            while (list.size > MAX_EVIDENCE) list.removeAt(0)
            _evidenceFlow.value = list.toList()
        }
        scheduleFlush()
    }

    /** Evidence recorded at/after [sinceMs] (newest last) — what the check-in queries to verify a claim. */
    suspend fun recent(sinceMs: Long): List<ActivityEvidence> = ensureLoaded().filter { it.atMs >= sinceMs }

    /** A defensive copy of the whole history. */
    suspend fun all(): List<ActivityEvidence> = ensureLoaded().toList()

    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock { evidence = mutableListOf(); _evidenceFlow.value = emptyList() }
        lastRecorded.clear()
        runCatching { context.activityEvidenceDataStore.edit { it.remove(prefsKey) } }
    }

    suspend fun flushNow() { flushJob?.cancel(); flush() }

    private suspend fun ensureLoaded(): List<ActivityEvidence> = mutex.withLock {
        evidence ?: run {
            val raw = context.activityEvidenceDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.evidence.orEmpty()
                .mapNotNull { it.domain() }
                .toMutableList()
            loaded.also { evidence = it; _evidenceFlow.value = it.toList() }
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch { delay(FLUSH_DELAY_MS); flush() }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { evidence?.let { list -> Stored(list.map { it.stored() }) } } ?: return
        runCatching {
            context.activityEvidenceDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private fun ActivityEvidence.stored() = StoredEvidence(activity.name, confidence, atMs)
    private fun StoredEvidence.domain(): ActivityEvidence? {
        val act = runCatching { RealActivity.valueOf(activity) }.getOrNull() ?: return null
        return ActivityEvidence(act, confidence, atMs)
    }

    private fun hourOfDay(now: Long): Int =
        Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

    private companion object {
        const val POLL_MS = 15_000L
        const val FLUSH_DELAY_MS = 2_000L
        const val RECORD_FLOOR = 0.3f          // ignore near-noise detections
        const val RECORD_GAP_MS = 5 * 60_000L  // one entry per activity per 5 min (a shower isn't 40 events)
        const val MAX_EVIDENCE = 400           // capped ring — days of self-care evidence
        val WATER_HINTS = listOf("water", "shower", "bathtub", "faucet", "tap", "sink")
    }
}
