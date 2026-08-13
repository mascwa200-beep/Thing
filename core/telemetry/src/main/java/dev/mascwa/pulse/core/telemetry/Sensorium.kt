package dev.mascwa.pulse.core.telemetry

/**
 * SENSORIUM — the sensory cortex of the app's cognitive stack. The Android layer samples everything the
 * hardware can give (mic sound labels, camera scene labels, light, barometer, magnetometer, motion,
 * GPS speed, WiFi/BT radio density, proximity) and this PURE, CI-tested core fuses each [SenseFrame]
 * into one [EnvReading]: where you are, what's happening around you, how loud/bright/crowded it is,
 * whether the weather is turning. Deterministic keyword + threshold logic throughout, so CI gates it.
 *
 * Privacy invariant (unchanged from the original perception layer this rebuilds): only text labels and
 * numbers ever reach this core — no pixels, no audio. The classify-then-discard rule lives in the app
 * layer; nothing raw is persisted or transmitted.
 *
 * Lessons carried from the deleted `Perception.kt` (b9ba600~1): movement is an EWMA of |accelG − 1|
 * (a still phone reads ~0; raw magnitude sits at ~1 g and misreads handling as walking), and VEHICLE
 * requires real motion — engine sounds while stationary are not transit.
 */

/** One classifier output — a label with a confidence in 0f..1f. */
data class PerceptLabel(val label: String, val confidence: Float)

/** Everything the sensing layer knows at one moment. All-nullable: a missing signal degrades the
 *  reading instead of blocking it (no camera permission → scene labels absent → setting leans on
 *  sound/light; Location off → wifi count null → crowd leans on BT/voices). */
data class SenseFrame(
    val soundLabels: List<PerceptLabel> = emptyList(),
    val sceneLabels: List<PerceptLabel> = emptyList(),
    val lightLux: Float? = null,
    val pressureHpa: Float? = null,
    /** Pressure change over roughly the last 3 hours (caller computes from its history buffer). */
    val pressureDeltaHpa: Float? = null,
    val magneticUt: Float? = null,
    /** Smoothed movement intensity — EWMA(|accelG − 1|), ~0 at rest. Null = unknown → still. */
    val movement: Float? = null,
    /** GPS ground speed, when a fix exists. */
    val speedMps: Float? = null,
    val wifiApCount: Int? = null,
    val btDeviceCount: Int? = null,
    val proximityNear: Boolean? = null,
    val hourOfDay: Int = 12,
    val weekend: Boolean = false,
)

enum class EnvSetting { INDOOR, OUTDOOR, VEHICLE, UNKNOWN }
enum class MotionState { STILL, HANDLING, WALKING, DRIVING }
enum class SocialDensity { ALONE, FEW, CROWD }
enum class NoiseProfile { SILENT, QUIET, CALM, LIVELY, LOUD }
enum class LightState { DARK, DIM, LIT, BRIGHT, SUNLIGHT }
enum class PressureTrend { PLUNGING, FALLING, STEADY, RISING }

/** The fused environmental read — what the scanner shows, Computer knows, and ORACLE reasons over. */
data class EnvReading(
    val setting: EnvSetting = EnvSetting.UNKNOWN,
    val motion: MotionState = MotionState.STILL,
    val social: SocialDensity = SocialDensity.ALONE,
    val noise: NoiseProfile = NoiseProfile.QUIET,
    val light: LightState = LightState.DIM,
    val pressureTrend: PressureTrend? = null,
    val sceneTags: List<String> = emptyList(),
    val soundTags: List<String> = emptyList(),
    /** True when mic/camera labels were present in the frame — a reading built from them is richer
     *  than one inferred from bare sensors, and consumers may say so. */
    val heard: Boolean = false,
    val seen: Boolean = false,
) {
    /** One line for the scanner header / Computer's context: "Indoors · still · quiet · lit · alone". */
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += when (setting) {
            EnvSetting.INDOOR -> "Indoors"
            EnvSetting.OUTDOOR -> "Outdoors"
            EnvSetting.VEHICLE -> "In transit"
            EnvSetting.UNKNOWN -> "Somewhere"
        }
        parts += when (motion) {
            MotionState.STILL -> "still"
            MotionState.HANDLING -> "in hand"
            MotionState.WALKING -> "walking"
            MotionState.DRIVING -> "moving fast"
        }
        parts += noise.name.lowercase()
        parts += when (light) {
            LightState.DARK -> "dark"
            LightState.DIM -> "dim"
            LightState.LIT -> "lit"
            LightState.BRIGHT -> "bright"
            LightState.SUNLIGHT -> "sunlight"
        }
        parts += when (social) {
            SocialDensity.ALONE -> "alone"
            SocialDensity.FEW -> "company nearby"
            SocialDensity.CROWD -> "crowded"
        }
        when (pressureTrend) {
            PressureTrend.PLUNGING -> parts += "pressure plunging"
            PressureTrend.FALLING -> parts += "pressure falling"
            else -> {}
        }
        return parts.joinToString(" · ")
    }
}

object Sensorium {

    // ---- keyword vocabularies (substring-matched against lower-cased classifier labels) ----
    private val VEHICLE = setOf(
        "car", "vehicle", "bus", "train", "dashboard", "steering", "traffic", "road", "highway",
        "subway", "cabin", "aircraft",
    )
    private val OUTDOOR = setOf(
        "street", "sky", "tree", "grass", "field", "park", "outdoor", "mountain", "beach",
        "sidewalk", "forest", "sun", "cloud", "fountain", "seashore",
    )
    private val INDOOR = setOf(
        "room", "office", "desk", "screen", "monitor", "kitchen", "ceiling", "wall", "indoor",
        "furniture", "bed", "couch", "shelf", "table", "bookcase", "lamp", "refrigerator",
    )
    private val SPEECH = setOf("speech", "talk", "voice", "conversation", "narration")
    private val CROWD_SOUND = setOf("crowd", "cheer", "chatter", "applause", "babble", "hubbub")
    private val TRAFFIC_SOUND = setOf("traffic", "engine", "vehicle", "car", "motor", "horn")
    private val LOUD_SOUND = setOf("music", "shout", "yell", "drill", "machin", "alarm", "siren", "drum")
    private val CALM_SOUND = setOf("bird", "wind", "rain", "insect", "waves", "rustle", "tick", "hum", "fan")
    private val SILENT_SOUND = setOf("silence", "quiet")

    // ---- thresholds (owner-tunable) ----
    /** Movement EWMA at/above this ≈ genuinely moving (the carried-forward Perception fix). */
    const val MOVEMENT_THRESHOLD = 0.09f
    /** Movement EWMA at/above this but below [MOVEMENT_THRESHOLD] ≈ the phone is being handled. */
    const val HANDLING_THRESHOLD = 0.03f
    /** GPS speed at/above this ≈ vehicular regardless of anything else (~22 km/h). */
    const val DRIVING_SPEED_MPS = 6.0f
    /** Walking tops out around here — motion + speed below it stays WALKING. */
    const val WALK_SPEED_MPS = 3.0f

    const val LUX_DARK = 8f
    const val LUX_DIM = 40f
    const val LUX_LIT = 400f
    const val LUX_BRIGHT = 3000f

    /** BT devices in one scan burst that suggest people (phones/wearables) around you. */
    const val BT_FEW = 3
    const val BT_CROWD = 10

    /** 3-hour pressure falls: −1 hPa is a trend, −3 hPa is a storm-front plunge. */
    const val PRESSURE_FALL_HPA = -1.0f
    const val PRESSURE_PLUNGE_HPA = -3.0f
    const val PRESSURE_RISE_HPA = 1.0f

    /** Labels below this confidence are noise, not signal. */
    const val MIN_CONF = 0.30f

    private fun List<PerceptLabel>.strong(): List<String> =
        filter { it.confidence >= MIN_CONF }.map { it.label.lowercase() }

    private fun List<String>.hits(vocab: Set<String>): Int =
        count { tag -> vocab.any { tag.contains(it) } }

    /** Fuse one frame into the environmental read. Deterministic; every input optional. */
    fun distill(f: SenseFrame): EnvReading {
        val scene = f.sceneLabels.strong()
        val sound = f.soundLabels.strong()
        val movement = f.movement ?: 0f
        val speed = f.speedMps ?: 0f
        val moving = movement >= MOVEMENT_THRESHOLD || speed >= WALK_SPEED_MPS

        // Motion first — several other reads depend on it.
        val motion = when {
            speed >= DRIVING_SPEED_MPS -> MotionState.DRIVING
            moving -> MotionState.WALKING
            movement >= HANDLING_THRESHOLD -> MotionState.HANDLING
            else -> MotionState.STILL
        }

        val vehicleHits = scene.hits(VEHICLE) + sound.hits(TRAFFIC_SOUND)
        val outdoorHits = scene.hits(OUTDOOR)
        val indoorHits = scene.hits(INDOOR)
        val setting = when {
            motion == MotionState.DRIVING -> EnvSetting.VEHICLE
            // Engine sounds while stationary are NOT transit — vehicle needs real motion.
            vehicleHits >= 1 && moving -> EnvSetting.VEHICLE
            outdoorHits > indoorHits && outdoorHits > 0 -> EnvSetting.OUTDOOR
            indoorHits > 0 -> EnvSetting.INDOOR
            outdoorHits > 0 -> EnvSetting.OUTDOOR
            // No camera: very bright ambient light strongly suggests outdoors.
            f.lightLux != null && f.lightLux >= LUX_BRIGHT -> EnvSetting.OUTDOOR
            else -> EnvSetting.UNKNOWN
        }

        val speechHits = sound.hits(SPEECH)
        val crowdHits = sound.hits(CROWD_SOUND)
        val bt = f.btDeviceCount ?: 0
        val social = when {
            crowdHits >= 1 || bt >= BT_CROWD -> SocialDensity.CROWD
            speechHits >= 1 || bt >= BT_FEW -> SocialDensity.FEW
            else -> SocialDensity.ALONE
        }

        val loudHits = sound.hits(LOUD_SOUND) + crowdHits + sound.hits(TRAFFIC_SOUND)
        val calmHits = sound.hits(CALM_SOUND)
        val noise = when {
            sound.isEmpty() -> NoiseProfile.QUIET // mic off/silent sip — assume quiet, don't invent
            sound.hits(SILENT_SOUND) >= 1 && loudHits == 0 -> NoiseProfile.SILENT
            loudHits >= 3 -> NoiseProfile.LOUD
            loudHits >= 1 || speechHits >= 2 -> NoiseProfile.LIVELY
            speechHits >= 1 || calmHits >= 1 -> NoiseProfile.CALM
            else -> NoiseProfile.QUIET
        }

        val light = when {
            f.lightLux == null -> LightState.DIM
            f.lightLux < LUX_DARK -> LightState.DARK
            f.lightLux < LUX_DIM -> LightState.DIM
            f.lightLux < LUX_LIT -> LightState.LIT
            f.lightLux < LUX_BRIGHT -> LightState.BRIGHT
            else -> LightState.SUNLIGHT
        }

        val trend = f.pressureDeltaHpa?.let {
            when {
                it <= PRESSURE_PLUNGE_HPA -> PressureTrend.PLUNGING
                it <= PRESSURE_FALL_HPA -> PressureTrend.FALLING
                it >= PRESSURE_RISE_HPA -> PressureTrend.RISING
                else -> PressureTrend.STEADY
            }
        }

        return EnvReading(
            setting = setting,
            motion = motion,
            social = social,
            noise = noise,
            light = light,
            pressureTrend = trend,
            sceneTags = scene.take(5),
            soundTags = sound.take(5),
            heard = f.soundLabels.isNotEmpty(),
            seen = f.sceneLabels.isNotEmpty(),
        )
    }

    // ---- the adaptive throttle ladder ----

    enum class SenseLevel { NOMINAL, SETTLED, CONSERVE, STANDDOWN }

    /** Sampling cadence for one level. 0 = off; camera 0 with [cameraOnTrigger] true = trigger-only. */
    data class Cadence(
        val micIntervalSec: Int,
        val cameraIntervalSec: Int,
        val cameraOnTrigger: Boolean,
        val wifiIntervalSec: Int,
        val bleIntervalSec: Int,
        val fusionHeartbeatSec: Int,
    )

    const val BATTERY_CONSERVE_PCT = 25
    const val BATTERY_STANDDOWN_PCT = 9
    /** Hysteresis: once throttled, recovery needs this much battery (mirrors ActiveMatrix). */
    const val BATTERY_RECOVER_PCT = 30
    const val SETTLED_SCREEN_OFF_MIN = 30

    /**
     * Which level the service should run at. [previous] provides hysteresis — a device hovering at the
     * conserve boundary must not flap between levels; recovery from CONSERVE/STANDDOWN needs
     * [BATTERY_RECOVER_PCT] or a charger.
     */
    fun level(
        previous: SenseLevel,
        batteryPct: Int,
        charging: Boolean,
        powerSave: Boolean,
        screenOffMinutes: Int,
        movement: Float,
    ): SenseLevel {
        val throttled = previous == SenseLevel.CONSERVE || previous == SenseLevel.STANDDOWN
        if (!charging) {
            if (batteryPct <= BATTERY_STANDDOWN_PCT) return SenseLevel.STANDDOWN
            if (batteryPct <= BATTERY_CONSERVE_PCT || powerSave) return SenseLevel.CONSERVE
            if (throttled && batteryPct < BATTERY_RECOVER_PCT) return SenseLevel.CONSERVE
        }
        return if (screenOffMinutes >= SETTLED_SCREEN_OFF_MIN && movement < HANDLING_THRESHOLD) {
            SenseLevel.SETTLED
        } else {
            SenseLevel.NOMINAL
        }
    }

    fun cadenceFor(level: SenseLevel): Cadence = when (level) {
        SenseLevel.NOMINAL -> Cadence(
            micIntervalSec = 45, cameraIntervalSec = 600, cameraOnTrigger = true,
            wifiIntervalSec = 300, bleIntervalSec = 180, fusionHeartbeatSec = 30,
        )
        SenseLevel.SETTLED -> Cadence(
            micIntervalSec = 120, cameraIntervalSec = 0, cameraOnTrigger = true,
            wifiIntervalSec = 900, bleIntervalSec = 600, fusionHeartbeatSec = 60,
        )
        SenseLevel.CONSERVE -> Cadence(
            micIntervalSec = 300, cameraIntervalSec = 0, cameraOnTrigger = false,
            wifiIntervalSec = 0, bleIntervalSec = 0, fusionHeartbeatSec = 120,
        )
        SenseLevel.STANDDOWN -> Cadence(
            micIntervalSec = 0, cameraIntervalSec = 0, cameraOnTrigger = false,
            wifiIntervalSec = 0, bleIntervalSec = 0, fusionHeartbeatSec = 900,
        )
    }
}
