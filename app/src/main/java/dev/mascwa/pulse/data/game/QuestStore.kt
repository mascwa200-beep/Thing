package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.ActiveQuest
import dev.mascwa.pulse.core.telemetry.Quest
import dev.mascwa.pulse.core.telemetry.QuestGoal
import dev.mascwa.pulse.core.telemetry.QuestKind
import dev.mascwa.pulse.core.telemetry.QuestLog
import dev.mascwa.pulse.core.telemetry.QuestLogState
import dev.mascwa.pulse.core.telemetry.QuestMetrics
import dev.mascwa.pulse.core.telemetry.QuestView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.questDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_quests")

/**
 * On-device persistence + lifecycle for the personalised quests. Holds the authoritative [QuestLogState]
 * (mirrors ProfileStore: in-memory + Mutex + debounced flush). The ViewModel drives [sync] with the freshly
 * composed quests + a live [QuestMetrics] snapshot; this store retires completions (emitting them on
 * [completed] so the caller can grant the reward), tops up the active set, persists, and publishes the
 * rendered [quests] for the UI. Everything the log does is the pure, CI-tested [QuestLog]. On-device only.
 */
class QuestStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredQuest(
        val id: String, val title: String, val brief: String, val goal: String, val target: Int,
        val kind: String, val source: String, val rewardCaps: Int, val rewardXp: Int,
        val baseDistanceM: Int = 0, val baseWins: Int = 0, val baseVentures: Int = 0,
        val basePlaces: Int = 0, val issuedDay: Int = 1,
        // The VISIT_KIND target kind — defaulted → old saves reload as legacy (any-place) VISIT_KIND quests.
        val targetKey: String? = null,
    )

    @Serializable
    private data class Stored(val active: List<StoredQuest> = emptyList(), val completedIds: List<String> = emptyList())

    private val prefsKey = stringPreferencesKey("quests_json")
    private val mutex = Mutex()
    private var loaded = false
    private var flushJob: Job? = null

    private var state = QuestLogState()

    private val _quests = MutableStateFlow<List<QuestView>>(emptyList())
    /** The active quests rendered with live progress + completion, for the QUESTS panel. */
    val quests: StateFlow<List<QuestView>> = _quests.asStateFlow()

    private val _completed = MutableSharedFlow<Quest>(extraBufferCapacity = 16)
    /** Emits each quest at the moment it completes — the caller grants its reward. */
    val completed: SharedFlow<Quest> = _completed

    private fun ActiveQuest.stored() = StoredQuest(
        id = quest.id, title = quest.title, brief = quest.brief, goal = quest.goal.name, target = quest.target,
        kind = quest.kind.name, source = quest.source, rewardCaps = quest.rewardCaps, rewardXp = quest.rewardXp,
        baseDistanceM = baseDistanceM, baseWins = baseWins, baseVentures = baseVentures,
        basePlaces = basePlaces, issuedDay = issuedDay, targetKey = quest.targetKey,
    )

    private fun StoredQuest.domain(): ActiveQuest? {
        val goalE = runCatching { QuestGoal.valueOf(goal) }.getOrNull() ?: return null
        val kindE = runCatching { QuestKind.valueOf(kind) }.getOrNull() ?: QuestKind.SIDE
        return ActiveQuest(
            quest = Quest(id, title, brief, goalE, target, kindE, source, rewardCaps, rewardXp, targetKey),
            baseDistanceM = baseDistanceM, baseWins = baseWins, baseVentures = baseVentures,
            basePlaces = basePlaces, issuedDay = issuedDay,
        )
    }

    private suspend fun ensureLoaded() {
        mutex.withLock {
            if (loaded) return@withLock
            val stored = context.questDataStore.data.first()[prefsKey]
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            if (stored != null) {
                state = QuestLogState(
                    active = stored.active.mapNotNull { it.domain() },
                    completedIds = stored.completedIds.toSet(),
                )
            }
            loaded = true
        }
    }

    /**
     * Advance the quest log against the freshly [composed] quests + the live [metrics]: retire completions
     * (emit each on [completed] for reward granting), top up the active set, persist, and republish.
     */
    fun sync(composed: List<Quest>, metrics: QuestMetrics) {
        scope.launch {
            ensureLoaded()
            // Serialize the read-modify-write so rapid emissions can't race and double-complete a quest.
            val outcome = mutex.withLock {
                val before = state
                val result = QuestLog.sync(state, composed, metrics)
                state = result.state
                result to (before != result.state)
            }
            val (result, changed) = outcome
            _quests.value = QuestLog.view(result.state, metrics)
            result.newlyCompleted.forEach { _completed.tryEmit(it) }
            if (changed) scheduleFlush()
        }
    }

    /** Wipe all quest state (called when the operative is reset). */
    fun clear() {
        flushJob?.cancel()
        scope.launch {
            mutex.withLock { state = QuestLogState() }
            _quests.value = emptyList()
            runCatching { context.questDataStore.edit { it.remove(prefsKey) } }
        }
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val snapshot = Stored(state.active.map { it.stored() }, state.completedIds.toList())
        runCatching {
            context.questDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 1_500L
    }
}
