package dev.mascwa.pulse.feature.tacnet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.Achievement
import dev.mascwa.pulse.core.telemetry.EnvContext
import dev.mascwa.pulse.core.telemetry.GameMetrics
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    /** Buy an item from a shop. */
    fun buy(itemId: String) = game.buy(itemId)
    /** Talk to an NPC — resolves the conversation with the current real-world context. */
    fun talk(encounter: dev.mascwa.pulse.core.telemetry.Encounter) = game.resolveTalk(encounter, _env.value)

    // Outdoor temperature (°C) + daylight, fetched once from the weather service on start (best-effort).
    private var weatherTempC: Double? = null
    private var weatherIsDay: Boolean? = null

    /** Draw the next encounter to face. */
    fun venture() = game.venture()
    /** Resolve a choice in the active encounter — with the current real-world context + an optional CHEM. */
    fun choose(choiceIndex: Int, useItemId: String? = null) = game.choose(choiceIndex, _env.value, useItemId)
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
    fun resetGame() = game.reset()

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
            motionG = t.accelG,
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
            while (true) {
                controller.refreshSystem()
                _env.value = buildEnv(controller.telemetry.value)
                gameWorld.addPlayTime(1500) // time spent on the STAT tab = time played
                pushLog()
                delay(1500)
            }
        }
    }

    fun stop() {
        controller.stop()
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
    }
}
