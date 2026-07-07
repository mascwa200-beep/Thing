package dev.mascwa.pulse.feature.tacnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Achievement
import dev.mascwa.pulse.core.telemetry.AgendaQuest
import dev.mascwa.pulse.core.telemetry.CalEvent
import dev.mascwa.pulse.core.telemetry.CalendarQuests
import dev.mascwa.pulse.core.telemetry.EnvContext
import dev.mascwa.pulse.core.telemetry.GameClock
import dev.mascwa.pulse.core.telemetry.GameMetrics
import dev.mascwa.pulse.core.telemetry.LifeContext
import dev.mascwa.pulse.core.telemetry.LocationKind
import dev.mascwa.pulse.core.telemetry.Perception
import dev.mascwa.pulse.core.telemetry.ProfileCategory
import dev.mascwa.pulse.core.telemetry.Quest
import dev.mascwa.pulse.core.telemetry.QuestMetrics
import dev.mascwa.pulse.core.telemetry.QuestView
import dev.mascwa.pulse.core.telemetry.LocationGate
import dev.mascwa.pulse.core.telemetry.SceneContext
import dev.mascwa.pulse.core.telemetry.WorldEvent
import dev.mascwa.pulse.core.telemetry.WorldEvents
import dev.mascwa.pulse.core.telemetry.WorldSite
import dev.mascwa.pulse.core.telemetry.WorldSites
import dev.mascwa.pulse.core.telemetry.SceneSignals
import dev.mascwa.pulse.core.telemetry.StoryDirector
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.data.sensors.Telemetry
import dev.mascwa.pulse.data.usage.UsageRepository
import dev.mascwa.pulse.data.sensors.TelemetryController
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.data.weather.LocationProvider
import dev.mascwa.pulse.data.weather.WeatherRepository
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The nearest engageable wasteland [WorldSite], how far it is, and whether you're within reach to fight it. */
data class SiteReach(val site: WorldSite, val distanceM: Double, val atSite: Boolean)

/** An active "neglect bites the phone" penalty for the STATS banner: the locked capability + how to lift it. */
data class PhonePenaltyView(val lock: String, val hint: String)

class TelemetryViewModel(
    private val controller: TelemetryController,
    private val location: LocationProvider,
    private val settings: SettingsRepository,
    private val game: dev.mascwa.pulse.data.game.SpecialGameStore,
    private val weather: WeatherRepository,
    private val usage: UsageRepository,
    private val gameWorld: dev.mascwa.pulse.data.game.GameWorldStore,
    private val profileStore: dev.mascwa.pulse.data.profile.ProfileStore,
    private val taskStore: dev.mascwa.pulse.data.tasks.TaskStore,
    private val questStore: dev.mascwa.pulse.data.game.QuestStore,
    private val sampler: dev.mascwa.pulse.data.perception.AmbientPerceptionSampler,
    private val cameraSampler: dev.mascwa.pulse.data.perception.CameraPerceptionSampler,
    private val activityEvidence: dev.mascwa.pulse.data.perception.ActivityEvidenceStore,
    private val habitStore: dev.mascwa.pulse.data.game.HabitStore,
    private val calendar: dev.mascwa.pulse.data.calendar.CalendarRepository,
    private val waypointStore: dev.mascwa.pulse.data.objectives.WaypointStore,
    private val needAutoCare: dev.mascwa.pulse.data.perception.NeedAutoCare,
) : ViewModel() {

    val telemetry: StateFlow<Telemetry> = controller.telemetry

    // --- S.P.E.C.I.A.L. game (the STAT-tab wasteland RPG) ---
    /** The live character sheet — stats, level, XP, caps, HP, unspent points. */
    val character: StateFlow<dev.mascwa.pulse.core.telemetry.Character> = game.characterFlow
    /** The most recent encounter outcome to surface, or null. */
    val gameResolution: StateFlow<dev.mascwa.pulse.core.telemetry.Resolution?> = game.resolutionFlow
    /** The encounter the player is currently facing, derived from the character. */
    val currentEncounter: StateFlow<dev.mascwa.pulse.core.telemetry.Encounter?> =
        game.characterFlow.map { game.encounterFor(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The real world the operative is standing in — bends stat checks. Rebuilt each telemetry tick. */
    private val _env = MutableStateFlow(EnvContext())
    val env: StateFlow<EnvContext> = _env.asStateFlow()

    // --- Habit check-in (self-care attestation) ---
    /** The habit due for a check-in ("Have you showered today?"), or null when nothing's due. */
    val dueCheckin: StateFlow<dev.mascwa.pulse.core.telemetry.Habit?> = habitStore.due
    /** A one-line readout of a LIVE self-care streak ("4-day self-care streak · best 9"), or "" when none is
     *  currently running (a lapsed streak reads as "" here; the `selfcare` tool still reports the lapse). */
    val selfCareStreak: StateFlow<String> = habitStore.streaksFlow
        .map { streaks ->
            val now = System.currentTimeMillis()
            val today = ((now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
            val liveBest = streaks.values.maxOfOrNull {
                dev.mascwa.pulse.core.telemetry.SelfCareStreak.currentAsOf(it, today)
            } ?: 0
            if (liveBest > 0) dev.mascwa.pulse.core.telemetry.SelfCareStreak.describe(streaks.values, today) else ""
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    private val _checkinResult = MutableStateFlow<dev.mascwa.pulse.core.telemetry.CheckinOutcome?>(null)
    /** The last answered check-in's verdict (confirmed / caught lie / …), for a transient banner. */
    val checkinResult: StateFlow<dev.mascwa.pulse.core.telemetry.CheckinOutcome?> = _checkinResult.asStateFlow()

    /** Answer the due check-in: cross-references the sensor evidence, tops up the need on a truthful yes. */
    fun answerHabit(habit: dev.mascwa.pulse.core.telemetry.Habit, claimedDone: Boolean) {
        viewModelScope.launch { _checkinResult.value = habitStore.answer(habit, claimedDone) }
    }

    fun dismissCheckinResult() { _checkinResult.value = null }

    /** The wasteland day banner ("DAY 3 · DUSK") — advances with your real life. Rebuilt each tick. */
    private val _dayBanner = MutableStateFlow("")
    val dayBanner: StateFlow<String> = _dayBanner.asStateFlow()

    /** The current wasteland day number — drives quest generation (a StateFlow so quests roll over daily). */
    private val _day = MutableStateFlow(1)

    private val _worldEvent = MutableStateFlow(WorldEvents.eventFor(1))
    /** The day's wasteland situation — favours some stats + modifies win caps (surfaced as a banner). */
    val worldEvent: StateFlow<WorldEvent> = _worldEvent.asStateFlow()

    private fun updateDayBanner() {
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        _dayBanner.value = GameClock.banner(game.startedFlow.value, now, hour)
        val day = GameClock.dayNumber(game.startedFlow.value, now)
        _day.value = day
        _worldEvent.value = WorldEvents.eventFor(day)
    }

    /**
     * What the game perceives around you — distilled from the on-device audio classifier's sound labels plus
     * the live light/motion sensors and the clock. Drives the quest director's flavour + (future) encounter
     * strategy. Neutral until the sampler produces labels; light/motion/time keep it partially live regardless.
     */
    // Coarse buckets so tiny sensor jitter doesn't re-run the distillation on every sensor event (only when
    // a perception-relevant boundary is crossed).
    private fun lightBand(lux: Float?): Int = lux?.let { if (it < 12f) 0 else if (it >= 250f) 2 else 1 } ?: -1
    private fun moving(intensity: Float): Boolean = intensity >= Perception.MOVEMENT_THRESHOLD

    // Smoothed movement intensity — an EWMA of the accelerometer's deviation from rest (|accel/g − 1|), NOT
    // the raw ~1 g magnitude. A still phone reads ~0 (so it can't be mistaken for walking), and a brief
    // handling spike is damped out. This is the fix for "it thinks I'm moving while stationary".
    private val movementIntensity: StateFlow<Float> = telemetry.scan(0f) { ewma, t ->
        ewma * MOTION_SMOOTH + kotlin.math.abs((t.accelG ?: 1f) - 1f) * (1f - MOTION_SMOOTH)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    val sceneContext: StateFlow<SceneContext> = combine(
        sampler.soundLabels,
        cameraSampler.sceneLabels,
        movementIntensity.distinctUntilChanged { a, b -> moving(a) == moving(b) },
        telemetry.distinctUntilChanged { a, b -> lightBand(a.lightLux) == lightBand(b.lightLux) },
    ) { sounds, scenes, move, t ->
        Perception.distill(
            SceneSignals(
                sceneLabels = scenes,
                soundLabels = sounds,
                lightLux = t.lightLux,
                movement = move,
                hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SceneContext())

    private data class LifeInputs(
        val interests: List<String>,
        val pending: List<String>,
        val kinds: Set<LocationKind>,
        val level: Int,
        val day: Int,
        val metrics: QuestMetrics,
    )

    // The life signals (tasks/interests/nearby/day/metrics), gathered from the stores.
    private val lifeInputs = combine(
        profileStore.entriesFlow,
        taskStore.tasksFlow,
        gameWorld.locationsFlow,
        game.metricsFlow,
        _day,
    ) { entries, tasks, locs, gm, day ->
        val interests = entries
            .filter {
                it.category == ProfileCategory.INTEREST ||
                    it.category == ProfileCategory.PROJECT ||
                    it.category == ProfileCategory.PREFERENCE
            }
            .sortedByDescending { it.weight }
            .map { it.text }
        val pending = TaskBoard.pending(tasks).map { it.title }
        LifeInputs(
            interests = interests, pending = pending, kinds = locs.map { it.kind }.toSet(),
            level = gm.level, day = day,
            metrics = QuestMetrics(gm.distanceM, gm.wins, gm.ventures, gm.placesVisited, day, pending.toSet(), gm.visitedKinds),
        )
    }

    /**
     * Drives the quest log: composes personalised quests from your real life (pending tasks, profiled
     * interests, nearby places, day/level) + the perceived [sceneContext], paired with a live [QuestMetrics]
     * snapshot, whenever any of those change. Pure + deterministic via [StoryDirector]; the [questStore]
     * then tracks completion + rewards.
     */
    private val questDriver = combine(lifeInputs, sceneContext) { inp, scene ->
        val composed = StoryDirector.compose(
            LifeContext(
                interests = inp.interests,
                pendingTasks = inp.pending,
                scene = scene,
                nearbyKinds = inp.kinds,
                day = inp.day,
                level = inp.level,
            ),
            seed = inp.day.toLong(),
        )
        composed to inp.metrics
    }

    /** The live quest log, rendered with progress + completion — for the QUESTS panel. */
    val quests: StateFlow<List<QuestView>> = questStore.quests

    private val _questDone = MutableStateFlow<Quest?>(null)
    /** The most-recently-completed quest, for a one-shot reward banner (cleared via [dismissQuestComplete]). */
    val questCompleted: StateFlow<Quest?> = _questDone.asStateFlow()
    fun dismissQuestComplete() { _questDone.value = null }

    private val _lastExertion = MutableStateFlow<dev.mascwa.pulse.data.game.ExertReport?>(null)
    /** The most-recent "that action cost you needs" report, shown as a transient STAT-panel flourish. */
    val lastExertion: StateFlow<dev.mascwa.pulse.data.game.ExertReport?> = _lastExertion.asStateFlow()

    init {
        // Surface each exertion report for ~3s, then clear (collectLatest cancels the pending clear when a
        // fresh action drains needs again, so a burst keeps showing the latest cost).
        viewModelScope.launch {
            game.exertReportFlow.collectLatest { r ->
                _lastExertion.value = r
                kotlinx.coroutines.delay(3000)
                _lastExertion.value = null
            }
        }
        // Subscribe to completions FIRST (so a first-tick completion isn't missed), then drive the log on
        // every life/metric change; grant rewards + a one-shot banner as quests complete.
        viewModelScope.launch {
            questStore.completed.collect { q ->
                game.awardQuest(q.rewardCaps, q.rewardXp)
                _questDone.value = q
            }
        }
        viewModelScope.launch {
            questDriver.collect { (composed, metrics) -> questStore.sync(composed, metrics) }
        }
        // Track whether the always-on ambient-sensing service owns the samplers. When it does, closing the
        // game screen must NOT stop them (that would kill the background sensing), so stop() defers below.
        viewModelScope.launch {
            settings.settings
                .map { it.ambientSensingAlways && it.ambientSensing }
                .distinctUntilChanged()
                .collect { alwaysOnSensing = it }
        }
    }

    /** Cached: the always-on sensing service is running the shared samplers, so the screen shouldn't stop them. */
    @Volatile private var alwaysOnSensing = false

    // --- Achievements (the grind: app usage + game progress → rewards) ---
    val unlockedAchievements: StateFlow<Set<String>> = game.unlockedFlow
    val lastUnlock: StateFlow<Achievement?> = game.lastUnlockFlow
    val gameMetrics: StateFlow<GameMetrics> = game.metricsFlow
    fun dismissUnlock() = game.dismissUnlock()

    // --- Daily objectives (the daily grind loop) ---
    val daily: StateFlow<dev.mascwa.pulse.data.game.DailyState> = game.dailyFlow
    /** Claim a completed daily objective's reward. */
    fun claimDaily(objectiveId: String) = game.claimDaily(objectiveId)

    // --- Wasteland map (real shops → game locations + real-world travel) ---
    val locations: StateFlow<List<dev.mascwa.pulse.core.telemetry.GameLocation>> = gameWorld.locationsFlow
    /** The geo-gated wasteland sites near you (settlements/tribes/gang camps/monster dens/vaults/shops). */
    val sites: StateFlow<List<dev.mascwa.pulse.core.telemetry.WorldSite>> = gameWorld.sitesFlow
    val travel: StateFlow<dev.mascwa.pulse.data.game.TravelStats> = gameWorld.travelFlow
    val scanning: StateFlow<Boolean> = gameWorld.scanningFlow

    /** Scan the area around the last GPS fix for nearby real shops → game locations. */
    fun scanArea() {
        val loc = _gps.value ?: return
        gameWorld.refresh(loc.latitude, loc.longitude)
    }

    // --- Track a wasteland site → a NAV waypoint (the gold path routes you there) ---
    /** The saved NAV waypoints — the UI reads these to tell which sites are currently tracked. */
    val trackedWaypoints: StateFlow<List<dev.mascwa.pulse.data.objectives.Waypoint>> =
        waypointStore.waypoints.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Track a wasteland [site] for navigation — drops a NAV waypoint at its real coords (the gold path on the
     * NAV map then routes you there). Idempotent: if a waypoint already sits on this spot, it's just re-activated.
     */
    fun trackSite(site: WorldSite) {
        viewModelScope.launch {
            val existing = trackedWaypoints.value.firstOrNull { atSameSpot(it.latitude, it.longitude, site.lat, site.lon) }
            if (existing != null) waypointStore.setActive(existing.id)
            else waypointStore.add(
                site.name, site.lat, site.lon,
                dev.mascwa.pulse.data.objectives.ObjectiveKind.MAIN, note = site.type.label,
            )
        }
    }

    /** Untrack a wasteland [site] — removes any NAV waypoint sitting on its spot. */
    fun untrackSite(site: WorldSite) {
        viewModelScope.launch {
            trackedWaypoints.value
                .filter { atSameSpot(it.latitude, it.longitude, site.lat, site.lon) }
                .forEach { waypointStore.remove(it.id) }
        }
    }

    /** Whether two coordinates are the same wasteland spot (~20 m — a site's exact POI coords, not a nearby one). */
    private fun atSameSpot(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Boolean =
        dev.mascwa.pulse.core.util.Geo.distanceMeters(aLat, aLon, bLat, bLon) <= 20.0
    /** Every item id ever acquired — for the ITEMS ▸ codex completion tracker. */
    val discovered: StateFlow<Set<String>> = game.discoveredFlow
    /** The most recent scavenge haul (id → count), for a one-shot readout; null when dismissed. */
    val lastScavenge: StateFlow<Map<String, Int>?> = game.lastScavengeFlow
    private val _scavengeCooldown = MutableStateFlow(0L)
    /** Milliseconds left on the scavenge cooldown (0 = ready), recomputed each game tick. */
    val scavengeCooldown: StateFlow<Long> = _scavengeCooldown.asStateFlow()
    /** Scavenge the area for rarity-weighted loot scaled by LUCK (rate-limited by the cooldown). */
    /** Scavenge for rarity-weighted loot — GEO-GATED: only while physically at a wasteland site. */
    fun scavenge() { if (siteReach.value?.atSite == true) game.scavenge() }
    /** Dismiss the one-shot scavenge-haul readout. */
    fun dismissScavenge() = game.dismissScavenge()

    /** Buy an item from a [kind] shop — reputation + the day's world event bend the price; earns standing. */
    fun buy(itemId: String, kind: dev.mascwa.pulse.core.telemetry.LocationKind) =
        game.buyAt(itemId, kind, _worldEvent.value.shopPct)
    /** Talk to a [kind] NPC — resolves the conversation with the real-world context; a win earns rep. */
    fun talk(encounter: dev.mascwa.pulse.core.telemetry.Encounter, kind: dev.mascwa.pulse.core.telemetry.LocationKind) =
        game.resolveTalk(encounter, kind, _env.value)

    // Outdoor temperature (°C) + daylight, fetched once from the weather service on start (best-effort).
    private var weatherTempC: Double? = null
    private var weatherIsDay: Boolean? = null

    /**
     * Draw the next encounter to face — GEO-GATED: only when you're physically at a wasteland site (no more
     * couch play). The site's [WorldSites.favoredStats] biases which encounter appears, unioned with what the
     * game perceives you doing, the hour of day, and the day's world event.
     */
    fun venture() {
        val reach = siteReach.value
        if (reach == null || !reach.atSite) return // fully geo-gated — travel to a site to fight
        game.venture(
            WorldSites.favoredStats(reach.site.type) +
                Perception.strategy(sceneContext.value).favored +
                dev.mascwa.pulse.core.telemetry.LifeStats.circadianFavored(_env.value.hourOfDay) +
                _worldEvent.value.favored,
        )
    }
    /**
     * Resolve a choice in the active encounter — with the real-world context, an optional CHEM, and an
     * optional [roll] (supplied by the gesture-performance grade; random if null).
     */
    fun choose(choiceIndex: Int, useItemId: String? = null, roll: Int? = null) =
        game.choose(choiceIndex, _env.value, useItemId, roll, _worldEvent.value.capsWinPct, wellKept.value)

    /** The current self-care "well-kept" edge — a small flat bonus on EVERY stat check from live habit
     *  streaks. Drives both the encounter preview odds and the actual resolve, so what you see is what you
     *  roll. */
    val wellKept: StateFlow<Int> = habitStore.streaksFlow
        .map { streaks ->
            val now = System.currentTimeMillis()
            val today = ((now + java.util.TimeZone.getDefault().getOffset(now)) / 86_400_000L).toInt()
            dev.mascwa.pulse.core.telemetry.SelfCareStreak.wellKeptBonus(streaks.values, today)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The live device motion (accelerometer/gyro), for grading encounter gestures. */
    val telemetryFlow: StateFlow<Telemetry> = telemetry
    /** Spend an unspent point on a stat. */
    fun allocate(s: dev.mascwa.pulse.core.telemetry.Special) = game.allocate(s)
    /** Choose a perk (spends a perk pick). */
    fun choosePerk(perkId: String) = game.choosePerk(perkId)
    /** Use an AID item from the pack to heal. */
    fun useItem(itemId: String) = game.useItem(itemId)
    fun useProvision(itemId: String) = game.useProvision(itemId)
    /** Sell one of an item for caps. */
    fun sellItem(itemId: String) = game.sellItem(itemId)
    /** Craft a recipe at the workbench. */
    fun craft(recipeId: String) = game.craft(recipeId)
    /** Hire a companion for caps. */
    fun hireCompanion(companionId: String) = game.hireCompanion(companionId)
    /** Dismiss the active companion. */
    fun dismissCompanion() = game.dismissCompanion()
    /** Get back up after being downed. */
    fun revive() = game.revive()
    /** Start the game over with a fresh operative (also wipes the real-life profile). */
    fun resetGame() { game.reset(); questStore.clear() }
    /** Reset the wasteland RUN — stats/level/XP/caps/inventory/achievements/quests — but KEEP your profile. */
    fun resetProgress() { game.resetProgress(); questStore.clear() }

    // --- Real-life profile (LifeStats): body metrics + real money + hydration/hygiene, on-device only ---
    /** The operator's real-life profile with hydration/hygiene decayed to now. */
    val life: StateFlow<dev.mascwa.pulse.core.telemetry.LifeProfile> = game.lifeFlow

    /** The last need the camera/mic caught you tending in real life and auto-restored — drives a live
     *  "👁 AUTO-CARE" readout on the LIFE panel. Null until the auto-care engine senses a care action. */
    val sensedCare: StateFlow<dev.mascwa.pulse.core.telemetry.NeedKind?> =
        needAutoCare.sensed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The active "neglect bites the phone" locks — a capability revoked per critically-neglected need, with
     *  the care action that lifts it. Empty unless the opt-in penalty toggle is on. Drives the STATS banner. */
    val phonePenalties: StateFlow<List<PhonePenaltyView>> = settings.settings
        .map { s ->
            if (!s.phonePenalties) emptyList()
            else s.phonePenalisedNeeds
                .mapNotNull { runCatching { dev.mascwa.pulse.core.telemetry.NeedKind.valueOf(it) }.getOrNull() }
                .mapNotNull { need ->
                    dev.mascwa.pulse.core.telemetry.PhonePenalties.DEFAULT_MAPPING[need]?.let { lock ->
                        PhonePenaltyView(lock.label, dev.mascwa.pulse.core.telemetry.PhonePenalties.restoreHint(need))
                    }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Lingering afflictions contracted from long-neglected needs (advanced on the needs tick). */
    val afflictions: StateFlow<dev.mascwa.pulse.core.telemetry.AfflictionState> = game.afflictionsFlow
    /** Wall-clock ms an active REST window runs until (0 = not resting) — drives the "RESTING · Xh left" readout. */
    val restUntil: StateFlow<Long> = game.restUntilFlow
    /** Floss — restore the flossing oral-hygiene bar. */
    fun floss() = game.floss()
    fun setHeight(cm: Int) = game.setHeight(cm)
    fun setWeight(kg: Int) = game.setWeight(kg)
    fun setAge(years: Int) = game.setAge(years)
    fun setRealMoney(amount: Double) = game.setRealMoney(amount)
    fun setCurrency(code: String) = game.setCurrency(code)
    fun setMood(mood: Int) = game.setMood(mood)
    fun setName(name: String) = game.setName(name)
    /** How well-read you are (0..100 self-report) → INTELLIGENCE. */
    fun setWellRead(v: Int) = game.setWellRead(v)
    /** How in-shape you are (0..100 self-report) → STRENGTH/ENDURANCE. */
    fun setFitness(v: Int) = game.setFitness(v)
    /** How rooted you are where you live (0..100 self-report) → CHARISMA/PERCEPTION. */
    fun setCommunity(v: Int) = game.setCommunity(v)
    /** Top up hydration (a drink). */
    fun drink() = game.drink()
    /** Freshen up (a wash). */
    fun wash() = game.wash()
    /** Rest up (restore energy). */
    fun rest() = game.rest()
    /** Eat (restore nourishment). */
    fun eat() = game.eat()
    /** Brush your teeth (a partial hygiene lift). */
    fun brushTeeth() = game.brushTeeth()

    // --- Your wasteland tale (global renown) — curate it, or let it grow from your deeds ---
    /** Your current tale: renown + the curated archetype. Bends shops + CHARISMA. */
    val legend: StateFlow<dev.mascwa.pulse.core.telemetry.Legend> =
        game.characterFlow.map { it.legend }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), dev.mascwa.pulse.core.telemetry.Legend())
    /** Curate your tale — (re)seed renown from a chosen archetype (null / blank = let it grow on its own). */
    fun curateTale(archetype: dev.mascwa.pulse.core.telemetry.Archetype?) = game.curateTale(archetype)

    // --- Calendar agenda: your real upcoming events → wasteland objectives (on-device, permission-gated) ---
    private var calEvents: List<CalEvent> = emptyList()
    private var lastCalLoadMs = 0L
    private val _agenda = MutableStateFlow<List<AgendaQuest>>(emptyList())
    /** Upcoming real calendar events, framed as time-boxed wasteland objectives (empty without permission). */
    val agenda: StateFlow<List<AgendaQuest>> = _agenda.asStateFlow()

    /** Reload the calendar off the main thread (READ_CALENDAR-gated, defensive) and recompute the agenda. */
    fun refreshAgenda() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            lastCalLoadMs = now
            calEvents = withContext(Dispatchers.IO) { runCatching { calendar.upcoming(now) }.getOrDefault(emptyList()) }
            _agenda.value = CalendarQuests.compose(calEvents, System.currentTimeMillis())
        }
    }

    /** Distil the live telemetry snapshot + cached weather + clock into the game's [EnvContext]. */
    private fun buildEnv(t: Telemetry): EnvContext {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val online = t.netType != "OFFLINE" && t.netType != "—"
        return EnvContext(
            outdoorTempC = weatherTempC,
            isDay = weatherIsDay ?: (hour in 7..19),
            lightLux = t.lightLux,
            pressureHpa = t.pressureHpa,
            altitudeM = t.pressureAltitudeM,
            movement = movementIntensity.value, // smoothed deviation-from-rest, not raw ~1 g magnitude
            batteryPct = t.batteryPct,
            charging = t.charging,
            hourOfDay = hour,
            online = online,
        )
    }

    private fun toCelsius(t: Double, unitSymbol: String): Double =
        if (unitSymbol.contains("F")) (t - 32.0) * 5.0 / 9.0 else t

    /** The user's chosen operator portrait (content URI) for the STATUS>CND section, or "" if none. */
    val portraitUri: StateFlow<String> = settings.settings
        .map { it.operatorPortraitUri }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setPortrait(uri: String) = viewModelScope.launch {
        settings.update { it.copy(operatorPortraitUri = uri) }
    }

    private val _gps = MutableStateFlow<DeviceLocation?>(null)
    val gps: StateFlow<DeviceLocation?> = _gps.asStateFlow()

    /**
     * The nearest wasteland site you can fight/engage at (settlement/tribe/ruins/gang camp/monster den/vault),
     * with its distance and whether you're physically within reach. Drives the geo-gated encounter loop — you
     * can only venture when this is at-reach. Recomputed as the GPS fix or the scanned sites change. (Declared
     * after [_gps] so its initializer sees an initialized flow.)
     */
    val siteReach: StateFlow<SiteReach?> = combine(_gps, sites) { gps, list ->
        list.filter { WorldSites.spawnsEncounter(it.type) }
            .mapNotNull { s -> LocationGate.distanceTo(gps?.latitude, gps?.longitude, s)?.let { s to it } }
            .minByOrNull { it.second }
            ?.let { (s, d) -> SiteReach(s, d, d <= LocationGate.REACH_RADIUS_M) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var ticker: Job? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun start() {
        controller.start()
        _env.value = buildEnv(controller.telemetry.value)
        refreshAgenda() // pull the real calendar into the wasteland agenda (no-op without READ_CALENDAR)
        habitStore.refresh() // surface any due self-care check-in ("showered today?")
        // Ambient camera/mic sensing runs only when the owner has it enabled (privacy/battery control); the
        // samplers are still individually no-ops without their permissions.
        viewModelScope.launch {
            val cfg = runCatching { settings.current() }.getOrNull()
            if (cfg == null || cfg.ambientSensing) {
                val mic = cfg?.ambientMic ?: true
                val cam = cfg?.ambientCamera ?: true
                if (mic) sampler.start()       // on-device ambient hearing (no-op without mic)
                if (cam) cameraSampler.start()  // on-device ambient seeing (no-op without the camera permission)
                if (mic || cam) activityEvidence.start() // detect real self-care → the lie-catcher's history
            }
        }
        // Feed real app-usage into the achievement engine (drives "Operator Online"/"Explorer"/… + rewards).
        viewModelScope.launch {
            runCatching {
                val snap = usage.snapshot()
                game.setUsageMetrics(snap.totalEvents, snap.features.size)
            }
        }
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            launch {
                if (!location.hasPermission()) return@launch
                val loc = location.current() ?: return@launch
                _gps.value = loc
                gameWorld.onLocation(loc.latitude, loc.longitude, loc.accuracyM, loc.speedMps, loc.altitudeM)
                // Best-effort outdoor temperature (in °C) + daylight, so the wasteland reacts to real weather.
                runCatching {
                    val w = weather.fetch(loc.latitude, loc.longitude, loc.name, force = false).data
                    w.current?.let { cur ->
                        cur.temperature?.let { weatherTempC = toCelsius(it, w.tempUnitSymbol) }
                        weatherIsDay = cur.isDay
                    }
                }
            }
            // Real-world travel (distance/places/mode/elevation/cells) → travel + transport achievements.
            launch {
                gameWorld.travelFlow.collect {
                    game.setTravelMetrics(
                        it.distanceM.toInt(), it.placesVisited,
                        it.walkM.toInt(), it.runM.toInt(), it.cycleM.toInt(), it.driveM.toInt(),
                        it.elevationM.toInt(), it.cellsExplored, it.visitedKinds,
                    )
                }
            }
            // Poll GPS periodically so walking accumulates distance + reaches nearby locations.
            launch {
                while (true) {
                    delay(GPS_POLL_MS)
                    if (location.hasPermission()) {
                        location.current()?.let {
                            _gps.value = it
                            gameWorld.onLocation(it.latitude, it.longitude, it.accuracyM, it.speedMps, it.altitudeM)
                        }
                    }
                }
            }
            updateDayBanner()
            while (true) {
                controller.refreshSystem()
                _env.value = buildEnv(controller.telemetry.value)
                updateDayBanner()
                game.refreshNeeds(_env.value) // real weather/time/motion/charging drive the needs decay
                controller.telemetry.value.stepCounterRaw?.let { game.setStepCounter(it) } // real steps → activity buff
                _agenda.value = CalendarQuests.compose(calEvents, System.currentTimeMillis()) // refresh countdowns
                if (System.currentTimeMillis() - lastCalLoadMs > CAL_RELOAD_MS) refreshAgenda() // reload events ~5-minly
                gameWorld.addPlayTime(1500) // time spent on the STAT tab = time played
                _scavengeCooldown.value = (dev.mascwa.pulse.data.game.SpecialGameStore.SCAVENGE_COOLDOWN_MS -
                    (System.currentTimeMillis() - game.lastScavengeMsFlow.value)).coerceAtLeast(0L)
                pushLog()
                delay(1500)
            }
        }
    }

    fun stop() {
        controller.stop()
        // Release the mic/camera when the game screen isn't visible — UNLESS the always-on sensing service
        // owns them, in which case leave them running so background attestation keeps its history.
        if (!alwaysOnSensing) {
            sampler.stop()
            cameraSampler.stop()
            activityEvidence.stop()
        }
        ticker?.cancel()
    }

    private fun pushLog() {
        val t = controller.telemetry.value
        val ts = fmt.format(Date())
        val baro = t.pressureHpa?.let { "%.1f".format(it) } ?: "--"
        val mag = t.magneticUt?.roundToInt()?.toString() ?: "--"
        val g = t.accelG?.let { "%.2f".format(it) } ?: "--"
        val lux = t.lightLux?.roundToInt()?.toString() ?: "--"
        val line = "[$ts] baro=${baro}hPa mag=${mag}uT g=$g lux=$lux net=${t.netType}"
        _log.update { (listOf(line) + it).take(40) }
    }

    private companion object {
        const val GPS_POLL_MS = 10_000L
        // EWMA retention for the movement estimator (higher = smoother/slower; owner-tunable on the Pixel).
        const val MOTION_SMOOTH = 0.8f
        // How often to re-read the calendar (countdowns recompute every tick; events reload only this often).
        const val CAL_RELOAD_MS = 300_000L
    }
}
