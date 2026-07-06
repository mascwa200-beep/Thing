package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.Achievement
import dev.mascwa.pulse.core.telemetry.Achievements
import dev.mascwa.pulse.core.telemetry.Affliction
import dev.mascwa.pulse.core.telemetry.AfflictionState
import dev.mascwa.pulse.core.telemetry.Afflictions
import dev.mascwa.pulse.core.telemetry.Archetype
import dev.mascwa.pulse.core.telemetry.Character
import dev.mascwa.pulse.core.telemetry.DeedKind
import dev.mascwa.pulse.core.telemetry.Legend
import dev.mascwa.pulse.core.telemetry.Legends
import dev.mascwa.pulse.core.telemetry.DailyObjective
import dev.mascwa.pulse.core.telemetry.DailyObjectives
import dev.mascwa.pulse.core.telemetry.Encounter
import dev.mascwa.pulse.core.telemetry.EnvContext
import dev.mascwa.pulse.core.telemetry.ExertCost
import dev.mascwa.pulse.core.telemetry.ExertKind
import dev.mascwa.pulse.core.telemetry.Exertion
import dev.mascwa.pulse.core.telemetry.TodayMetrics
import dev.mascwa.pulse.core.telemetry.GameMetrics
import dev.mascwa.pulse.core.telemetry.ItemCodex
import dev.mascwa.pulse.core.telemetry.LifeProfile
import dev.mascwa.pulse.core.telemetry.LifeStats
import dev.mascwa.pulse.core.telemetry.LootTable
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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

/** A one-shot "that action cost you needs" event for a transient UI flourish. */
data class ExertReport(val kind: ExertKind, val cost: ExertCost)

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
        // Your wasteland tale (global renown + the curated archetype). Defaulted → old saves load neutral.
        val legendRenown: Int = 0,
        val legendArchetype: String? = null,
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
        // Wall-clock ms when this operative's story began — drives the wasteland day counter (Day 1, 2, …).
        val startedAtMs: Long = 0L,
        // The operator's real-life profile (LifeStats). Body metrics + self-reported real money → game buffs;
        // hydration/hygiene persist as ANCHORED base values decayed forward from [needsAnchorMs]. All defaulted
        // → old saves load with a fully-neutral (blank) profile. ON-DEVICE ONLY — never leaves the device.
        val heightCm: Int = 0,
        val weightKg: Int = 0,
        val ageYears: Int = 0,
        val realMoney: Double = 0.0,
        val currency: String = "USD",
        val hydrationBase: Int = 100,
        val hygieneBase: Int = 100,
        val energyBase: Int = 100,
        val nourishmentBase: Int = 100,
        val mood: Int = 50,
        val operatorName: String = "",
        // Self-reported 0..100 life scores (0 = unset) — how well-read / in-shape / rooted you are. Defaulted
        // → old saves load neutral. ON-DEVICE ONLY.
        val wellRead: Int = 0,
        val fitness: Int = 0,
        val community: Int = 0,
        val needsAnchorMs: Long = 0L,
        // Today's real steps + the daily baseline (cumulative step-counter reading at the day's start;
        // reboot-safe). All defaulted → old saves load with no step data.
        val stepsToday: Int = 0,
        val stepDay: Long = -1L,
        val stepBaseline: Long = -1L,
        // Wall-clock ms of the last SCAVENGE (0 = never); gates the scavenge cooldown. Defaulted → old saves.
        val lastScavengeMs: Long = 0L,
        // Item codex: every item id ever acquired (monotonic — never removed). Defaulted → old saves.
        val discoveredIds: List<String> = emptyList(),
        // Lingering afflictions: the per-affliction sickness meter (ms), the set that's taken hold, and the
        // advance anchor. Serialized by Affliction.name. All defaulted → old saves load healthy.
        val afflictionMeter: Map<String, Long> = emptyMap(),
        val afflicted: List<String> = emptyList(),
        val afflictionAnchorMs: Long = 0L,
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
    private var extVisitedKinds = emptySet<String>() // place-kinds reached (LocationKind.names) → VISIT_KIND quests
    // Real-time travel breakdown (from GameWorldStore): per-mode distance, vertical climb, cells explored.
    private var extWalkM = 0
    private var extRunM = 0
    private var extCycleM = 0
    private var extDriveM = 0
    private var extElevationM = 0
    private var extCells = 0
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
    // When the operative's story began (wall-clock ms) — the anchor for the wasteland day counter.
    private var startedAtMs = 0L

    // The operator's real-life profile. [lifeBase] holds the ANCHORED needs (hydration/hygiene) — the live
    // values are decayed forward from [needsAnchorMs] on read, so continuous display refresh never loses
    // sub-threshold decay (base + anchor only change on a top-up / edit).
    private var lifeBase = LifeProfile()
    private var needsAnchorMs = 0L
    // The most recent real-world context (weather/time/motion/charging) the ViewModel fed in — drives how
    // fast the needs decay (and lets charging regenerate energy). Null = plain base rates.
    private var lastEnv: EnvContext? = null

    // Lingering afflictions: the sickness state + its own advance anchor (the meter accrues over real time a
    // need is left critical / recovers while it's healthy). Persisted so it survives across sessions.
    private var afflictions = AfflictionState()
    private var afflictionAnchorMs = 0L
    // Step-counter daily baseline: the cumulative reading at the start of [stepDay] (epoch-day). Today's
    // steps = latest cumulative − baseline (reboot-safe: a lower reading re-baselines).
    private var stepDay = -1L
    private var stepBaseline = -1L
    // Wall-clock ms of the last scavenge — the cooldown anchor (0 = ready immediately).
    private var lastScavengeMs = 0L

    private val _lastScavengeMs = MutableStateFlow(0L)
    /** When the player last scavenged (wall-clock ms). The UI derives the cooldown countdown from this. */
    val lastScavengeMsFlow: StateFlow<Long> = _lastScavengeMs.asStateFlow()

    private val _lastScavenge = MutableStateFlow<Map<String, Int>?>(null)
    /** The most recent scavenge haul (id → count), for a one-shot "found" readout; null when dismissed. */
    val lastScavengeFlow: StateFlow<Map<String, Int>?> = _lastScavenge.asStateFlow()

    // Item codex: every item id ever acquired (monotonic). Fed by a collector on the character (below), so
    // every acquisition path — loot, scavenge, shop, craft, encounter reward — marks discovery automatically.
    private var discovered: Set<String> = emptySet()
    private val _discovered = MutableStateFlow<Set<String>>(emptySet())
    /** Ids of every item ever acquired (for the ITEMS ▸ codex progress). */
    val discoveredFlow: StateFlow<Set<String>> = _discovered.asStateFlow()

    private val _started = MutableStateFlow(0L)
    /** Wall-clock ms the character's story began (0 until loaded); feeds [GameClock] for the day counter. */
    val startedFlow: StateFlow<Long> = _started.asStateFlow()

    private val _life = MutableStateFlow(LifeProfile())
    /** The operator's real-life profile with hydration/hygiene decayed to now (on-device only). */
    val lifeFlow: StateFlow<LifeProfile> = _life.asStateFlow()

    private val _afflictions = MutableStateFlow(AfflictionState())
    /** Lingering survival afflictions (contracted from long-neglected needs) — advanced on the decay tick. */
    val afflictionsFlow: StateFlow<AfflictionState> = _afflictions.asStateFlow()

    // Non-blocking one-shot stream of "that action drained your needs" reports (fight/venture/scavenge/travel).
    private val _exertReport = MutableSharedFlow<ExertReport>(extraBufferCapacity = 4)
    val exertReportFlow: SharedFlow<ExertReport> = _exertReport.asSharedFlow()

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
        legendRenown = legend.renown, legendArchetype = legend.archetype?.name,
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
            legend = Legend(
                renown = legendRenown.coerceIn(Legends.MIN, Legends.MAX),
                archetype = legendArchetype?.let { runCatching { Archetype.valueOf(it) }.getOrNull() },
            ),
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
                startedAtMs = stored.startedAtMs
                lifeBase = LifeProfile(
                    heightCm = stored.heightCm, weightKg = stored.weightKg, ageYears = stored.ageYears,
                    realMoney = stored.realMoney, currency = stored.currency.ifBlank { "USD" },
                    hydration = stored.hydrationBase.coerceIn(0, 100), hygiene = stored.hygieneBase.coerceIn(0, 100),
                    energy = stored.energyBase.coerceIn(0, 100), nourishment = stored.nourishmentBase.coerceIn(0, 100),
                    mood = stored.mood.coerceIn(0, 100), operatorName = stored.operatorName.take(LifeStats.MAX_NAME),
                    stepsToday = stored.stepsToday.coerceAtLeast(0),
                    wellRead = stored.wellRead.coerceIn(0, 100), fitness = stored.fitness.coerceIn(0, 100),
                    community = stored.community.coerceIn(0, 100),
                )
                needsAnchorMs = stored.needsAnchorMs
                stepDay = stored.stepDay; stepBaseline = stored.stepBaseline
                lastScavengeMs = stored.lastScavengeMs.coerceAtLeast(0L)
                _lastScavengeMs.value = lastScavengeMs
                discovered = stored.discoveredIds.toSet()
                afflictions = AfflictionState(
                    meterMs = stored.afflictionMeter.mapNotNull { (k, v) ->
                        runCatching { Affliction.valueOf(k) }.getOrNull()?.let { it to v.coerceIn(0L, Afflictions.ONSET_MS) }
                    }.toMap(),
                    active = stored.afflicted.mapNotNull { runCatching { Affliction.valueOf(it) }.getOrNull() }.toSet(),
                )
                afflictionAnchorMs = stored.afflictionAnchorMs
            }
            // Seed the codex from whatever's already in the pack (so pre-codex saves count their held items).
            discovered = (discovered + _character.value.inventory.keys)
            _discovered.value = discovered
            // Fresh operative, or an old save from before day-tracking → stamp the story's start now.
            if (startedAtMs <= 0L) startedAtMs = System.currentTimeMillis()
            if (needsAnchorMs <= 0L) needsAnchorMs = System.currentTimeMillis()
            if (afflictionAnchorMs <= 0L) afflictionAnchorMs = System.currentTimeMillis()
            _started.value = startedAtMs
            _life.value = currentLife()
            _afflictions.value = afflictions
            loaded = true
            justLoaded = true
        }
        if (justLoaded) {
            scheduleFlush(); publishMetrics(); refreshDaily()
            // Track item discovery off the live character — any acquisition path (loot/scavenge/shop/craft/
            // encounter reward) grows the pack, and the codex unions those ids (monotonic). Launched once.
            scope.launch {
                _character.collect { c ->
                    val union = discovered + c.inventory.keys
                    if (union.size != discovered.size) {
                        discovered = union
                        _discovered.value = union
                        runAchievementCheck() // codex-milestone achievements measure the fresh discovery count
                        scheduleFlush()
                    }
                }
            }
        }
    }

    /** Build the current metric snapshot: character progress + counters + the fed-in app-usage/travel. */
    private fun currentMetrics(): GameMetrics {
        val c = _character.value
        return GameMetrics(
            level = c.level, wins = wins, crits = crits, ventures = ventures,
            perks = c.perks.size, distinctItems = c.inventory.size, caps = c.caps,
            appVisits = extVisits, distinctFeatures = extFeatures,
            distanceM = extDistanceM, placesVisited = extPlaces,
            itemsDiscovered = ItemCodex.found(discovered),
            walkM = extWalkM, runM = extRunM, cycleM = extCycleM, driveM = extDriveM,
            elevationM = extElevationM, cellsExplored = extCells,
            renown = c.legend.renown,
            visitedKinds = extVisitedKinds,
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

    /**
     * After a RESET, mark whatever already qualifies (mostly your KEPT real-life metrics — steps, distance,
     * places) as earned **without paying out its rewards**, so a fresh operative can't be instantly re-levelled
     * by achievements you preserved. Without this, [runAchievementCheck] on a just-reset character re-grants
     * all those XP/caps/item rewards and hands out perk picks — the "reset didn't reset" bug. Game-progress
     * achievements (wins/crits/ventures/…) don't re-qualify because their counters were just zeroed.
     */
    private fun seedAchievementsNoReward() {
        unlocked = Achievements.evaluate(currentMetrics(), emptySet()).map { it.id }.toSet()
        _unlocked.value = unlocked
        _lastUnlock.value = null
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
            // NO MORE COUCH XP — opening app screens no longer levels the operative. XP is earned ONLY by
            // doing things at real wasteland sites (site-gated encounters/scavenge/talks + quest rewards).
            // We still track the raw counts so the (one-time milestone) usage achievements can still clear.
            extVisits = appVisits
            extFeatures = distinctFeatures
            runAchievementCheck()
        }
    }

    /** Feed real-world travel metrics (from the game-world tracker); re-checks travel achievements. */
    fun setTravelMetrics(
        distanceM: Int,
        placesVisited: Int,
        walkM: Int = extWalkM,
        runM: Int = extRunM,
        cycleM: Int = extCycleM,
        driveM: Int = extDriveM,
        elevationM: Int = extElevationM,
        cells: Int = extCells,
        visitedKinds: Set<String> = extVisitedKinds,
    ) {
        scope.launch {
            ensureLoaded()
            extDistanceM = distanceM
            extPlaces = placesVisited
            extVisitedKinds = visitedKinds
            extWalkM = walkM; extRunM = runM; extCycleM = cycleM; extDriveM = driveM
            extElevationM = elevationM; extCells = cells
            runAchievementCheck()
        }
    }

    /**
     * Buy one of [itemId] at a [kind] shop — faction reputation discounts the price, the day's world event
     * [shopPct] bends it further, and the purchase earns standing.
     */
    fun buyAt(itemId: String, kind: dev.mascwa.pulse.core.telemetry.LocationKind, shopPct: Int = 0) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.buyItemAt(_character.value, itemId, kind, shopPct)
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
            tickAfflictions()
            val resolution = SpecialGame.resolve(_character.value, encounter, 0, roll, env, life = currentLife(), afflictions = afflictions)
            var updated = resolution.character
            // A good conversation earns standing with that faction — and grows your wasteland tale.
            if (resolution.success) {
                if (kind != null) {
                    updated = SpecialGame.addRep(updated, kind, dev.mascwa.pulse.core.telemetry.Reputation.PER_TALK)
                }
                updated = SpecialGame.recordDeed(updated, DeedKind.WON_TALK)
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

    /**
     * Draw the next encounter to face (no-op if one is already active or the player is downed). [favored]
     * biases selection toward the perceived scene's stats (from the on-device perception), when supplied.
     */
    fun venture(favored: Set<Special> = emptySet()) {
        scope.launch {
            ensureLoaded()
            val c = _character.value
            if (c.down || c.currentEncounterId != null) return@launch
            val next = SpecialGame.nextEncounter(c, SpecialEncounters.ALL, random.nextInt(0, 100_000), favored) ?: return@launch
            _resolution.value = null
            _character.value = c.copy(currentEncounterId = next.id)
            ventures++
            exert(ExertKind.VENTURE, Exertion.ventureCost()) // heading out costs a little energy
            runAchievementCheck()
            scheduleFlush()
        }
    }

    /**
     * Scavenge the area for salvage — a rarity-weighted [LootTable] haul scaled by LUCK (more picks, better
     * odds on the rare tiers). Rate-limited by [SCAVENGE_COOLDOWN_MS] so it can't be farmed; no-op while on
     * cooldown or downed. Publishes the haul to [lastScavengeFlow] for a one-shot readout.
     */
    fun scavenge() {
        scope.launch {
            ensureLoaded()
            val now = System.currentTimeMillis()
            if (now - lastScavengeMs < SCAVENGE_COOLDOWN_MS) return@launch // still on cooldown
            val c = _character.value
            if (c.down) return@launch
            val luck = c.stat(Special.LUCK)
            val picks = 1 + luck / 5 // luck 1–4 → 1, 5–9 → 2, 10 → 3 items
            val rolls = List(picks) { random.nextDouble() }
            val haul = LootTable.scavenge(luck, rolls)
            var updated = c
            for ((id, qty) in haul) updated = SpecialGame.addItem(updated, id, qty)
            _character.value = updated
            lastScavengeMs = now
            _lastScavengeMs.value = now
            _lastScavenge.value = haul
            exert(ExertKind.SCAVENGE, Exertion.scavengeCost()) // combing a site is thirsty, tiring legwork
            runAchievementCheck() // a new distinct item may clear a collection achievement
            scheduleFlush()
        }
    }

    /** Dismiss the one-shot scavenge-haul readout. */
    fun dismissScavenge() { _lastScavenge.value = null }

    /**
     * Resolve [choiceIndex] of the active encounter with a fresh die roll; publishes the [Resolution].
     * [env] is the real-world context (temperature/light/motion/… bends the check) and [useItemId] is an
     * optional CHEM consumed to buff this check — both flow in from the ViewModel.
     */
    fun choose(choiceIndex: Int, env: EnvContext? = null, useItemId: String? = null, roll: Int? = null, worldCapsPct: Int = 0, wellKept: Int = 0) {
        scope.launch {
            ensureLoaded()
            val c = _character.value
            val encounter = encounterFor(c) ?: return@launch
            // The die comes from how well the player physically performed the gesture, when supplied.
            val r = roll ?: random.nextInt(1, SpecialGame.DIE + 1)
            tickAfflictions()
            val resolution = SpecialGame.resolve(c, encounter, choiceIndex, r, env, useItemId, currentLife(), wellKept, afflictions)
            var updatedChar = resolution.character
            // The day's world event tweaks the caps a win pays (a fraction of the base reward, +/-).
            if (resolution.success && worldCapsPct != 0) {
                val bonus = resolution.outcome.caps * worldCapsPct / 100
                if (bonus != 0) updatedChar = updatedChar.copy(caps = (updatedChar.caps + bonus).coerceAtLeast(0))
            }
            _character.value = updatedChar
            _resolution.value = resolution
            if (resolution.success) wins++
            if (resolution.crit) crits++
            // The fight was resolved at pre-exertion needs (resolve read currentLife above); now the physical
            // effort of the gesture is the aftermath. Only stat-gated approaches (a real gesture) cost needs —
            // a SAFE option is passive. Harder checks + a flat-out gesture cost more.
            val chosen = encounter.choices.getOrNull(choiceIndex)
            if (chosen?.stat != null) {
                exert(ExertKind.ENGAGE, Exertion.engageCost(chosen.difficulty, r.toFloat() / SpecialGame.DIE))
            }
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

    /**
     * Curate your tale — (re)seed your renown from a chosen [archetype] (null / ENIGMA / WANDERER = a blank
     * slate). The alternative to letting it grow on its own from your deeds ([resolveTalk] etc.).
     */
    fun curateTale(archetype: Archetype?) {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.curateLegend(_character.value, archetype)
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
            startedAtMs = System.currentTimeMillis(); _started.value = startedAtMs // Day 1 begins again
            lifeBase = LifeProfile(); needsAnchorMs = System.currentTimeMillis(); _life.value = lifeBase // clear the real-life profile
            afflictions = AfflictionState(); afflictionAnchorMs = System.currentTimeMillis(); _afflictions.value = afflictions // shake any sickness
            stepDay = -1L; stepBaseline = -1L // re-baseline today's steps for the fresh operative
            lastScavengeMs = 0L; _lastScavengeMs.value = 0L; _lastScavenge.value = null // scavenge ready again
            discovered = emptySet(); _discovered.value = emptySet() // wipe the codex for the fresh operative
            // Seed (no reward) so kept real-life achievements can't instantly re-level the fresh operative.
            seedAchievementsNoReward()
            scheduleFlush()
        }
    }

    /**
     * Reset the GAME — stats, level, XP, caps, inventory, perks, companion, faction rep, achievements, daily
     * objectives, scavenge state and the item codex — to a fresh operative, but KEEP the real-life profile
     * (height/weight/age/money, hydration/hygiene/energy/nourishment, mood, name, steps) and the story clock.
     * For restarting the wasteland run without re-entering your real self. (Contrast [reset], which also
     * wipes the profile.)
     */
    fun resetProgress() {
        scope.launch {
            ensureLoaded()
            _character.value = SpecialGame.newCharacter()
            _resolution.value = null
            wins = 0; crits = 0; ventures = 0
            unlocked = emptySet(); _unlocked.value = emptySet(); _lastUnlock.value = null
            lastXpVisits = -1 // re-baseline app-usage XP against the fresh counters
            dailyDay = -1L; claimed = emptySet() // re-baseline today's objectives
            lastScavengeMs = 0L; _lastScavengeMs.value = 0L; _lastScavenge.value = null // scavenge ready again
            discovered = emptySet(); _discovered.value = emptySet() // wipe the codex
            afflictions = AfflictionState(); afflictionAnchorMs = System.currentTimeMillis(); _afflictions.value = afflictions // fresh run starts healthy
            // PRESERVED: lifeBase (+ needsAnchorMs), startedAtMs, stepDay/stepBaseline — your real self + clocks.
            // Seed (no reward) so kept real-life achievements can't instantly re-level the fresh operative
            // (the "reset didn't reset — I still have perks/level/caps" bug).
            seedAchievementsNoReward()
            scheduleFlush()
        }
    }

    /** Grant a completed quest's reward — caps + XP (with any level-ups) — into the character. */
    fun awardQuest(caps: Int, xp: Int) {
        scope.launch {
            ensureLoaded()
            var c = _character.value
            if (caps != 0) c = c.copy(caps = (c.caps + caps).coerceAtLeast(0))
            if (xp != 0) c = SpecialGame.gainXp(c, xp)
            _character.value = c
            runAchievementCheck()
            scheduleFlush()
        }
    }

    // --- Real-life profile (LifeStats) ---

    /** The live profile: [lifeBase] with the needs decayed forward to now under the last real-world [lastEnv]. */
    private fun currentLife(): LifeProfile =
        LifeStats.decayNeeds(lifeBase, System.currentTimeMillis() - needsAnchorMs, lastEnv)

    /** The decayed-to-now life profile, loading from disk first — for background reads (e.g. the notifier). */
    suspend fun lifeSnapshot(): LifeProfile { ensureLoaded(); return currentLife() }

    /**
     * Advance the sickness state to now over the current decayed needs and publish it. Fills the meter while
     * a need is critical, drains it while it's healthy (see [Afflictions.advance]); persists only on a change.
     * Always moves the anchor so elapsed time can't double-count. Cheap; safe to call every tick.
     */
    private fun tickAfflictions() {
        val now = System.currentTimeMillis()
        if (afflictionAnchorMs <= 0L) { afflictionAnchorMs = now; return }
        val elapsed = now - afflictionAnchorMs
        afflictionAnchorMs = now
        if (elapsed <= 0L) return
        val next = Afflictions.advance(afflictions, currentLife(), elapsed)
        if (next != afflictions) {
            afflictions = next
            _afflictions.value = next
            scheduleFlush()
        }
    }

    /** The sickness state, advanced to now, loading from disk first — for background reads (the notifier). */
    suspend fun afflictionSnapshot(): AfflictionState { ensureLoaded(); tickAfflictions(); return afflictions }

    /**
     * Recompute the decayed needs under the current real-world [env] and publish — call periodically (e.g.
     * each game tick) so the meters move in real time. Does NOT change the base/anchor (no sub-threshold
     * decay is lost) EXCEPT on a charging transition, where it re-anchors so the energy regen/decay isn't
     * applied retroactively to the whole prior gap.
     */
    fun refreshNeeds(env: EnvContext? = null) {
        scope.launch {
            ensureLoaded()
            val chargingChanged = (env?.charging == true) != (lastEnv?.charging == true)
            if (chargingChanged) {
                lifeBase = currentLife()               // capture the decayed state under the OLD env
                needsAnchorMs = System.currentTimeMillis()
                lastEnv = env
                scheduleFlush()
            } else {
                lastEnv = env
            }
            _life.value = currentLife()
            tickAfflictions()
        }
    }

    /**
     * Apply [transform] to the CURRENT (decayed) profile, then re-anchor: the decayed needs become the new
     * base and the clock resets to now. So editing a field or topping up a need never drops accrued decay.
     */
    private fun mutateLife(transform: (LifeProfile) -> LifeProfile) {
        scope.launch {
            ensureLoaded()
            lifeBase = transform(currentLife())
            needsAnchorMs = System.currentTimeMillis()
            _life.value = lifeBase
            scheduleFlush()
        }
    }

    /**
     * Spend survival needs on a physical action ([kind]). Applies [cost] to the CURRENT decayed needs and
     * re-anchors — the SAME capture-decayed-then-re-anchor discipline as [mutateLife], so the accrued
     * time-decay is never double-counted. Emits a one-shot [ExertReport] for the UI. Only touches the needs
     * (never HP, never a [LifeEffect]). Call from inside an already-loaded [scope] coroutine.
     */
    private fun exert(kind: ExertKind, cost: ExertCost) {
        if (cost.isEmpty) return
        lifeBase = Exertion.apply(currentLife(), cost)
        needsAnchorMs = System.currentTimeMillis()
        _life.value = lifeBase
        _exertReport.tryEmit(ExertReport(kind, cost))
        scheduleFlush()
    }

    fun setHeight(cm: Int) = mutateLife { LifeStats.withHeight(it, cm) }
    fun setWeight(kg: Int) = mutateLife { LifeStats.withWeight(it, kg) }
    fun setAge(years: Int) = mutateLife { LifeStats.withAge(it, years) }
    fun setRealMoney(amount: Double) = mutateLife { LifeStats.withMoney(it, amount) }
    fun setCurrency(code: String) = mutateLife { it.copy(currency = code.take(4).ifBlank { "USD" }) }
    fun setMood(mood: Int) = mutateLife { LifeStats.withMood(it, mood) }
    fun setName(name: String) = mutateLife { LifeStats.withName(it, name) }
    fun setWellRead(v: Int) = mutateLife { LifeStats.withWellRead(it, v) }
    fun setFitness(v: Int) = mutateLife { LifeStats.withFitness(it, v) }
    fun setCommunity(v: Int) = mutateLife { LifeStats.withCommunity(it, v) }

    /**
     * Feed the cumulative step-counter reading (steps since boot). Maintains a per-day baseline so today's
     * steps = latest − baseline; a lower reading (device reboot) or a new local day re-baselines. Updates
     * the profile's `stepsToday` (no need re-anchor — steps aren't a decaying need). Aggregate + on-device.
     */
    fun setStepCounter(raw: Long) {
        scope.launch {
            ensureLoaded()
            val today = currentDay()
            if (stepDay != today || stepBaseline < 0 || raw < stepBaseline) {
                stepDay = today
                stepBaseline = raw
            }
            val steps = (raw - stepBaseline).coerceAtLeast(0).toInt()
            if (steps != lifeBase.stepsToday) {
                lifeBase = lifeBase.copy(stepsToday = steps)
                _life.value = currentLife()
                scheduleFlush()
            }
        }
    }

    /** Top up hydration (a drink). */
    fun drink() = mutateLife { LifeStats.drink(it) }
    /** Freshen up (a wash). */
    fun wash() = mutateLife { LifeStats.wash(it) }
    /** Rest up (restore energy). */
    fun rest() = mutateLife { LifeStats.rest(it) }
    /** Eat (restore nourishment). */
    fun eat() = mutateLife { LifeStats.eat(it) }
    /** Brush your teeth (a partial hygiene lift). */
    fun brushTeeth() = mutateLife { LifeStats.brush(it) }

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
            startedAtMs = startedAtMs,
            heightCm = lifeBase.heightCm, weightKg = lifeBase.weightKg, ageYears = lifeBase.ageYears,
            realMoney = lifeBase.realMoney, currency = lifeBase.currency,
            hydrationBase = lifeBase.hydration, hygieneBase = lifeBase.hygiene,
            energyBase = lifeBase.energy, nourishmentBase = lifeBase.nourishment,
            mood = lifeBase.mood, operatorName = lifeBase.operatorName, needsAnchorMs = needsAnchorMs,
            wellRead = lifeBase.wellRead, fitness = lifeBase.fitness, community = lifeBase.community,
            stepsToday = lifeBase.stepsToday, stepDay = stepDay, stepBaseline = stepBaseline,
            lastScavengeMs = lastScavengeMs,
            discoveredIds = discovered.toList(),
            afflictionMeter = afflictions.meterMs.mapKeys { it.key.name },
            afflicted = afflictions.active.map { it.name },
            afflictionAnchorMs = afflictionAnchorMs,
        )
        runCatching {
            context.specialDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    companion object {
        const val FLUSH_DELAY_MS = 1_000L
        /** Real-time cooldown between scavenges (ms) — stops loot-farming; the UI shows the countdown. */
        const val SCAVENGE_COOLDOWN_MS = 3 * 60_000L // 3 minutes
    }
}
