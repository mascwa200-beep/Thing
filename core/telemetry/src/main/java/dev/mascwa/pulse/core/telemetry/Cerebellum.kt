package dev.mascwa.pulse.core.telemetry

/**
 * A virtual **cerebellum** for J.A.R.V.I.S. — the subconscious motor/skill layer that sits beneath the
 * deliberate "cortex" (the LLM agent loop). Biologically the cerebellum does three things this models:
 *
 *  1. **Procedural learning** ("muscle memory") — repetition reinforces an action's reliability for a
 *     given cue, so well-practiced routines become automatic *reflexes* the cortex needn't re-derive.
 *  2. **Forward models** — it predicts the likely outcome of an action before/while it runs.
 *  3. **Error correction** — when the chosen action *deviates* from the practiced reflex, it emits an
 *     error signal so behaviour can be corrected.
 *
 * This object is the pure, deterministic core (CI-tested). It is content-agnostic: a *cue* is just a
 * normalized situation key (e.g. "tool", "after:web", "req:weather today") and an *action* is what was
 * done. Storage, persistence and wiring live in the app layer; here is only the math.
 *
 * Reliability is an exponentially-weighted moving average (EWMA) of success, so recent outcomes matter
 * more — the cerebellum adapts when a once-reliable action starts failing. A skill becomes a *reflex*
 * only after enough practice AND sufficient reliability, so a single lucky success can't trigger one.
 */

/** One learned association: doing [action] in situation [cue] tends to succeed with [reliability]. */
data class Skill(
    val cue: String,
    val action: String,
    val attempts: Int,
    val successes: Int,
    /** EWMA of success in 0..1 — the forward-model estimate of P(success). */
    val reliability: Double,
    val lastEpochMs: Long,
)

/** The cerebellum's whole learned state (immutable; [Cerebellum.learn] returns a new one). */
data class CerebellumState(val skills: List<Skill> = emptyList()) {
    companion object {
        val EMPTY = CerebellumState()
    }
}

/** A forward-model read for a contemplated ([cue], [action]). */
data class Prediction(
    /** Estimated probability the action succeeds (the skill's reliability, or a prior if unseen). */
    val successProbability: Double,
    /** True once the (cue, action) skill has been practiced enough to be trusted. */
    val practiced: Boolean,
    /** The practiced reflex for this cue, if one exists. */
    val reflexAction: String?,
    /** Error signal: the action departs from the practiced reflex for this cue. */
    val deviation: Boolean,
)

object Cerebellum {
    /** EWMA weight on the newest outcome. Higher = faster to adapt, noisier. */
    const val LEARNING_RATE = 0.3
    /** Repetitions before a skill can be considered automatic. */
    const val PRACTICE_THRESHOLD = 3
    /** Minimum reliability for a practiced skill to fire as a reflex. */
    const val RELIABILITY_THRESHOLD = 0.6
    /** Forward-model prior for an action never tried in this situation. */
    const val PRIOR = 0.5
    /** Cap on retained skills; least-practiced / stalest are forgotten past this. */
    const val MAX_SKILLS = 240

    /** True if [skill] is practiced and reliable enough to act on reflexively. */
    fun isAutomatic(skill: Skill): Boolean =
        skill.attempts >= PRACTICE_THRESHOLD && skill.reliability >= RELIABILITY_THRESHOLD

    /**
     * Motor learning: fold one observed outcome into the state and return the new state. Creates the
     * skill on first sight (reliability initialized to the first outcome), then EWMA-updates it.
     */
    fun learn(
        state: CerebellumState,
        cue: String,
        action: String,
        success: Boolean,
        nowMs: Long,
    ): CerebellumState {
        val c = cue.trim()
        val a = action.trim()
        if (c.isEmpty() || a.isEmpty()) return state
        val obs = if (success) 1.0 else 0.0
        val existing = state.skills.firstOrNull { it.cue == c && it.action == a }
        val updated = if (existing == null) {
            Skill(c, a, attempts = 1, successes = if (success) 1 else 0, reliability = obs, lastEpochMs = nowMs)
        } else {
            existing.copy(
                attempts = existing.attempts + 1,
                successes = existing.successes + if (success) 1 else 0,
                reliability = (1 - LEARNING_RATE) * existing.reliability + LEARNING_RATE * obs,
                lastEpochMs = nowMs,
            )
        }
        val rest = state.skills.filterNot { it.cue == c && it.action == a }
        val merged = rest + updated
        return CerebellumState(if (merged.size > MAX_SKILLS) evict(merged) else merged)
    }

    /** Drop the weakest skill: least-practiced first, then stalest. Keeps the store bounded. */
    private fun evict(skills: List<Skill>): List<Skill> {
        val weakest = skills.minWithOrNull(
            compareBy<Skill> { it.attempts }.thenBy { it.lastEpochMs },
        ) ?: return skills
        return skills.filterNot { it === weakest }
    }

    /** All skills learned for [cue], most reliable (then most practiced) first. */
    fun skillsFor(state: CerebellumState, cue: String): List<Skill> =
        state.skills.filter { it.cue == cue.trim() }
            .sortedWith(compareByDescending<Skill> { it.reliability }.thenByDescending { it.attempts })

    /** The practiced reflex for [cue] — the most reliable *automatic* skill — or null if none yet. */
    fun recall(state: CerebellumState, cue: String): Skill? =
        skillsFor(state, cue).firstOrNull { isAutomatic(it) }

    /** Forward-model + error-correction read for a contemplated ([cue], [action]). */
    fun predict(state: CerebellumState, cue: String, action: String): Prediction {
        val c = cue.trim()
        val a = action.trim()
        val skill = state.skills.firstOrNull { it.cue == c && it.action == a }
        val reflex = recall(state, c)
        return Prediction(
            successProbability = skill?.reliability ?: PRIOR,
            practiced = skill != null && skill.attempts >= PRACTICE_THRESHOLD,
            reflexAction = reflex?.action,
            deviation = reflex != null && reflex.action != a,
        )
    }

    /** Most-practiced skills overall (for a procedural-memory readout). */
    fun topSkills(state: CerebellumState, limit: Int = 8): List<Skill> =
        state.skills.sortedWith(
            compareByDescending<Skill> { it.attempts }.thenByDescending { it.reliability },
        ).take(limit)

    private val STOPWORDS = setOf(
        "the", "a", "an", "to", "of", "and", "or", "is", "are", "for", "in", "on", "at", "my", "me",
        "you", "i", "please", "can", "could", "would", "what", "whats", "how", "do", "does", "show",
        "tell", "give", "get", "set", "with", "this", "that", "it", "jarvis",
    )

    // Compiled once — signature runs per request; Regex is immutable/thread-safe.
    private val TOKEN = Regex("[^a-z0-9]+")

    /**
     * Normalize a free-text request into a stable, low-sensitivity cue: lowercase, letters only, drop
     * stopwords, keep the first few salient tokens. Deterministic so the same kind of request maps to
     * the same skill — the basis of a request-level reflex.
     */
    fun signature(text: String, maxTokens: Int = 3): String {
        val tokens = text.lowercase()
            .split(TOKEN)
            .filter { it.length > 2 && it !in STOPWORDS }
        return tokens.take(maxTokens).joinToString(" ")
    }
}
