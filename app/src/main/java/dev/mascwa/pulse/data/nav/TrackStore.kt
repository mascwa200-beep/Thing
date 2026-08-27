package dev.mascwa.pulse.data.nav

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.TrackLog
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

private val Context.trackDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_track")

/**
 * The breadcrumb trail behind you on the NAV map.
 *
 * The app's own feature catalogue has described this map as having "a trail" since it was written,
 * and there has never been one. This is it.
 *
 * Same shape as the other on-device stores: in-memory state is authoritative, guarded by a mutex,
 * flushed on a trailing delay so a walk does not become one disk write per GPS fix. Which fixes
 * are worth keeping is [TrackLog]'s decision, tested separately — the store only persists them.
 *
 * Entirely on-device. A recorded track is a detailed account of where somebody has been, so it is
 * never transmitted anywhere, and clearing it removes it outright.
 */
class TrackStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredPoint(
        val lat: Double,
        val lon: Double,
        val at: Long,
        val alt: Double? = null,
    )

    @Serializable
    private data class Stored(val points: List<StoredPoint> = emptyList())

    private val pointsKey = stringPreferencesKey("track_json")
    private val recordingKey = booleanPreferencesKey("track_recording")
    private val mutex = Mutex()
    private var points: List<TrackLog.TrackPoint>? = null
    private var flushJob: Job? = null

    private val _pointsFlow = MutableStateFlow<List<TrackLog.TrackPoint>>(emptyList())
    val pointsFlow: StateFlow<List<TrackLog.TrackPoint>> = _pointsFlow.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private suspend fun ensureLoaded(): List<TrackLog.TrackPoint> = mutex.withLock {
        points ?: run {
            val prefs = context.trackDataStore.data.first()
            val loaded = prefs[pointsKey]
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.points.orEmpty()
                .map { TrackLog.TrackPoint(it.lat, it.lon, it.at, it.alt) }
            _recording.value = prefs[recordingKey] ?: false
            loaded.also { points = it; _pointsFlow.value = it }
        }
    }

    /** Load whatever is on disk so the trail is drawn as soon as the map opens. */
    fun prime() {
        scope.launch { ensureLoaded() }
    }

    /**
     * Offer a fix to the track. Ignored unless recording, and then only if [TrackLog] accepts it.
     *
     * Returning silently on a rejected fix is the normal case, not an error — most fixes from a
     * stationary phone are rejected, which is the entire point.
     */
    fun record(lat: Double, lon: Double, atMs: Long, altitudeM: Double?, accuracyM: Double?) {
        if (!_recording.value) return
        scope.launch {
            ensureLoaded()
            val candidate = TrackLog.TrackPoint(lat, lon, atMs, altitudeM)
            var appended = false
            mutex.withLock {
                val current = points ?: emptyList()
                if (TrackLog.accept(current.lastOrNull(), candidate, accuracyM)) {
                    points = TrackLog.capped(current + candidate, MAX_POINTS)
                    _pointsFlow.value = points ?: emptyList()
                    appended = true
                }
            }
            if (appended) scheduleFlush()
        }
    }

    fun setRecording(on: Boolean) {
        _recording.value = on
        scope.launch {
            ensureLoaded()
            runCatching { context.trackDataStore.edit { it[recordingKey] = on } }
            // Stopping is the moment somebody expects the track to be safe, so do not leave it
            // sitting in the flush window.
            //
            // ⚠️ **Caught here, and it has to be.** [flushNow] now rethrows a failed write so the
            // reporters that wrap it at app-stop can finally see one; this call site is inside a
            // launched coroutine with nobody above it, where an escaping exception reaches the
            // thread's default handler and takes the process down. A SupervisorJob does not help —
            // it stops siblings being cancelled, it does not swallow.
            if (!on) runCatching { flushNow() }
        }
    }

    suspend fun clear() {
        flushJob?.cancel() // a buffered write must not resurrect a track that was just deleted
        mutex.withLock {
            points = emptyList()
            _pointsFlow.value = emptyList()
        }
        runCatching { context.trackDataStore.edit { it.remove(pointsKey) } }
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

    private suspend fun flush() {
        val snapshot = mutex.withLock { points } ?: return
        val blob = runCatching {
            json.encodeToString(
                Stored.serializer(),
                Stored(snapshot.map { StoredPoint(it.latitudeDeg, it.longitudeDeg, it.epochMs, it.altitudeM) }),
            )
        }.getOrNull() ?: return
        lastWrite = runCatching { context.trackDataStore.edit { it[pointsKey] = blob } }
    }

    private companion object {
        /** Long enough for a full day out at walking pace, short enough to stay cheap to draw. */
        const val MAX_POINTS = 4000
        const val FLUSH_DELAY_MS = 4000L
    }
}
