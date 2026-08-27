package dev.mascwa.pulse.data.cerebellum

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Cerebellum
import dev.mascwa.pulse.core.telemetry.CerebellumState
import dev.mascwa.pulse.core.telemetry.Prediction
import dev.mascwa.pulse.core.telemetry.Skill
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

private val Context.cerebellumDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_cerebellum")

/**
 * On-device persistence + wiring for J.A.R.V.I.S.'s virtual [Cerebellum]. Holds the learned skill state
 * in memory (authoritative), persists it with a debounced flush (a burst of actions = one write), and
 * exposes the cerebellar operations to the rest of the app.
 *
 * Learning is fed from the assistant's *motor actions* — its tool calls. [observeAction] learns two
 * things from each call: the action's own reliability (cue `"tool"`) and the sequence coordination
 * (cue `"after:<previous>"`), the way the cerebellum learns both a movement and how movements chain.
 * [observe] is the generic feed for higher-level cues (e.g. request signatures).
 *
 * Content-safe: cues/actions are tool names, transitions and normalized request signatures — never raw
 * user content. Stays on-device; wiped by "Reset learned reflexes" in Settings.
 */
class CerebellumStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredSkill(
        val cue: String,
        val action: String,
        val attempts: Int,
        val successes: Int,
        val reliability: Double,
        val lastEpochMs: Long,
    )

    @Serializable
    private data class Stored(val skills: List<StoredSkill> = emptyList())

    private val prefsKey = stringPreferencesKey("cerebellum_json")
    private val mutex = Mutex()
    private var state: CerebellumState? = null
    private var lastAction: String? = null
    private var flushJob: Job? = null

    private suspend fun ensureLoaded(): CerebellumState = mutex.withLock {
        state ?: run {
            val raw = context.cerebellumDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.skills.orEmpty()
                .map { Skill(it.cue, it.action, it.attempts, it.successes, it.reliability, it.lastEpochMs) }
            CerebellumState(loaded).also { state = it }
        }
    }

    /** Motor learning from one tool call: reliability of the action + the sequence transition into it. */
    fun observeAction(action: String, success: Boolean) {
        val a = action.trim()
        if (a.isEmpty()) return
        scope.launch {
            ensureLoaded()
            val now = System.currentTimeMillis()
            mutex.withLock {
                var s = state ?: CerebellumState.EMPTY
                s = Cerebellum.learn(s, cue = "tool", action = a, success = success, nowMs = now)
                lastAction?.let { prev ->
                    s = Cerebellum.learn(s, cue = "after:$prev", action = a, success = success, nowMs = now)
                }
                state = s
                lastAction = a
            }
            scheduleFlush()
        }
    }

    /** Generic procedural learning for a higher-level [cue] (e.g. a request signature). */
    fun observe(cue: String, action: String, success: Boolean) {
        val c = cue.trim()
        val a = action.trim()
        if (c.isEmpty() || a.isEmpty()) return
        scope.launch {
            ensureLoaded()
            mutex.withLock {
                state = Cerebellum.learn(state ?: CerebellumState.EMPTY, c, a, success, System.currentTimeMillis())
            }
            scheduleFlush()
        }
    }

    /** The practiced reflex for [cue], if any. */
    suspend fun recall(cue: String): Skill? = Cerebellum.recall(ensureLoaded(), cue)

    /** Forward-model + error-correction read for a contemplated ([cue], [action]). */
    suspend fun predict(cue: String, action: String): Prediction = Cerebellum.predict(ensureLoaded(), cue, action)

    /** A snapshot of the whole learned state (for the readout tool). */
    suspend fun snapshot(): CerebellumState = ensureLoaded()

    /** Forget all learned skills. */
    suspend fun clear() {
        flushJob?.cancel() // so a buffered write can't resurrect cleared skills after this returns
        mutex.withLock {
            state = CerebellumState.EMPTY
            lastAction = null
        }
        runCatching { context.cerebellumDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (e.g. on app stop). */
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

    /** Trailing throttle (see UsageRepository): one flush per window, never deferred indefinitely. */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock {
            state?.let { st -> Stored(st.skills.map { StoredSkill(it.cue, it.action, it.attempts, it.successes, it.reliability, it.lastEpochMs) }) }
        } ?: return
        lastWrite = runCatching {
            context.cerebellumDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
