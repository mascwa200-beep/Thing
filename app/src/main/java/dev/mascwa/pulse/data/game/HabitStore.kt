package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.CareNeed
import dev.mascwa.pulse.core.telemetry.CheckinOutcome
import dev.mascwa.pulse.core.telemetry.Habit
import dev.mascwa.pulse.core.telemetry.HabitCheckin
import dev.mascwa.pulse.core.telemetry.HabitState
import dev.mascwa.pulse.data.perception.ActivityEvidenceStore
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

private val Context.habitDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_habits")

/**
 * The habit check-in loop, on-device: tracks per-habit [HabitState] (last confirmed / last asked), decides
 * which habit is due to ask about ([HabitCheckin.due]), and on the user's answer cross-references the
 * [ActivityEvidenceStore] history — the moment "yeah I showered" gets caught when the sensors never heard the
 * water. A truthful/provisional completion tops up the matching real-life need (via [SpecialGameStore]) and
 * stamps the habit done; a caught lie tops up nothing. Persists like ProfileStore (Mutex + debounced flush).
 * No device lockout — sense + check-in only.
 */
class HabitStore(
    private val context: Context,
    private val json: Json,
    private val evidence: ActivityEvidenceStore,
    private val game: SpecialGameStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredState(val activity: String, val lastConfirmedMs: Long, val lastAskedMs: Long)

    @Serializable
    private data class Stored(val states: List<StoredState> = emptyList())

    private val prefsKey = stringPreferencesKey("habits_json")
    private val mutex = Mutex()
    private var states: MutableMap<String, HabitState>? = null
    private var flushJob: Job? = null

    val habits: List<Habit> = HabitCheckin.DEFAULTS

    private val _due = MutableStateFlow<Habit?>(null)
    /** The single most-overdue habit to surface a check-in for right now, or null. */
    val due: StateFlow<Habit?> = _due.asStateFlow()

    /** Recompute which habit is due (call on the game screen opening + after an answer). */
    fun refresh() {
        scope.launch {
            val s = ensureLoaded()
            _due.value = HabitCheckin.due(habits, s, clock()).firstOrNull()
        }
    }

    /**
     * Record the user's answer to [habit]'s check-in. [claimedDone] = they say they did it. Cross-references
     * the evidence log over the habit's cadence window; on CONFIRMED/UNVERIFIED tops up the matching need and
     * stamps the habit done, on CAUGHT_LIE tops up nothing. Always advances lastAsked. Returns the outcome so
     * the UI can react (confirm / call out the lie / nudge).
     */
    suspend fun answer(habit: Habit, claimedDone: Boolean): CheckinOutcome {
        ensureLoaded()
        val now = clock()
        val sinceMs = now - habit.everyMs
        val ev = evidence.recent(sinceMs)
        val outcome = HabitCheckin.resolve(habit, claimedDone, ev, sinceMs)
        mutex.withLock {
            val map = states ?: mutableMapOf<String, HabitState>().also { states = it }
            val prev = map[habit.activity.name] ?: HabitState()
            map[habit.activity.name] = prev.copy(
                lastAskedMs = now,
                lastConfirmedMs = if (HabitCheckin.confirms(outcome)) now else prev.lastConfirmedMs,
            )
        }
        if (HabitCheckin.topsUpNeed(outcome)) topUp(habit.activity.satisfies)
        _due.value = HabitCheckin.due(habits, states ?: emptyMap(), now).firstOrNull()
        scheduleFlush()
        return outcome
    }

    private fun topUp(need: CareNeed) {
        when (need) {
            CareNeed.HYGIENE -> game.wash()
            CareNeed.NOURISHMENT -> game.eat()
            CareNeed.HYDRATION -> game.drink()
            CareNeed.REST -> game.rest()
            CareNeed.NONE -> Unit
        }
    }

    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock { states = mutableMapOf() }
        _due.value = null
        runCatching { context.habitDataStore.edit { it.remove(prefsKey) } }
    }

    suspend fun flushNow() { flushJob?.cancel(); flush() }

    private suspend fun ensureLoaded(): Map<String, HabitState> = mutex.withLock {
        states ?: run {
            val raw = context.habitDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.states.orEmpty()
                .associate { it.activity to HabitState(it.lastConfirmedMs, it.lastAskedMs) }
                .toMutableMap()
            loaded.also { states = it }
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch { delay(FLUSH_DELAY_MS); flush() }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock {
            states?.let { m -> Stored(m.map { (k, v) -> StoredState(k, v.lastConfirmedMs, v.lastAskedMs) }) }
        } ?: return
        runCatching {
            context.habitDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
