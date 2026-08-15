package dev.mascwa.pulse.data.sensing

import dev.mascwa.pulse.core.telemetry.EnvAnomaly
import dev.mascwa.pulse.core.telemetry.EnvMetrics
import dev.mascwa.pulse.core.telemetry.EnvReading
import dev.mascwa.pulse.core.telemetry.EventSeverity
import dev.mascwa.pulse.core.telemetry.MemoryKind
import dev.mascwa.pulse.core.telemetry.PerceptLabel
import dev.mascwa.pulse.core.telemetry.SenseEvent
import dev.mascwa.pulse.core.telemetry.SenseFrame
import dev.mascwa.pulse.core.telemetry.Sensorium
import dev.mascwa.pulse.core.telemetry.SensoriumBaseline
import dev.mascwa.pulse.core.telemetry.SensoriumEvents
import dev.mascwa.pulse.data.memory.MemoryStreamStore
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.notifications.Notifier
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Sensorium's conductor: the service calls [step] on every heartbeat, and the engine decides —
 * from the current [Sensorium.Cadence] — which samplers are due, runs them, fuses the results through
 * the pure core, learns the baseline, extracts events, and dispatches each event to its fate
 * (urgent LCARS-board line / episodic memory / the scanner log) with per-key cool-downs so a real
 * alarm that fires the classifier every sip for minutes becomes ONE alert, not a stream.
 *
 * Camera ramping lives here, not in the sampler: a loud sound event, a big light change, or
 * motion-after-stillness marks the camera due immediately (when the level's cadence permits
 * trigger bursts), so the eyes look exactly when something is happening.
 */
class SensoriumEngine(
    private val store: SensoriumStore,
    private val audio: AmbientAudioSampler,
    private val camera: AmbientCameraSampler,
    private val fusion: SensorFusionController,
    private val memoryStream: MemoryStreamStore,
    private val notifier: Notifier,
    private val settings: SettingsRepository,
) {
    private val _reading = MutableStateFlow(EnvReading())
    /** The live fused environmental read — the scanner's centerpiece, Computer's context line. */
    val reading: StateFlow<EnvReading> = _reading.asStateFlow()

    private val _anomalies = MutableStateFlow<List<EnvAnomaly>>(emptyList())
    /** What's unusual right now vs the learned normal (empty while the baseline is young). */
    val anomalies: StateFlow<List<EnvAnomaly>> = _anomalies.asStateFlow()

    private val _normalLine = MutableStateFlow<String?>(null)
    /** "typical weekday 15:00 here: calm, alone" — the comparison line under the live reading. */
    val normalLine: StateFlow<String?> = _normalLine.asStateFlow()

    /** Service-reported arming state, surfaced honestly in the scanner. */
    val micArmed = MutableStateFlow(false)
    val camArmed = MutableStateFlow(false)
    val level = MutableStateFlow(Sensorium.SenseLevel.NOMINAL)

    // Sampler due-times (epoch ms of last run) — the engine's own cadence bookkeeping.
    private var lastMicMs = 0L
    private var lastCamMs = 0L
    private var lastWifiMs = 0L
    private var lastBleMs = 0L
    private var lastBaselineMs = 0L
    private var cameraTriggered = false

    // Latest label sets, aged out so a stale sip can't masquerade as the present.
    private var sounds: List<PerceptLabel> = emptyList()
    private var soundsAtMs = 0L
    private var scenes: List<PerceptLabel> = emptyList()
    private var scenesAtMs = 0L

    private var lastLux: Float? = null
    private var lastMagUt: Float? = null
    private var wifiCount: Int? = null
    private var bleCount: Int? = null
    private var wasStill = false

    private val eventCooldownMs = mutableMapOf<String, Long>()
    private var memoryDay = 0
    private var memoryCountToday = 0

    /** Ask the eyes to look now (scanner button / an external trigger). Honored on the next step
     *  whenever the current level permits camera work at all. */
    fun requestLook() {
        cameraTriggered = true
    }

    /**
     * One heartbeat: run whatever is due under [cadence], fuse, learn, dispatch. [micAllowed] and
     * [camAllowed] fold together the service's armed state and the user's sub-toggles.
     */
    suspend fun step(
        cadence: Sensorium.Cadence,
        micAllowed: Boolean,
        camAllowed: Boolean,
        radioAllowed: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val snap = fusion.snapshot.value

        // --- ears ---
        if (micAllowed && cadence.micIntervalSec > 0 && nowMs - lastMicMs >= cadence.micIntervalSec * 1000L) {
            lastMicMs = nowMs
            val s = audio.sip()
            if (s.isNotEmpty()) {
                sounds = s; soundsAtMs = nowMs
            }
        }

        // --- eyes: base cadence, plus trigger ramps (loud event / light jump / motion-after-stillness) ---
        val lightJump = lastLux != null && snap.lightLux != null &&
            kotlin.math.abs(snap.lightLux - lastLux!!) > LIGHT_TRIGGER_LUX
        val startedMoving = wasStill && snap.movement >= Sensorium.MOVEMENT_THRESHOLD
        if (lightJump || startedMoving) cameraTriggered = true
        val camDue = when {
            !camAllowed -> false
            cadence.cameraIntervalSec > 0 && nowMs - lastCamMs >= cadence.cameraIntervalSec * 1000L -> true
            cameraTriggered && cadence.cameraOnTrigger && nowMs - lastCamMs >= CAM_TRIGGER_MIN_GAP_MS -> true
            else -> false
        }
        if (camDue) {
            lastCamMs = nowMs
            cameraTriggered = false
            val s = camera.burst()
            if (s.isNotEmpty()) {
                scenes = s; scenesAtMs = nowMs
            }
        }

        // --- radio density ---
        if (radioAllowed && cadence.wifiIntervalSec > 0 && nowMs - lastWifiMs >= cadence.wifiIntervalSec * 1000L) {
            lastWifiMs = nowMs
            fusion.wifiApCount()?.let { wifiCount = it }
        }
        if (radioAllowed && cadence.bleIntervalSec > 0 && nowMs - lastBleMs >= cadence.bleIntervalSec * 1000L &&
            !settings.current().jarvis.vitalsTracking // never collide with the strap scanner
        ) {
            lastBleMs = nowMs
            fusion.bleBurstCount()?.let { bleCount = it }
        }

        // --- fuse ---
        if (nowMs - soundsAtMs > LABEL_TTL_MS) sounds = emptyList()
        if (nowMs - scenesAtMs > LABEL_TTL_MS) scenes = emptyList()
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val weekend = cal.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
        val frame = SenseFrame(
            soundLabels = sounds,
            sceneLabels = scenes,
            lightLux = snap.lightLux,
            pressureHpa = snap.pressureHpa,
            pressureDeltaHpa = snap.pressureDeltaHpa,
            magneticUt = snap.magneticUt,
            movement = snap.movement,
            speedMps = null, // GPS deliberately not polled here — sensing must never wake the GPS
            wifiApCount = wifiCount,
            btDeviceCount = bleCount,
            proximityNear = snap.proximityNear,
            hourOfDay = hour,
            weekend = weekend,
        )
        val fused = Sensorium.distill(frame)
        _reading.value = fused

        // --- learn + judge (throttled so dense heartbeats don't over-weight one moment) ---
        if (nowMs - lastBaselineMs >= BASELINE_GAP_MS) {
            lastBaselineMs = nowMs
            val metrics = EnvMetrics.of(fused, frame)
            val state = SensoriumBaseline.update(store.baseline(), metrics, hour, weekend)
            store.updateBaseline(state)
            _anomalies.value = SensoriumBaseline.anomalies(state, metrics, hour, weekend)
            _normalLine.value = SensoriumBaseline.describeNormal(state, hour, weekend)
        }

        // --- events ---
        val events = buildList {
            addAll(SensoriumEvents.fromSounds(sounds.takeIf { nowMs == soundsAtMs } ?: emptyList()))
            SensoriumEvents.pressureEvent(snap.pressureDeltaHpa)?.let { add(it) }
            SensoriumEvents.lightTransition(lastLux, snap.lightLux, hour)?.let { add(it) }
            SensoriumEvents.magneticEvent(lastMagUt, snap.magneticUt)?.let { add(it) }
        }
        lastLux = snap.lightLux
        lastMagUt = snap.magneticUt
        wasStill = snap.movement < Sensorium.HANDLING_THRESHOLD
        events.forEach { dispatch(it, nowMs) }
    }

    private suspend fun dispatch(event: SenseEvent, nowMs: Long) {
        val cooldown = when (event.severity) {
            EventSeverity.ALERT -> ALERT_COOLDOWN_MS
            EventSeverity.NOTABLE -> NOTABLE_COOLDOWN_MS
            EventSeverity.LOG -> LOG_COOLDOWN_MS
        }
        val last = eventCooldownMs[event.key] ?: 0L
        if (nowMs - last < cooldown) return
        eventCooldownMs[event.key] = nowMs

        store.recordEvent(event, nowMs)
        // An ALERT-class sound also asks the eyes to look at what's happening.
        if (event.severity == EventSeverity.ALERT) {
            cameraTriggered = true
            notifier.notifyUrgentLine(
                event.title, "Sensorium: ${event.detail}", "sensorium.${event.key}", red = true,
            )
        }
        if (event.severity != EventSeverity.LOG && settings.current().sensing.rememberEvents) {
            rememberEvent(event, nowMs)
        }
    }

    /** Notable moments become episodic memories — bounded to a handful a day so ambient texture can
     *  never evict real conversation memories from the capped stream. */
    private suspend fun rememberEvent(event: SenseEvent, nowMs: Long) {
        val day = (nowMs / 86_400_000L).toInt()
        if (day != memoryDay) {
            memoryDay = day; memoryCountToday = 0
        }
        if (memoryCountToday >= MEMORY_PER_DAY) return
        memoryCountToday++
        val importance = if (event.severity == EventSeverity.ALERT) 7 else 4
        memoryStream.record("Sensed: ${event.title.lowercase()} — ${event.detail}", MemoryKind.OBSERVATION, importance)
    }

    private companion object {
        const val LABEL_TTL_MS = 4 * 60_000L
        const val LIGHT_TRIGGER_LUX = 120f
        const val CAM_TRIGGER_MIN_GAP_MS = 90_000L
        const val BASELINE_GAP_MS = 2 * 60_000L
        const val ALERT_COOLDOWN_MS = 10 * 60_000L
        const val NOTABLE_COOLDOWN_MS = 30 * 60_000L
        const val LOG_COOLDOWN_MS = 60 * 60_000L
        const val MEMORY_PER_DAY = 10
    }
}
