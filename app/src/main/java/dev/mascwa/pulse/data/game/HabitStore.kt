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
import dev.mascwa.pulse.core.telemetry.SelfCareStreak
import dev.mascwa.pulse.core.telemetry.Streak
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
 *
 * It also keeps a per-habit **[Streak]** — confirming a habit on consecutive real days builds it, a missed day
 * breaks it ([SelfCareStreak]) — and pays out a small caps reward each time a streak crosses a milestone, so
 * keeping your real routine is rewarded in the game.
 */
class HabitStore(
    private val context: Context,
    private val json: Json,
    private val evidence: ActivityEvidenceStore,
    private val game: SpecialGameStore,
    private val settings: dev.mascwa.pulse.data.settings.SettingsRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredState(val activity: String, val lastConfirmedMs: Long, val lastAskedMs: Long)

    @Serializable
    private data class StoredStreak(val activity: String, val current: Int, val longest: Int, val lastDay: Int)

    @Serializable
    private data class Stored(
        val states: List<StoredState> = emptyList(),
        val streaks: List<StoredStreak> = emptyList(),
    )

    private val prefsKey = stringPreferencesKey("habits_json")
    private val mutex = Mutex()
    private var states: MutableMap<String, HabitState>? = null
    private var streaks: MutableMap<String, Streak>? = null
    private var flushJob: Job? = null

    val habits: List<Habit> = HabitCheckin.DEFAULTS

    private val _due = MutableStateFlow<Habit?>(null)
    /** The single most-overdue habit to surface a check-in for right now, or null. */
    val due: StateFlow<Habit?> = _due.asStateFlow()

    private val _streaks = MutableStateFlow<Map<String, Streak>>(emptyMap())
    /** Per-habit consecutive-day streaks (keyed by [RealActivity] name) for the UI + the `selfcare` tool. */
    val streaksFlow: StateFlow<Map<String, Streak>> = _streaks.asStateFlow()

    /**
     * The habits actually in play right now, honouring the owner's granular switches: an empty list if the
     * self-care check-in system is switched off entirely, else [HabitCheckin.DEFAULTS] minus any the owner
     * turned off individually ([NotificationPrefs.disabledHabits]). Read fresh so toggles apply at once.
     */
    private suspend fun activeHabits(): List<Habit> {
        val prefs = runCatching { settings.current().notifications }.getOrNull() ?: return habits
        if (!prefs.selfCareCheckins) return emptyList()
        if (prefs.disabledHabits.isEmpty()) return habits
        val off = prefs.disabledHabits.toSet()
        return habits.filter { it.activity.name !in off }
    }

    /** Recompute which habit is due (call on the game screen opening + after an answer). */
    fun refresh() {
        scope.launch {
            val s = ensureLoaded()
            _due.value = HabitCheckin.due(activeHabits(), s, clock()).firstOrNull()
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
        var milestone = 0
        mutex.withLock {
            val map = states ?: mutableMapOf<String, HabitState>().also { states = it }
            val prev = map[habit.activity.name] ?: HabitState()
            map[habit.activity.name] = prev.copy(
                lastAskedMs = now,
                lastConfirmedMs = if (HabitCheckin.confirms(outcome)) now else prev.lastConfirmedMs,
            )
            // A genuine completion also advances the consecutive-day streak (caught lies don't).
            if (HabitCheckin.confirms(outcome)) {
                val sMap = streaks ?: mutableMapOf<String, Streak>().also { streaks = it }
                val before = sMap[habit.activity.name] ?: Streak()
                val after = SelfCareStreak.record(before, localDay(now))
                sMap[habit.activity.name] = after
                _streaks.value = sMap.toMap()
                if (after.current > before.current && after.current in STREAK_MILESTONES) milestone = after.current
            }
        }
        if (HabitCheckin.topsUpNeed(outcome)) topUp(habit.activity.satisfies)
        // Pay out for reaching a streak milestone — keeping the real routine earns caps + a little XP.
        if (milestone > 0) game.awardQuest(caps = milestone * 3, xp = milestone)
        _due.value = HabitCheckin.due(activeHabits(), states ?: emptyMap(), now).firstOrNull()
        scheduleFlush()
        return outcome
    }

    /** The single most-overdue habit right now (for the background worker to fire an aggressive check-in). */
    suspend fun nextDue(): Habit? = HabitCheckin.due(activeHabits(), ensureLoaded(), clock()).firstOrNull()

    /** Stamp a habit as ASKED (without answering) so a fired check-in doesn't re-fire until the ask-gap. */
    suspend fun markAsked(habit: Habit) {
        ensureLoaded()
        mutex.withLock {
            val map = states ?: mutableMapOf<String, HabitState>().also { states = it }
            val prev = map[habit.activity.name] ?: HabitState()
            map[habit.activity.name] = prev.copy(lastAskedMs = clock())
        }
        scheduleFlush()
    }

    /** A snapshot of the current streaks (loads if needed) — for the `selfcare` tool. */
    suspend fun streaks(): Map<String, Streak> { ensureLoaded(); return streaks?.toMap() ?: emptyMap() }

    /** A one-line readout of the best live streak ("4-day self-care streak · best 9"), or "" if none. */
    suspend fun describeStreak(): String =
        SelfCareStreak.describe(streaks().values, localDay(clock()))

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
        mutex.withLock { states = mutableMapOf(); streaks = mutableMapOf() }
        _due.value = null
        _streaks.value = emptyMap()
        runCatching { context.habitDataStore.edit { it.remove(prefsKey) } }
    }

    suspend fun flushNow() { flushJob?.cancel(); flush() }

    private suspend fun ensureLoaded(): Map<String, HabitState> = mutex.withLock {
        states ?: run {
            val raw = context.habitDataStore.data.first()[prefsKey]
            val stored = raw?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            val loaded = stored?.states.orEmpty()
                .associate { it.activity to HabitState(it.lastConfirmedMs, it.lastAskedMs) }
                .toMutableMap()
            val loadedStreaks = stored?.streaks.orEmpty()
                .associate { it.activity to Streak(it.current, it.longest, it.lastDay) }
                .toMutableMap()
            streaks = loadedStreaks
            _streaks.value = loadedStreaks.toMap()
            loaded.also { states = it }
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch { delay(FLUSH_DELAY_MS); flush() }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock {
            val s = states ?: return@withLock null
            Stored(
                states = s.map { (k, v) -> StoredState(k, v.lastConfirmedMs, v.lastAskedMs) },
                streaks = (streaks ?: emptyMap()).map { (k, v) -> StoredStreak(k, v.current, v.longest, v.lastDay) },
            )
        } ?: return
        runCatching {
            context.habitDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    /** Local epoch-day (calendar day in the device's timezone) for the consecutive-day streak logic. */
    private fun localDay(nowMs: Long): Int {
        val offset = java.util.TimeZone.getDefault().getOffset(nowMs)
        return ((nowMs + offset) / 86_400_000L).toInt()
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
        /** Streak lengths that pay out a caps + XP reward on the day they're reached. */
        val STREAK_MILESTONES = setOf(3, 7, 14, 30, 60, 100)
    }
}
