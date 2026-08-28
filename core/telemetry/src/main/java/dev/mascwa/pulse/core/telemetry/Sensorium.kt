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
/**
 * How bright it is around the phone.
 *
 * ⚠️ [UNKNOWN] is not a shade, it is the absence of a measurement — **many phones ship no
 * ambient-light sensor at all**, and before this existed a phone without one reported [DIM] forever.
 * That is a fabricated reading, and it did not stay in one place: it went onto the scanner, into the
 * one-line environment read the Computer is given every turn, and into ORACLE's rules. A person on a
 * phone with no light sensor was being told, in a confident sentence, how bright their room was.
 */
enum class LightState { DARK, DIM, LIT, BRIGHT, SUNLIGHT, UNKNOWN }
enum class PressureTrend { PLUNGING, FALLING, STEADY, RISING }

/** The fused environmental read — what the scanner shows, Computer knows, and ORACLE reasons over. */
data class EnvReading(
    val setting: EnvSetting = EnvSetting.UNKNOWN,
    val motion: MotionState = MotionState.STILL,
    val social: SocialDensity = SocialDensity.ALONE,
    val noise: NoiseProfile = NoiseProfile.QUIET,
    val light: LightState = LightState.UNKNOWN,
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
        // ⚠️ An unknown brightness contributes NOTHING to this line rather than a word for it.
        // "Indoors · still · quiet · alone" is honest on a phone with no light sensor; "· unknown ·"
        // in the middle of it reads as a fault, and "dim" — what this used to say — is a lie.
        when (light) {
            LightState.DARK -> parts += "dark"
            LightState.DIM -> parts += "dim"
            LightState.LIT -> parts += "lit"
            LightState.BRIGHT -> parts += "bright"
            LightState.SUNLIGHT -> parts += "sunlight"
            LightState.UNKNOWN -> {}
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
            // ⚠️ Not DIM. A null lux means nothing measured it — no sensor, or no event yet — and
            // guessing the middle of the range is how a phone comes to report a room it cannot see.
            f.lightLux == null -> LightState.UNKNOWN
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
     * Should heavy polling stand down? The one answer, so nothing has to keep its own.
     *
     * ⚠️ **This exists because there were two ladders and they had drifted.** `ActiveMatrixService`
     * had its own `BATTERY_RECOVER_PCT = 25` and its own conserve condition, while
     * [BATTERY_RECOVER_PCT] here is 30 and its KDoc claimed to "mirror ActiveMatrix". Neither was
     * mirroring the other. That is the duplicated-definition drift this repository has corrected
     * repeatedly, and the fix is one function rather than two agreeing constants.
     *
     * ⚠️ Unifying moves ActiveMatrix's recovery from 25% to 30%, so it now stays conserving slightly
     * longer. That is a real behaviour change and it is in the safe direction: the cost of recovering
     * late is a few minutes of reduced polling, and the cost of recovering early on a phone hovering
     * at the boundary is flapping.
     */
    fun conserveBattery(
        batteryPct: Int,
        charging: Boolean,
        previouslyConserving: Boolean,
        standDownPct: Int = BATTERY_STANDDOWN_PCT,
    ): Boolean {
        if (charging) return false
        // ⚠️ `DeviceContext.batteryPct` is -1 when the level cannot be read, and an unreadable
        // battery is not a flat one. Without this the stack conserves for ever on a phone whose
        // gauge is broken — the same absent-probe rule DeviceClass is built on.
        if (batteryPct < 0) return previouslyConserving
        if (batteryPct <= standDownPct.coerceIn(1, BATTERY_CONSERVE_PCT)) return true
        return previouslyConserving && batteryPct < BATTERY_RECOVER_PCT
    }

    /**
     * Which level the service should run at. [previous] provides hysteresis — a device hovering at the
     * conserve boundary must not flap between levels; recovery from CONSERVE/STANDDOWN needs
     * [BATTERY_RECOVER_PCT] or a charger.
     *
     * ⚠️ **[tier] and [pressure] are a CEILING, never a floor, and only ever downward.** A weak or
     * hot phone cannot be sampled as hard as a cold flagship, but nothing here may ever *promote* a
     * device the battery ladder has already throttled. Both default to the strongest reading, so
     * every existing call site keeps today's behaviour exactly — and a [DeviceClass.Tier.FULL]
     * phone at [DeviceClass.Pressure.NONE] is byte-for-byte unchanged.
     *
     * ⚠️ There is deliberately **one** ladder. Adding a second, parallel device ladder beside this
     * one is precisely the mistake [conserveBattery] above exists to undo.
     *
     * @param standDownPct the battery percentage at which sensing stops entirely. A parameter rather
     *   than the constant so the user's own `standDownBatteryPct` setting can reach it — that
     *   setting had been written and read by nothing at all.
     */
    fun level(
        previous: SenseLevel,
        batteryPct: Int,
        charging: Boolean,
        powerSave: Boolean,
        screenOffMinutes: Int,
        movement: Float,
        tier: DeviceClass.Tier = DeviceClass.Tier.FULL,
        pressure: DeviceClass.Pressure = DeviceClass.Pressure.NONE,
        standDownPct: Int = BATTERY_STANDDOWN_PCT,
    ): SenseLevel {
        val throttled = previous == SenseLevel.CONSERVE || previous == SenseLevel.STANDDOWN
        val standDown = standDownPct.coerceIn(1, BATTERY_CONSERVE_PCT)
        val battery = when {
            charging -> null
            // ⚠️ **-1 means the level could not be read, and this was a live defect.**
            // `DeviceContext.batteryPct` documents the sentinel and `isCriticalBattery` guards it
            // with `in 0..9`; this ladder compared it unguarded, so a phone with an unreadable
            // gauge stood the entire sensing stack down for ever and the notification blamed the
            // battery. Unknown holds whatever the hysteresis already decided and asserts nothing.
            batteryPct < 0 -> if (throttled) previous else null
            batteryPct <= standDown -> SenseLevel.STANDDOWN
            batteryPct <= BATTERY_CONSERVE_PCT || powerSave -> SenseLevel.CONSERVE
            throttled && batteryPct < BATTERY_RECOVER_PCT -> SenseLevel.CONSERVE
            else -> null
        }
        val settled = if (screenOffMinutes >= SETTLED_SCREEN_OFF_MIN && movement < HANDLING_THRESHOLD) {
            SenseLevel.SETTLED
        } else {
            SenseLevel.NOMINAL
        }
        // Higher ordinal is more throttled, so the most throttled opinion wins. `battery` is null
        // when the battery has nothing to say (charging, or comfortably above every threshold), and
        // the stillness reading stands in for it then.
        return listOf(battery ?: settled, ceilingFor(tier), ceilingFor(pressure)).maxBy { it.ordinal }
    }

    /**
     * The hardest this device may be sampled, whatever the battery says.
     *
     * A cheap phone is not merely short of battery — the mic classifier and the camera burst cost
     * CPU and memory it does not have. ⚠️ [DeviceClass.Tier.MODEST] gets no ceiling: a 6 GB phone
     * runs this comfortably, and throttling it would be adapting to a problem it does not have.
     */
    fun ceilingFor(tier: DeviceClass.Tier): SenseLevel = when (tier) {
        DeviceClass.Tier.FULL, DeviceClass.Tier.MODEST -> SenseLevel.NOMINAL
        DeviceClass.Tier.LEAN -> SenseLevel.SETTLED
        DeviceClass.Tier.MINIMAL -> SenseLevel.CONSERVE
    }

    /**
     * ⚠️ [DeviceClass.Pressure.WARM] gets no ceiling. Warm is what an ordinary busy phone reports,
     * and standing the sensing down every time somebody watches a video would make the feature
     * useless on exactly the thin phones it is meant to survive on.
     */
    fun ceilingFor(pressure: DeviceClass.Pressure): SenseLevel = when (pressure) {
        DeviceClass.Pressure.NONE, DeviceClass.Pressure.WARM -> SenseLevel.NOMINAL
        DeviceClass.Pressure.HOT -> SenseLevel.CONSERVE
        DeviceClass.Pressure.CRITICAL -> SenseLevel.STANDDOWN
    }

    /**
     * Why the service is at this level, for the notification. "Conserving battery" was said whatever
     * the cause — a degradation the user can see but not account for is barely better than a silent
     * one.
     */
    fun reasonFor(
        level: SenseLevel,
        tier: DeviceClass.Tier,
        pressure: DeviceClass.Pressure,
        batteryPct: Int,
        charging: Boolean,
        powerSave: Boolean,
    ): String? = when {
        level == SenseLevel.NOMINAL -> null
        pressure == DeviceClass.Pressure.CRITICAL -> "the phone is too hot"
        pressure == DeviceClass.Pressure.HOT -> "the phone is warm"
        // ⚠️ The bound is the RECOVERY threshold, not the conserve one. A phone climbing back
        // through 27% is still throttled by the hysteresis above, and a sentence that stopped at 25
        // would leave that band explaining itself with silence.
        !charging && batteryPct in 0 until BATTERY_RECOVER_PCT -> "battery is at $batteryPct%"
        // Holding whatever the hysteresis last decided, because the gauge stopped answering.
        batteryPct < 0 -> "the battery level cannot be read"
        powerSave && !charging -> "the battery saver is on"
        ceilingFor(tier).ordinal >= level.ordinal && tier != DeviceClass.Tier.FULL &&
            tier != DeviceClass.Tier.MODEST -> "this phone has little to spare"
        level == SenseLevel.SETTLED -> "nothing has moved for a while"
        else -> null
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
