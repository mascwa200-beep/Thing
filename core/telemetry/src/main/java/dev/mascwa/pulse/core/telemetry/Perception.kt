package dev.mascwa.pulse.core.telemetry

/**
 * The perception layer for the Fallout life-sim — "the game sees and hears your real surroundings, and
 * bends its strategy from that." This is the PURE, CI-tested brain: it takes the labels an on-device
 * camera/mic classifier produces (plus light + motion + the clock) and distils them into a [SceneContext]
 * (where you are, what you're doing, who's around, how bright), then maps that to a [SceneStrategy] — which
 * S.P.E.C.I.A.L. themes to favour in the next encounter, the tempo, and a flavour line the story director
 * can use. The heavy on-device part (sampling frames/audio, running the classifier) lives in the app layer
 * and only ever feeds this text-label summary in — no pixels or audio reach the core, and none leaves the
 * device. Everything here is deterministic keyword logic so CI gates it.
 */

/** One classifier output — a label with a confidence in 0f..1f. */
data class PerceptLabel(val label: String, val confidence: Float)

/** The raw sensed frame the on-device layer produces each sample (labels are lower-cased tags). */
data class SceneSignals(
    val sceneLabels: List<PerceptLabel> = emptyList(),
    val soundLabels: List<PerceptLabel> = emptyList(),
    val lightLux: Float? = null,
    val motionG: Float? = null,
    val hourOfDay: Int = 12,
)

enum class Setting { INDOOR, OUTDOOR, VEHICLE, UNKNOWN }
enum class Activity { STILL, MOVING, COMMUTING }
enum class Social { ALONE, VOICES, CROWD }
enum class LightLevel { DARK, DIM, BRIGHT }

/** The distilled read of the player's real surroundings. */
data class SceneContext(
    val setting: Setting = Setting.UNKNOWN,
    val activity: Activity = Activity.STILL,
    val social: Social = Social.ALONE,
    val light: LightLevel = LightLevel.DIM,
    val phase: DayPhase = DayPhase.DAY,
    val sceneTags: List<String> = emptyList(),
    val soundTags: List<String> = emptyList(),
) {
    /** A one-line read for the HUD / story flavour: "Outdoors · on the move · voices nearby · dusk". */
    fun describe(): String {
        val parts = mutableListOf<String>()
        parts += when (setting) {
            Setting.INDOOR -> "Indoors"
            Setting.OUTDOOR -> "Outdoors"
            Setting.VEHICLE -> "In transit"
            Setting.UNKNOWN -> "Somewhere"
        }
        parts += when (activity) {
            Activity.STILL -> "holding position"
            Activity.MOVING -> "on the move"
            Activity.COMMUTING -> "travelling"
        }
        when (social) {
            Social.VOICES -> parts += "voices nearby"
            Social.CROWD -> parts += "in a crowd"
            Social.ALONE -> {}
        }
        if (light == LightLevel.DARK) parts += "in the dark"
        parts += phase.label.lowercase()
        return parts.joinToString(" · ")
    }
}

/** How the scene biases the game: which SPECIAL themes to favour, a tempo nudge, and a flavour line. */
data class SceneStrategy(
    val favored: Set<Special> = emptySet(),
    val tempoNudge: Int = 0,   // -1 = rest/slow, 0 = neutral, +1 = push for action
    val flavor: String = "",
)

object Perception {
    // Keyword vocabularies — an on-device classifier's labels are matched against these by substring, so
    // "office_desk", "city street", "steering_wheel" etc. all resolve. Order/priority handled in distill().
    private val VEHICLE = setOf("car", "vehicle", "bus", "train", "dashboard", "steering", "traffic", "road", "highway", "subway", "cabin")
    private val OUTDOOR = setOf("street", "sky", "tree", "grass", "field", "park", "outdoor", "building", "mountain", "beach", "sidewalk", "forest", "sun", "cloud")
    private val INDOOR = setOf("room", "office", "desk", "screen", "monitor", "kitchen", "ceiling", "wall", "indoor", "furniture", "bed", "couch", "shelf", "table")
    private val DARK = setOf("dark", "night", "shadow", "dim", "black")

    private val SPEECH = setOf("speech", "talk", "voice", "conversation", "narration")
    private val CROWD = setOf("crowd", "cheer", "chatter", "applause", "babble", "many")
    private val TRAFFIC = setOf("traffic", "engine", "vehicle", "car", "road", "motor", "horn")
    private val MUSIC = setOf("music", "song", "instrument", "singing")
    private val NATURE = setOf("bird", "wind", "water", "rain", "nature", "insect", "waves")
    private val SILENCE = setOf("silence", "quiet", "still")

    private const val MOTION_MOVING_G = 1.10f  // |accel| above this ≈ walking/handling
    private const val LUX_DARK = 12f
    private const val LUX_BRIGHT = 250f
    private const val MIN_CONF = 0.30f         // labels below this confidence are ignored

    private fun List<PerceptLabel>.strong(): List<String> =
        filter { it.confidence >= MIN_CONF }.map { it.label.lowercase() }

    private fun List<String>.hits(vocab: Set<String>): Int =
        count { tag -> vocab.any { tag.contains(it) } }

    /** Distil raw sensed signals into a [SceneContext] — deterministic keyword + threshold logic. */
    fun distill(s: SceneSignals): SceneContext {
        val scene = s.sceneLabels.strong()
        val sound = s.soundLabels.strong()

        val vehicleHits = scene.hits(VEHICLE) + sound.hits(TRAFFIC)
        val outdoorHits = scene.hits(OUTDOOR)
        val indoorHits = scene.hits(INDOOR)
        val moving = (s.motionG ?: 0f) >= MOTION_MOVING_G

        val setting = when {
            vehicleHits >= 2 || (vehicleHits >= 1 && moving) -> Setting.VEHICLE
            outdoorHits > indoorHits && outdoorHits > 0 -> Setting.OUTDOOR
            indoorHits > 0 -> Setting.INDOOR
            outdoorHits > 0 -> Setting.OUTDOOR
            else -> Setting.UNKNOWN
        }

        val activity = when {
            setting == Setting.VEHICLE -> Activity.COMMUTING
            moving -> Activity.MOVING
            else -> Activity.STILL
        }

        val crowd = sound.hits(CROWD)
        val speech = sound.hits(SPEECH)
        val social = when {
            crowd >= 1 -> Social.CROWD
            speech >= 1 -> Social.VOICES
            else -> Social.ALONE
        }

        val darkTag = scene.hits(DARK) >= 1
        val light = when {
            s.lightLux != null -> when {
                s.lightLux < LUX_DARK -> LightLevel.DARK
                s.lightLux >= LUX_BRIGHT -> LightLevel.BRIGHT
                else -> LightLevel.DIM
            }
            darkTag -> LightLevel.DARK
            else -> LightLevel.DIM
        }

        return SceneContext(
            setting = setting,
            activity = activity,
            social = social,
            light = light,
            phase = GameClock.phase(s.hourOfDay),
            sceneTags = scene.take(5),
            soundTags = sound.take(5),
        )
    }

    /**
     * Map a [SceneContext] to the game's [SceneStrategy]: your real situation decides which S.P.E.C.I.A.L.
     * approaches the wasteland throws at you next, the tempo, and a flavour line. Effects stack.
     */
    fun strategy(ctx: SceneContext): SceneStrategy {
        val favored = mutableSetOf<Special>()
        var tempo = 0
        val notes = mutableListOf<String>()

        when (ctx.setting) {
            Setting.OUTDOOR -> { favored += Special.AGILITY; favored += Special.PERCEPTION; notes += "The open wasteland stretches out." }
            Setting.VEHICLE -> { favored += Special.AGILITY; favored += Special.ENDURANCE; tempo += 1; notes += "The road hums beneath you — raiders love a moving target." }
            Setting.INDOOR -> { favored += Special.INTELLIGENCE; notes += "Walls close in; this is thinking work." }
            Setting.UNKNOWN -> {}
        }
        when (ctx.activity) {
            Activity.MOVING -> { favored += Special.ENDURANCE; tempo += 1 }
            Activity.COMMUTING -> { favored += Special.ENDURANCE; tempo += 1 }
            Activity.STILL -> {}
        }
        when (ctx.social) {
            Social.VOICES -> { favored += Special.CHARISMA; notes += "Voices carry — someone wants to talk." }
            Social.CROWD -> { favored += Special.CHARISMA; favored += Special.LUCK; notes += "A crowd means marks, marks mean opportunity." }
            Social.ALONE -> {}
        }
        if (ctx.light == LightLevel.DARK || ctx.phase == DayPhase.NIGHT) {
            favored += Special.PERCEPTION; favored += Special.AGILITY
            tempo += 1
            notes += "In the dark, things move that shouldn't."
        }
        if (ctx.light == LightLevel.BRIGHT && ctx.activity == Activity.STILL) {
            favored += Special.INTELLIGENCE
        }

        return SceneStrategy(
            favored = favored,
            tempoNudge = tempo.coerceIn(-1, 1),
            flavor = notes.firstOrNull() ?: "The wasteland is quiet, for now.",
        )
    }
}
