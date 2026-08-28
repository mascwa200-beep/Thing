package dev.mascwa.pulse.data.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Training
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

/**
 * ⚠️ Its own DataStore file, for the reason [RecipeStore] states beside it: `preferencesDataStore`
 * keeps one instance per name per process and **throws** on a second delegate for the same name, so
 * sharing means living in another feature's file. Sessions are written every time a set is logged;
 * they have no business re-encoding somebody's recipe book each time.
 */
private val Context.trainingDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_training")

/**
 * What was lifted, and the personal bests that outlive the sessions they were set in.
 *
 * Mirrors the ProfileStore template every store here follows — in-memory state is authoritative, a
 * [Mutex] serialises every read-modify-write, the disk write is debounced so a session that logs
 * twenty sets writes once, and [flushNow] runs from the host's `onStop` so a session in progress
 * survives the app being swiped away.
 *
 * ## Two things worth knowing before changing anything here
 *
 * ⚠️ **Sessions are capped and personal bests are NOT.** A single blob is right for this data —
 * tens of records that change a few times a week, not the thousands the food log carries — but only
 * because it is bounded. [MAX_SESSIONS] is roughly two years at four sessions a week; past that the
 * oldest are dropped. If a best lived only in its session, a lift set two years ago would silently
 * stop being your best the week it aged out, and the number would go DOWN with no explanation. So
 * [Best] is kept separately, updated on every save, and never evicted.
 *
 * ⚠️ **[StoredSession] is a flat mirror of [Training.Session] rather than the type itself.**
 * `:core:telemetry` is a plain Kotlin module carrying no kotlinx-serialization dependency, so a
 * `@Serializable` class cannot hold one of its types. `Food`, `SessionHours` and `StoredRecipe`
 * all solve this the same way. Conversion happens in this file and nowhere else.
 */
class TrainingStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    @Serializable
    private data class StoredSet(
        val reps: Int = 0,
        val loadKg: Double? = null,
        val rpe: Double? = null,
    )

    @Serializable
    private data class StoredMovement(
        val exerciseId: String = "",
        /**
         * The name AS IT WAS when the session was logged.
         *
         * ⚠️ Denormalised on purpose. Renaming a movement, or deleting one somebody added, must not
         * turn a year of history into rows labelled with an id — so the session carries what it was
         * called and the catalogue is consulted only for what it currently is.
         */
        val name: String = "",
        val pattern: String = "OTHER",
        val loaded: Boolean = true,
        val sets: List<StoredSet> = emptyList(),
    )

    @Serializable
    private data class StoredSession(
        val atMs: Long = 0L,
        val movements: List<StoredMovement> = emptyList(),
        val note: String = "",
    )

    /**
     * A movement somebody added themselves.
     *
     * ⚠️ Only the additions are stored. [Training.STARTER] is content that ships with the build, and
     * copying it into every install would mean a correction to a name never reaching anybody who had
     * already opened the screen once.
     */
    @Serializable
    private data class StoredExercise(
        val id: String = "",
        val name: String = "",
        val pattern: String = "OTHER",
        val loaded: Boolean = true,
    )

    /** The heaviest estimated maximum ever seen on one movement, and when. */
    @Serializable
    data class Best(
        val exerciseId: String = "",
        val name: String = "",
        val oneRepMaxKg: Double = 0.0,
        val reps: Int = 0,
        val loadKg: Double = 0.0,
        val atMs: Long = 0L,
    )

    @Serializable
    private data class Log(
        val sessions: List<StoredSession> = emptyList(),
        val added: List<StoredExercise> = emptyList(),
        val bests: List<Best> = emptyList(),
    )

    private val logKey = stringPreferencesKey("training")

    private val mutex = Mutex()

    /** Null until the first read; a loaded empty log is not the same as never having looked. */
    private var log: Log? = null
    private var dirty = false
    private var flushJob: Job? = null

    private val _sessions = MutableStateFlow<List<Training.Session>>(emptyList())

    /** Every retained session, most recent first. */
    val sessions: StateFlow<List<Training.Session>> = _sessions.asStateFlow()

    private val _exercises = MutableStateFlow(Training.STARTER)

    /** The catalogue plus anything added, with additions first because they are the personal ones. */
    val exercises: StateFlow<List<Training.Exercise>> = _exercises.asStateFlow()

    private val _bests = MutableStateFlow<List<Best>>(emptyList())

    /** Personal bests, heaviest first. Never evicted, unlike the sessions they came from. */
    val bests: StateFlow<List<Best>> = _bests.asStateFlow()

    /** Loads on first use. Call before reading the flows if a screen needs them populated. */
    suspend fun load(): List<Training.Session> = mutex.withLock { logLocked().sessions.map { it.domain() } }

    /**
     * Save a session, replacing any already recorded at the same instant.
     *
     * ⚠️ Upserts on `atMs`, which is what a session-in-progress needs: the editor saves after every
     * set, and without the upsert a workout would leave twenty near-identical records behind.
     */
    suspend fun save(session: Training.Session) {
        mutex.withLock {
            val current = logLocked()
            val kept = current.sessions.filter { it.atMs != session.atMs } + session.stored()
            val trimmed = kept.sortedByDescending { it.atMs }.take(MAX_SESSIONS)
            // ⚠️ Bests are updated from the session being saved, BEFORE the trim, so a record set in
            // a session that immediately ages out is still recorded.
            markLocked(current.copy(sessions = trimmed, bests = withBests(current.bests, session)))
        }
        scheduleFlush()
    }

    suspend fun remove(atMs: Long) {
        mutex.withLock {
            val current = logLocked()
            val kept = current.sessions.filter { it.atMs != atMs }
            if (kept.size == current.sessions.size) return@withLock
            // ⚠️ Bests are deliberately NOT recomputed. They are a record of what happened, and a
            // session deleted because it was typed wrong is far rarer than one deleted for tidiness
            // — recomputing would quietly erase a real lift, which is the worse of the two mistakes.
            markLocked(current.copy(sessions = kept))
        }
        scheduleFlush()
    }

    /** Add a movement the catalogue does not have. Upserts, so editing a name does not duplicate. */
    suspend fun addExercise(exercise: Training.Exercise) {
        mutex.withLock {
            val current = logLocked()
            val kept = current.added.filter { it.id != exercise.id } + exercise.stored()
            markLocked(current.copy(added = kept))
        }
        scheduleFlush()
    }

    suspend fun removeExercise(id: String) {
        mutex.withLock {
            val current = logLocked()
            val kept = current.added.filter { it.id != id }
            if (kept.size == current.added.size) return@withLock
            markLocked(current.copy(added = kept))
        }
        scheduleFlush()
    }

    suspend fun clear() {
        mutex.withLock {
            log = Log()
            dirty = false
            // ⚠️ Cancel the pending flush before wiping, or a write scheduled a moment ago restores
            // what was just deleted. Every store here carries this and it is the same reason.
            flushJob?.cancel()
            publishLocked(Log())
        }
        runCatching { context.trainingDataStore.edit { it.remove(logKey) } }
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

    // -------------------------------------------------------------------------------- internals

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
    private suspend fun logLocked(): Log {
        log?.let { return it }
        val loaded = withContext(Dispatchers.IO) {
            val raw = context.trainingDataStore.data.first()[logKey]
            raw?.let { runCatching { json.decodeFromString(Log.serializer(), it) }.getOrNull() } ?: Log()
        }
        log = loaded
        publishLocked(loaded)
        return loaded
    }

    private fun markLocked(next: Log) {
        log = next
        dirty = true
        publishLocked(next)
    }

    private fun publishLocked(l: Log) {
        _sessions.value = l.sessions.sortedByDescending { it.atMs }.map { it.domain() }
        _exercises.value = l.added.map { it.domain() } + Training.STARTER
        _bests.value = l.bests.sortedByDescending { it.oneRepMaxKg }
    }

    /**
     * Fold a session's estimates into the standing bests.
     *
     * ⚠️ Monotonic: a best is replaced only by a heavier one. Re-saving a session mid-workout must
     * not be able to take a record away, and neither should a light day.
     */
    private fun withBests(current: List<Best>, session: Training.Session): List<Best> {
        val out = current.associateBy { it.exerciseId }.toMutableMap()
        for (m in session.movements) {
            val top = m.sets
                .mapNotNull { s -> Training.estimateOneRepMax(s)?.let { it to s } }
                .maxByOrNull { it.first }
                ?: continue
            val (estimate, set) = top
            val existing = out[m.exercise.id]
            if (existing != null && existing.oneRepMaxKg >= estimate) continue
            out[m.exercise.id] = Best(
                exerciseId = m.exercise.id,
                name = m.exercise.name,
                oneRepMaxKg = estimate,
                reps = set.reps,
                loadKg = set.loadKg ?: 0.0,
                atMs = session.atMs,
            )
        }
        return out.values.toList()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    /**
     * ⚠️ On IO for the same reason [logLocked] is: the debounced path already launches on this store's
     * own IO scope, but [flushNow] is called from `onStop` through the container's flush-everything, and
     * that runs in an activity scope on the main thread. Encoding a whole store to JSON there is the
     * worst moment to do it — the system is timing how quickly the app backgrounds.
     */
    private suspend fun flush(): Unit = withContext(Dispatchers.IO) {
        val payload = mutex.withLock {
            if (!dirty) return@withContext
            val l = log ?: return@withContext
            // ⚠️ Cleared after the snapshot, not after the write. A failed write is retried by the
            // next change; a flag cleared before it would lose that change silently.
            dirty = false
            json.encodeToString(Log.serializer(), l)
        }
        lastWrite = runCatching { context.trainingDataStore.edit { it[logKey] = payload } }
    }

    // ------------------------------------------------------------------------------- conversion

    private fun StoredSession.domain() = Training.Session(
        atMs = atMs,
        movements = movements.map { it.domain() },
        note = note,
    )

    private fun StoredMovement.domain() = Training.Movement(
        exercise = Training.Exercise(exerciseId, name, pattern.toPattern(), loaded),
        sets = sets.map { Training.SetEntry(it.reps, it.loadKg, it.rpe) },
    )

    private fun StoredExercise.domain() = Training.Exercise(id, name, pattern.toPattern(), loaded)

    private fun Training.Session.stored() = StoredSession(
        atMs = atMs,
        movements = movements.map { m ->
            StoredMovement(
                exerciseId = m.exercise.id,
                name = m.exercise.name,
                pattern = m.exercise.pattern.name,
                loaded = m.exercise.loaded,
                sets = m.sets.map { StoredSet(it.reps, it.loadKg, it.rpe) },
            )
        },
        note = note,
    )

    private fun Training.Exercise.stored() = StoredExercise(id, name, pattern.name, loaded)

    /**
     * ⚠️ Patterns are stored as a **String** and read back defensively, for the reason the recipe
     * book's own `kind` states: an enum-valued serializer THROWS on a name it does not know, so
     * adding a pattern and then reading the file on an older build would make a whole training
     * history undecodable rather than merely puzzling. An unrecognised one falls back to OTHER.
     */
    private fun String.toPattern(): Training.Pattern =
        Training.Pattern.entries.firstOrNull { it.name == this } ?: Training.Pattern.OTHER

    companion object {
        private const val FLUSH_DELAY_MS = 2_000L

        /** Roughly two years at four sessions a week. See the note on bests never being evicted. */
        const val MAX_SESSIONS = 400
    }
}
