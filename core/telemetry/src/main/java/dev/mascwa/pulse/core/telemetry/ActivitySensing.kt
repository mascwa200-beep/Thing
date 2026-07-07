package dev.mascwa.pulse.core.telemetry

/**
 * Attesting to REAL-WORLD self-care from on-device perception. The mic (sound classification) and the camera
 * (scene classification) already produce text labels; this pure core maps a WINDOW of those labels — plus a
 * little context (how long water ran, whether you're home, the hour) — into evidence that a real activity (a
 * shower, a meal, a toilet trip) actually happened, each with a **confidence**. A later habit check-in can
 * then ask "did you shower?" and **catch a lie** by asking [verifyClaim] whether the sensors ever
 * corroborated it. Deterministic + CI-testable; no Android types (the samplers feed it labels).
 *
 * Honest by design: this yields a *probable inference*, never a claimed certainty (the mic can't be 100% sure
 * running water was a shower and not the dishes), so it always reports a confidence, and the strong verdict
 * requires corroboration. Privacy: it only ever sees text labels — never audio or pixels.
 */

/** A life-sim need a sensed activity tops up ([NONE] = presence evidence only, no need effect). */
enum class CareNeed { HYGIENE, NOURISHMENT, HYDRATION, REST, NONE }

/** A real-world activity the app tries to sense actually happening, and the need it satisfies. */
enum class RealActivity(val label: String, val satisfies: CareNeed) {
    SHOWER("Showered", CareNeed.HYGIENE),
    HANDWASH("Washed up", CareNeed.HYGIENE),
    TOOTHBRUSH("Brushed teeth", CareNeed.HYGIENE),
    TOILET("Bathroom trip", CareNeed.NONE),
    EATING("Ate a meal", CareNeed.NOURISHMENT),
    DRINKING("Had a drink", CareNeed.HYDRATION),
}

/** One sensed occurrence: which [activity], how sure ([confidence] 0..1), and when ([atMs]). */
data class ActivityEvidence(val activity: RealActivity, val confidence: Float, val atMs: Long)

/** The lie-catcher's answer when a claim is checked against the evidence log. */
enum class ClaimVerdict { CONFIRMED, WEAK, NONE }

object ActivitySensing {
    /** Evidence at/above this confidence firmly corroborates a claim. */
    const val CONFIRM_CONFIDENCE = 0.6f

    /** Below [CONFIRM_CONFIDENCE] but at/above this is a weak, not-quite-sure corroboration. */
    const val WEAK_CONFIDENCE = 0.3f

    /** Running water this long reads as a shower; a briefer run is a handwash. */
    const val SHOWER_MIN_MS = 90_000L

    // Being home / a plausible hour nudge confidence up; a matching scene corroborates a sound strongly.
    private const val HOME_BONUS = 0.15f
    private const val SCENE_BONUS = 0.25f
    private const val MEAL_HOUR_BONUS = 0.1f

    // Keyword vocab (lowercased substring match against YAMNet sound / image-classifier scene labels).
    private val WATER = listOf("water", "shower", "bathtub", "faucet", "tap", "sink", "basin", "spray", "trickle", "splash", "running water")
    private val TOILET_SND = listOf("toilet", "flush")
    private val TOOTH = listOf("toothbrush", "tooth", "electric toothbrush", "brushing")
    private val EAT = listOf("chewing", "eating", "cutlery", "crockery", "dishes", "chopping", "mastication", "biting", "crunch", "munch")
    private val DRINK = listOf("gulp", "gurgling", "pour", "sip", "slurp", "swallow", "drink")
    private val BATHROOM_SCENE = listOf("bathroom", "toilet", "shower", "washbasin", "washroom", "tub", "sink", "basin")
    private val KITCHEN_SCENE = listOf("kitchen", "restaurant", "dining", "plate", "meal", "food", "grocery", "cup", "mug", "bottle", "cooking")

    /**
     * Distil a window of sound + scene labels (with how long water ran + context) into activity evidence.
     * Corroboration — the right sound AND a matching scene AND being home AND a plausible hour — raises the
     * confidence; a lone signal stays low. Deterministic. [nowMs] stamps the evidence.
     */
    fun detect(
        soundLabels: List<PerceptLabel>,
        sceneLabels: List<PerceptLabel>,
        waterRunningMs: Long,
        atHome: Boolean,
        hourOfDay: Int,
        nowMs: Long,
    ): List<ActivityEvidence> {
        val sounds = soundLabels.map { it.label.lowercase() }
        val scenes = sceneLabels.map { it.label.lowercase() }
        val bathroom = scenes.anyOf(BATHROOM_SCENE)
        val kitchen = scenes.anyOf(KITCHEN_SCENE)
        val home = if (atHome) HOME_BONUS else 0f
        val out = mutableListOf<ActivityEvidence>()

        val water = sounds.anyOf(WATER)
        if (water || waterRunningMs >= SHOWER_MIN_MS) {
            if (waterRunningMs >= SHOWER_MIN_MS) {
                out += ev(RealActivity.SHOWER, 0.5f + (if (bathroom) SCENE_BONUS else 0f) + home, nowMs)
            } else {
                out += ev(RealActivity.HANDWASH, 0.4f + (if (bathroom) 0.2f else 0f) + home, nowMs)
            }
        }
        if (sounds.anyOf(TOILET_SND)) out += ev(RealActivity.TOILET, 0.6f + (if (bathroom) 0.2f else 0f), nowMs)
        if (sounds.anyOf(TOOTH)) out += ev(RealActivity.TOOTHBRUSH, 0.55f + (if (bathroom) 0.2f else 0f), nowMs)
        if (sounds.anyOf(EAT)) {
            out += ev(RealActivity.EATING, 0.4f + (if (kitchen) SCENE_BONUS else 0f) + home + mealHour(hourOfDay), nowMs)
        }
        if (sounds.anyOf(DRINK)) out += ev(RealActivity.DRINKING, 0.35f + home, nowMs)
        // A bathroom scene alone (camera, no telltale sound) is weak presence evidence — better than nothing.
        if (bathroom && !water && !sounds.anyOf(TOILET_SND)) out += ev(RealActivity.TOILET, 0.3f, nowMs)
        return out
    }

    /**
     * Check a [claim] against the [evidence] log since [sinceMs]: [ClaimVerdict.CONFIRMED] when strong
     * corroboration exists in the window, [ClaimVerdict.WEAK] when only weak, else [ClaimVerdict.NONE] — the
     * heart of "it'll know if I'm lying".
     */
    fun verifyClaim(claim: RealActivity, evidence: List<ActivityEvidence>, sinceMs: Long): ClaimVerdict {
        val best = evidence.filter { it.activity == claim && it.atMs >= sinceMs }.maxOfOrNull { it.confidence } ?: 0f
        return when {
            best >= CONFIRM_CONFIDENCE -> ClaimVerdict.CONFIRMED
            best >= WEAK_CONFIDENCE -> ClaimVerdict.WEAK
            else -> ClaimVerdict.NONE
        }
    }

    private fun ev(a: RealActivity, conf: Float, now: Long) = ActivityEvidence(a, conf.coerceIn(0f, 1f), now)

    /** A confidence nudge when the hour is a plausible mealtime (breakfast / lunch / dinner windows, local). */
    private fun mealHour(h: Int): Float {
        val x = ((h % 24) + 24) % 24
        return if (x in 6..9 || x in 11..14 || x in 17..21) MEAL_HOUR_BONUS else 0f
    }

    private fun List<String>.anyOf(keys: List<String>) = any { s -> keys.any { s.contains(it) } }
}
