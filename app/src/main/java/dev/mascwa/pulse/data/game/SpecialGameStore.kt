package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Achievement
import dev.mascwa.pulse.core.telemetry.Achievements
import dev.mascwa.pulse.core.telemetry.Character
import dev.mascwa.pulse.core.telemetry.DailyObjective
import dev.mascwa.pulse.core.telemetry.DailyObjectives
import dev.mascwa.pulse.core.telemetry.Encounter
import dev.mascwa.pulse.core.telemetry.EnvContext
import dev.mascwa.pulse.core.telemetry.TodayMetrics
import dev.mascwa.pulse.core.telemetry.GameMetrics
import dev.mascwa.pulse.core.telemetry.Recipes
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

/** Today's daily-objective state for the UI: the three objectives, today's progress, what's claimed, streak. */
data class DailyState(
    val objectives: List<DailyObjective> = emptyList(),
    val metrics: TodayMetrics = TodayMetrics(),
    val claimed: Set<String> = emptySet(),
    val streak: Int = 0,
)

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
        val companion: String? = null,
        val reputation: Map<String, Int> = emptyMap(),
        // Lifetime counters + unlocked achievements (defaulted → old saves load).
        val wins: Int = 0,
        val crits: Int = 0,
        val ventures: Int = 0,
        val unlocked: List<String> = emptyList(),
        // App-usage XP baseline: the total app-visit count we last granted XP for (-1 = not yet baselined).
        val lastXpVisits: Int = -1,
        // Daily objectives: the day the baseline is for (epoch-day, -1 = none), the counter baselines at day
        // start, what's been claimed today, and the all-3-a-day play streak.
        val dailyDay: Long = -1,
        val baseWins: Int = 0,
        val baseVentures: Int = 0,
        val baseCrits: Int = 0,
        val baseTravelM: Int = 0,
        val basePlaces: Int = 0,
        val claimed: List<String> = emptyList(),
        val streak: Int = 0,
        val streakDay: Long = -1,
    )

    private val prefsKey = stringPreferencesKey("special_json")
    private val mutex = Mutex()
    private var loaded = false
    private var flushJob: Job? = null

    // Lifetime achievement counters (persisted alongside the character).
    private var wins = 0
    private var crits = 0
    private var ventures = 0
    private var unlocked: Set<String> = emptySet()
    private var lastXpVisits = -1
    // External metrics the ViewModel feeds in (real app usage + travel) for usage/travel achievements.
    private var extVisits = 0
    private var extFeatures = 0
    private var extDistanceM = 0
    private var extPlaces = 0
    // Daily-objective state.
    private var dailyDay = -1L
    private var baseWins = 0
    private var baseVentures = 0
    private var baseCrits = 0
    private var baseTravelM = 0
    private var basePlaces = 0
    private var claimed: Set<String> = emptySet()
    private var streak = 0
    private var streakDay = -1L

    private val _character = MutableStateFlow(SpecialGame.newCharacter())
    /** The live character sheet — stats, level, XP, caps, HP, unspent points. */
    val characterFlow: StateFlow<Character> = _character.asStateFlow()

    private val _resolution = MutableStateFlow<Resolution?>(null)
    /** The most recent encounter outcome to surface (cleared when a new encounter is drawn). */
    val resolutionFlow: StateFlow<Resolution?> = _resolution.asStateFlow()

    private val _unlocked = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of unlocked achievements. */
    val unlockedFlow: StateFlow<Set<String>> = _unlocked.asStateFlow()

    private val _lastUnlock = MutableStateFlow<Achievement?>(null)
    /** The most recently unlocked achievement, for a one-shot banner (cleared via [dismissUnlock]). */
    val lastUnlockFlow: StateFlow<Achievement?> = _lastUnlock.asStateFlow()

    private val _metrics = MutableStateFlow(GameMetrics())
    /** The live metric snapshot achievements are measured against (for progress bars). */
    val metricsFlow: StateFlow<GameMetrics> = _metrics.asStateFlow()

    private val _daily = MutableStateFlow(DailyState())
    /** Today's daily objectives + progress + streak (for the DAILY panel). */
    val dailyFlow: StateFlow<DailyState> = _daily.asStateFlow()

    /** Dismiss the one-shot unlock banner. */
    fun dismissUnlock() { _lastUnlock.value = null }

    private fun Character.stored() = Stored(
        stats = stats.entries.associate { it.key.name to it.value },
        level = level, xp = xp, caps = caps, hp = hp, unspent = unspent,
        seen = seen.toList(), currentEncounterId = currentEncounterId,
        perks = perks.toList(), perkPicks = perkPicks,
        inventory = inventory, companion = companion, reputation = reputation,
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
            companion = companion,
            reputation = reputation.filterValues { it > 0 },
        )
    }

    private suspend fun ensureLoaded() {
        var justLoaded = false
        mutex.withLock {
            if (loaded) return@withLock
            val stored = context.specialDataStore.data.first()[prefsKey]
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            if (stored != null) {
                _character.value = stored.domain() // else keep the fresh newCharacter()
                wins = stored.wins.coerceAtLeast(0)
                crits = stored.crits.coerceAtLeast(0)
                ventures = stored.ventures.coerceAtLeast(0)
                unlocked = stored.unlocked.toSet()
                _unlocked.value = unlocked
                lastXpVisits = stored.lastXpVisits
                dailyDay = stored.dailyDay
                baseWins = stored.baseWins; baseVentures = stored.baseVentures; baseCrits = stored.baseCrits
                baseTravelM = stored.baseTravelM; basePlaces = stored.basePlaces
                claimed = stored.claimed.toSet()
                streak = stored.streak.coerceAtLeast(0); streakDay = stored.streakDay
            }
            loaded = true
            justLoaded = true
        }
        if (justLoaded) { publishMetrics(); refreshDaily() }
    }

    /** Build the current metric snapshot: character progress + counters + the fed-in app-usage/travel. */
    private fun currentMetrics(): GameMetrics {
        val c = _character.value
        return GameMetrics(
            level = c.level, wins = wins, crits = crits, ventures = ventures,
            perks = c.perks.size, distinctItems = c.inventory.size, caps = c.caps,
            appVisits = extVisits, distinctFeatures = extFeatures,
            distanceM = extDistanceM, placesVisited = extPlaces,
        )
    }

    private fun publishMetrics() { _metrics.value = currentMetrics() }

    /** Evaluate achievements against current state; grant rewards for newly cleared ones; always republish. */
    private fun runAchievementCheck() {
        val fresh = Achievements.evaluate(currentMetrics(), unlocked)
        if (fresh.isNotEmpty()) {
            var updated = _character.value
            fresh.forEach { updated = Achievements.applyReward(updated, it) }
            _character.value = updated
            unlocked = unlocked + fresh.map { it.id }
            _unlocked.value = unlocked
            _lastUnlock.value = fresh.last()
            scheduleFlush()
        }
        publishMetrics()
        refreshDaily()
    }

    // --- Daily objectives ---
    private fun currentDay(): Long = java.time.LocalDate.now().toEpochDay()

    /** On a new local day, capture the day's counter baselines and clear the claimed set. */
    private fun rolloverIfNewDay() {
        val today = currentDay()
        if (dailyDay == today) return
        dailyDay = today
        baseWins = wins; baseVentures = ventures; baseCrits = crits
        baseTravelM = extDistanceM; basePlaces = extPlaces
        claimed = emptySet()
        scheduleFlush()
    }

    /** Progress made *today* — current lifetime counters minus the day's baseline. */
    private fun todayMetrics(): TodayMetrics = TodayMetrics(
        wins = (wins - baseWins).coerceAtLeast(0),
        ventures = (ventures - baseVentures).coerceAtLeast(0),
        crits = (crits - baseCrits).coerceAtLeast(0),
        travelM = (extDistanceM - baseTravelM).coerceAtLeast(0),
        places = (extPlaces - basePlaces).coerceAtLeast(0),
    )

    /** Roll the day over if needed, then publish today's objectives + progress + streak. */
    private fun refreshDaily() {
        rolloverIfNewDay()
        _daily.value = DailyState(
            objectives = DailyObjectives.forDay(dailyDay.coerceAtLeast(0)),
            metrics = todayMetrics(),
            claimed = claimed,
            streak = streak,
        )
    }

    /** Claim a completed daily objective's reward (once); advances the streak when all three are claimed. */
    fun claimDaily(objectiveId: String) {
        scope.launch {
            ensureLoaded()
            rolloverIfNewDay()
            val today = dailyDay
            val objectives = DailyObjectives.forDay(today.coerceAtLeast(0))
            val obj = objectives.firstOrNull { it.id == objectiveId } ?: return@launch
            if (obj.id in claimed) return@launch
            if (!DailyObjectives.isComplete(obj, todayMetrics())) return@launch
            _character.value = DailyObjectives.applyReward(_character.value, obj)
            claimed = claimed + obj.id
            if (objectives.all { it.id in claimed }) {
                streak = if (streakDay == today - 1) streak + 1 else 1
                streakDay = today
            }
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /**
     * Feed real app-usage metrics (from the usage snapshot). Grants XP for NEW app usage since the last
     * check — using Pulse levels your operative — then re-checks usage achievements. The first call after a
     * save just baselines (no retro-dump for usage that happened before this existed).
     */
    fun setUsageMetrics(appVisits: Int, distinctFeatures: Int) {
        scope.launch {
            ensureLoaded()
            if (lastXpVisits < 0) {
                lastXpVisits = appVisits // baseline — don't grant XP for usage before the game was tracking it
            } else if (appVisits > lastXpVisits) {
                val gained = (appVisits - lastXpVisits) * XP_PER_VISIT
                lastXpVisits = appVisits
                if (gained > 0) _character.value = SpecialGame.gainXp(_character.value, gained)
            }
            extVisits = appVisits
            extFeatures = distinctFeatures
            runAchievementCheck()
        }
    }

    /** Feed real-world travel metrics (from the game-world tracker); re-checks travel achievements. */
    fun setTravelMetrics(distanceM: Int, placesVisited: Int) {
        scope.launch {
            ensureLoaded()
            extDistanceM = distanceM
            extPlaces = placesVisited
            runAchievementCheck()
        }
    }

    /** Buy one of [itemId] at a [kind] shop — faction reputation discounts the price + earns standing. */
    fun buyAt(itemId: String, kind: dev.mascwa.pulse.core.telemetry.LocationKind) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.buyItemAt(_character.value, itemId, kind)
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /**
     * Resolve an NPC conversation [encounter] (from [dev.mascwa.pulse.core.telemetry.GameLocations]) with a
     * fresh die roll + the real-world [env]. Publishes the [Resolution] (so the shared banner shows the
     * outcome) without touching the current wasteland encounter — conversations are repeatable.
     */
    fun resolveTalk(encounter: Encounter, kind: dev.mascwa.pulse.core.telemetry.LocationKind? = null, env: EnvContext? = null) {
        scope.launch {
            ensureLoaded()
            val roll = random.nextInt(1, SpecialGame.DIE + 1)
            val resolution = SpecialGame.resolve(_character.value, encounter, 0, roll, env)
            var updated = resolution.character
            // A good conversation earns standing with that faction.
            if (resolution.success && kind != null) {
                updated = SpecialGame.addRep(updated, kind, dev.mascwa.pulse.core.telemetry.Reputation.PER_TALK)
            }
            _character.value = updated
            _resolution.value = resolution
            if (resolution.success) wins++
            if (resolution.crit) crits++
            runAchievementCheck()
            scheduleFlush()
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
            ventures++
            runAchievementCheck()
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
            if (resolution.success) wins++
            if (resolution.crit) crits++
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /** Use an AID item from the pack to heal (no-op if none held or it isn't an AID). */
    fun useItem(itemId: String) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.useAid(_character.value, itemId)
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /** Sell one of [itemId] for caps. */
    fun sellItem(itemId: String) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.sellItem(_character.value, itemId)
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /** Craft [recipeId] at the workbench (consumes inputs, yields the output + a little XP). */
    fun craft(recipeId: String) {
        scope.launch {
            ensureLoaded()
            val recipe = Recipes.byId(recipeId) ?: return@launch
            _character.value = SpecialGame.craft(_character.value, recipe)
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /** Hire companion [companionId] for caps (replaces any current one). */
    fun hireCompanion(companionId: String) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.hireCompanion(_character.value, companionId)
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /** Send the active companion away. */
    fun dismissCompanion() {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.dismissCompanion(_character.value)
            scheduleFlush()
        }
    }

    /** Spend an unspent point on [s] (level-up allocation). */
    fun allocate(s: Special) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.allocate(_character.value, s)
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /** Choose a perk [perkId] (spends a perk pick). */
    fun choosePerk(perkId: String) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.choosePerk(_character.value, perkId)
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /** Get back up after being downed. */
    fun revive() {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.revive(_character.value)
            _resolution.value = null
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /** Start over with a fresh operative. */
    fun reset() {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.newCharacter()
            _resolution.value = null
            wins = 0; crits = 0; ventures = 0
            unlocked = emptySet()
            _unlocked.value = emptySet()
            _lastUnlock.value = null
            lastXpVisits = -1 // re-baseline app-usage XP so the fresh operative doesn't get a dump
            dailyDay = -1L; claimed = emptySet() // re-baseline today's objective progress against the fresh counters
            // Usage/travel achievements re-earn from the (persisted) real metrics on the next check.
            runAchievementCheck()
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
        val snapshot = _character.value.stored().copy(
            wins = wins, crits = crits, ventures = ventures, unlocked = unlocked.toList(),
            lastXpVisits = lastXpVisits,
            dailyDay = dailyDay, baseWins = baseWins, baseVentures = baseVentures, baseCrits = baseCrits,
            baseTravelM = baseTravelM, basePlaces = basePlaces, claimed = claimed.toList(),
            streak = streak, streakDay = streakDay,
        )
        runCatching {
            context.specialDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 1_000L
        const val XP_PER_VISIT = 3 // XP granted per new app-screen visit (using Pulse levels your operative)
    }
}
