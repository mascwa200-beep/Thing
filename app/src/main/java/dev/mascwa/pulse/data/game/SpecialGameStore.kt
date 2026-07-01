package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Character
import dev.mascwa.pulse.core.telemetry.Encounter
import dev.mascwa.pulse.core.telemetry.EnvContext
import dev.mascwa.pulse.core.telemetry.Resolution
import dev.mascwa.pulse.core.telemetry.Special
import dev.mascwa.pulse.core.telemetry.SpecialEncounters
import dev.mascwa.pulse.core.telemetry.SpecialGame
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
import kotlin.random.Random

private val Context.specialDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_special")

/**
 * On-device persistence + play surface for the [SpecialGame] — the STAT-tab wasteland RPG. In-memory
 * [Character] (authoritative) + Mutex + debounced flush (mirrors ProfileStore/TaskStore). The engine is
 * pure; this store supplies the randomness (die rolls, encounter selection) and durability, so the game
 * survives across launches. Stays on-device; the user can reset it.
 */
class SpecialGameStore(
    private val context: Context,
    private val json: Json,
    private val random: Random = Random.Default,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class Stored(
        val stats: Map<String, Int> = emptyMap(),
        val level: Int = 1,
        val xp: Int = 0,
        val caps: Int = 25,
        val hp: Int = 40,
        val unspent: Int = 0,
        val seen: List<String> = emptyList(),
        val currentEncounterId: String? = null,
        val perks: List<String> = emptyList(),
        val perkPicks: Int = 0,
        val inventory: Map<String, Int> = emptyMap(),
    )

    private val prefsKey = stringPreferencesKey("special_json")
    private val mutex = Mutex()
    private var loaded = false
    private var flushJob: Job? = null

    private val _character = MutableStateFlow(SpecialGame.newCharacter())
    /** The live character sheet — stats, level, XP, caps, HP, unspent points. */
    val characterFlow: StateFlow<Character> = _character.asStateFlow()

    private val _resolution = MutableStateFlow<Resolution?>(null)
    /** The most recent encounter outcome to surface (cleared when a new encounter is drawn). */
    val resolutionFlow: StateFlow<Resolution?> = _resolution.asStateFlow()

    private fun Character.stored() = Stored(
        stats = stats.entries.associate { it.key.name to it.value },
        level = level, xp = xp, caps = caps, hp = hp, unspent = unspent,
        seen = seen.toList(), currentEncounterId = currentEncounterId,
        perks = perks.toList(), perkPicks = perkPicks,
        inventory = inventory,
    )

    private fun Stored.domain(): Character {
        val map = stats.mapNotNull { (k, v) ->
            runCatching { Special.valueOf(k) }.getOrNull()?.let { it to v }
        }.toMap()
        // Any stat missing from the blob defaults to the starting value, so old/partial saves still load.
        val filled = Special.entries.associateWith { (map[it] ?: Character.START_STAT).coerceIn(1, 10) }
        return Character(
            stats = filled, level = level.coerceAtLeast(1), xp = xp.coerceAtLeast(0),
            caps = caps.coerceAtLeast(0), hp = hp.coerceAtLeast(0), unspent = unspent.coerceAtLeast(0),
            seen = seen.toSet(), currentEncounterId = currentEncounterId,
            perks = perks.toSet(), perkPicks = perkPicks.coerceAtLeast(0),
            inventory = inventory.filterValues { it > 0 },
        )
    }

    private suspend fun ensureLoaded() {
        mutex.withLock {
            if (loaded) return
            val raw = context.specialDataStore.data.first()[prefsKey]
            val character = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?.domain()
            if (character != null) _character.value = character // else keep the fresh newCharacter()
            loaded = true
        }
    }

    /** The encounter the player is currently facing (resolved from the persisted id), or null. */
    fun encounterFor(c: Character): Encounter? =
        c.currentEncounterId?.let { id -> SpecialEncounters.ALL.firstOrNull { it.id == id } }

    /** Draw the next encounter to face (no-op if one is already active or the player is downed). */
    fun venture() {
        scope.launch {
            ensureLoaded()
            val c = _character.value
            if (c.down || c.currentEncounterId != null) return@launch
            val next = SpecialGame.nextEncounter(c, SpecialEncounters.ALL, random.nextInt(0, 100_000)) ?: return@launch
            _resolution.value = null
            _character.value = c.copy(currentEncounterId = next.id)
            scheduleFlush()
        }
    }

    /**
     * Resolve [choiceIndex] of the active encounter with a fresh die roll; publishes the [Resolution].
     * [env] is the real-world context (temperature/light/motion/… bends the check) and [useItemId] is an
     * optional CHEM consumed to buff this check — both flow in from the ViewModel.
     */
    fun choose(choiceIndex: Int, env: EnvContext? = null, useItemId: String? = null) {
        scope.launch {
            ensureLoaded()
            val c = _character.value
            val encounter = encounterFor(c) ?: return@launch
            val roll = random.nextInt(1, SpecialGame.DIE + 1)
            val resolution = SpecialGame.resolve(c, encounter, choiceIndex, roll, env, useItemId)
            _character.value = resolution.character
            _resolution.value = resolution
            scheduleFlush()
        }
    }

    /** Use an AID item from the pack to heal (no-op if none held or it isn't an AID). */
    fun useItem(itemId: String) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.useAid(_character.value, itemId)
            scheduleFlush()
        }
    }

    /** Sell one of [itemId] for caps. */
    fun sellItem(itemId: String) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.sellItem(_character.value, itemId)
            scheduleFlush()
        }
    }

    /** Spend an unspent point on [s] (level-up allocation). */
    fun allocate(s: Special) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.allocate(_character.value, s)
            scheduleFlush()
        }
    }

    /** Choose a perk [perkId] (spends a perk pick). */
    fun choosePerk(perkId: String) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.choosePerk(_character.value, perkId)
            scheduleFlush()
        }
    }

    /** Get back up after being downed. */
    fun revive() {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.revive(_character.value)
            _resolution.value = null
            scheduleFlush()
        }
    }

    /** Start over with a fresh operative. */
    fun reset() {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.newCharacter()
            _resolution.value = null
            scheduleFlush()
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
        val snapshot = _character.value.stored()
        runCatching {
            context.specialDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 1_000L
    }
}
