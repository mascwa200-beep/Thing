package dev.mascwa.pulse.feature.tacnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Achievement
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
import dev.mascwa.pulse.core.telemetry.SceneContext
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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

    /** The wasteland day banner ("DAY 3 · DUSK") — advances with your real life. Rebuilt each tick. */
    private val _dayBanner = MutableStateFlow("")
    val dayBanner: StateFlow<String> = _dayBanner.asStateFlow()

    /** The current wasteland day number — drives quest generation (a StateFlow so quests roll over daily). */
    private val _day = MutableStateFlow(1)

    private fun updateDayBanner() {
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        _dayBanner.value = GameClock.banner(game.startedFlow.value, now, hour)
        _day.value = GameClock.dayNumber(game.startedFlow.value, now)
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
            metrics = QuestMetrics(gm.distanceM, gm.wins, gm.ventures, gm.placesVisited, day, pending.toSet()),
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

    init {
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
    }

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
    val travel: StateFlow<dev.mascwa.pulse.data.game.TravelStats> = gameWorld.travelFlow
    val scanning: StateFlow<Boolean> = gameWorld.scanningFlow

    /** Scan the area around the last GPS fix for nearby real shops → game locations. */
    fun scanArea() {
        val loc = _gps.value ?: return
        gameWorld.refresh(loc.latitude, loc.longitude)
    }
    /** Buy an item from a [kind] shop — faction reputation discounts it + earns standing. */
    fun buy(itemId: String, kind: dev.mascwa.pulse.core.telemetry.LocationKind) = game.buyAt(itemId, kind)
    /** Talk to a [kind] NPC — resolves the conversation with the real-world context; a win earns rep. */
    fun talk(encounter: dev.mascwa.pulse.core.telemetry.Encounter, kind: dev.mascwa.pulse.core.telemetry.LocationKind) =
        game.resolveTalk(encounter, kind, _env.value)

    // Outdoor temperature (°C) + daylight, fetched once from the weather service on start (best-effort).
    private var weatherTempC: Double? = null
    private var weatherIsDay: Boolean? = null

    /** Draw the next encounter to face. */
    /** Draw the next encounter, biased toward what the game perceives you doing/hearing right now. */
    fun venture() = game.venture(Perception.strategy(sceneContext.value).favored)
    /**
     * Resolve a choice in the active encounter — with the real-world context, an optional CHEM, and an
     * optional [roll] (supplied by the gesture-performance grade; random if null).
     */
    fun choose(choiceIndex: Int, useItemId: String? = null, roll: Int? = null) =
        game.choose(choiceIndex, _env.value, useItemId, roll)

    /** The live device motion (accelerometer/gyro), for grading encounter gestures. */
    val telemetryFlow: StateFlow<Telemetry> = telemetry
    /** Spend an unspent point on a stat. */
    fun allocate(s: dev.mascwa.pulse.core.telemetry.Special) = game.allocate(s)
    /** Choose a perk (spends a perk pick). */
    fun choosePerk(perkId: String) = game.choosePerk(perkId)
    /** Use an AID item from the pack to heal. */
    fun useItem(itemId: String) = game.useItem(itemId)
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
    /** Start the game over with a fresh operative. */
    fun resetGame() { game.reset(); questStore.clear() }

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

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var ticker: Job? = null
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun start() {
        controller.start()
        sampler.start() // on-device ambient hearing while the game screen is open (no-op without mic)
        cameraSampler.start() // on-device ambient seeing (no-op without the camera permission)
        _env.value = buildEnv(controller.telemetry.value)
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
                gameWorld.onLocation(loc.latitude, loc.longitude, loc.accuracyM)
                // Best-effort outdoor temperature (in °C) + daylight, so the wasteland reacts to real weather.
                runCatching {
                    val w = weather.fetch(loc.latitude, loc.longitude, loc.name, force = false).data
                    w.current?.let { cur ->
                        cur.temperature?.let { weatherTempC = toCelsius(it, w.tempUnitSymbol) }
                        weatherIsDay = cur.isDay
                    }
                }
            }
            // Real-world travel (distance/places) → travel achievements.
            launch { gameWorld.travelFlow.collect { game.setTravelMetrics(it.distanceM.toInt(), it.placesVisited) } }
            // Poll GPS periodically so walking accumulates distance + reaches nearby locations.
            launch {
                while (true) {
                    delay(GPS_POLL_MS)
                    if (location.hasPermission()) {
                        location.current()?.let {
                            _gps.value = it
                            gameWorld.onLocation(it.latitude, it.longitude, it.accuracyM)
                        }
                    }
                }
            }
            updateDayBanner()
            while (true) {
                controller.refreshSystem()
                _env.value = buildEnv(controller.telemetry.value)
                updateDayBanner()
                gameWorld.addPlayTime(1500) // time spent on the STAT tab = time played
                pushLog()
                delay(1500)
            }
        }
    }

    fun stop() {
        controller.stop()
        sampler.stop() // release the mic when the game screen isn't visible
        cameraSampler.stop() // release the camera when the game screen isn't visible
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
    }
}
