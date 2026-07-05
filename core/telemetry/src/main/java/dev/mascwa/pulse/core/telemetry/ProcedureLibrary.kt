package dev.mascwa.pulse.core.telemetry

/**
 * A learned **procedure** — a named, reusable multi-step *tool sequence* that accomplished a class of goal.
 *
 * This is the Mnemosyne "skills" layer, and it's deliberately distinct from its neighbours:
 *  - [Cerebellum] learns *single-action* reflexes + a forward model (one tool's reliability in a context).
 *  - Self-coding writes *new app code* (extends the app itself).
 *  - A [Procedure] captures HOW a whole multi-step request was carried out successfully, keyed by the goal,
 *    so the agent can recognise a similar goal later and follow the known plan instead of re-deriving it.
 */
data class Procedure(
    /** Short label derived from the goal (a few keywords). */
    val name: String,
    /** Normalized goal signature — the significant keywords used to match similar requests. */
    val cueKeywords: List<String>,
    /** The ordered tool names that solved it. */
    val steps: List<String>,
    val timesApplied: Int,
    val timesSucceeded: Int,
    val createdMs: Long,
    val lastUsedMs: Long,
) {
    val reliability: Double get() = if (timesApplied == 0) 0.0 else timesSucceeded.toDouble() / timesApplied
    fun practiced(threshold: Int = ProcedureLibrary.PRACTICE_THRESHOLD): Boolean = timesApplied >= threshold
}

/**
 * Pure, dependency-free core for the procedure library: normalize a goal into a cue, learn a procedure
 * from a completed run, recall the best matching one, and render a compact digest for the prompt. The
 * on-device store + agent wiring live in the app layer; this stays Android-free so the (noise-sensitive)
 * learning/matching rules are unit-tested in CI.
 */
object ProcedureLibrary {

    /** Repetitions before a procedure is trusted enough to recall/advertise. */
    const val PRACTICE_THRESHOLD = 2
    /** A single tool isn't a "procedure" — that's the cerebellum's job. */
    const val MIN_STEPS = 2
    const val MAX_PROCEDURES = 120
    const val MAX_CUE_KEYWORDS = 6
    /** Minimum reliability for a practiced procedure to be recalled / advertised. */
    const val RELIABILITY_THRESHOLD = 0.6
    /** Minimum cue overlap (Jaccard) to treat a procedure as matching a request. */
    const val MATCH_THRESHOLD = 0.34

    private val STOPWORDS = setOf(
        "the", "and", "for", "you", "your", "can", "could", "would", "will", "with", "that", "this",
        "what", "how", "get", "let", "please", "jarvis", "from", "into", "about", "have", "use", "using",
    )

    // Compiled once — keywords runs per procedure recall/observe; Regex is immutable/thread-safe.
    private val TOKEN = Regex("[^a-z0-9]+")

    /** The significant keywords of a request — lowercased, de-stopworded, deduped, capped. Order-independent
     *  so two phrasings of the same goal share a cue. */
    fun keywords(text: String): List<String> =
        text.lowercase().split(TOKEN)
            .filter { it.length > 2 && it !in STOPWORDS }
            .distinct()
            .take(MAX_CUE_KEYWORDS)

    /** Jaccard overlap of two keyword sets, 0..1. */
    private fun overlap(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet(); val sb = b.toSet()
        val inter = sa.intersect(sb).size.toDouble()
        return inter / (sa.size + sb.size - inter)
    }

    /** A short human/agent-readable name for the goal. */
    fun nameFor(request: String): String =
        keywords(request).take(3).joinToString(" ").ifBlank { "procedure" }

    /**
     * Learn from a completed run. [request] is the goal, [toolSequence] the ordered tools used, [success]
     * whether it worked. Sub-[MIN_STEPS] sequences are ignored (single tools are the cerebellum's domain).
     * Reinforces an existing procedure whose cue matches; otherwise seeds a NEW one only from a *successful*
     * run (failures are never memorised as procedures). Returns a new, capped list (immutable).
     */
    fun learn(
        procedures: List<Procedure>,
        request: String,
        toolSequence: List<String>,
        success: Boolean,
        nowMs: Long,
        cap: Int = MAX_PROCEDURES,
    ): List<Procedure> {
        val steps = toolSequence.filter { it.isNotBlank() }
        if (steps.size < MIN_STEPS) return procedures
        val cue = keywords(request)
        if (cue.isEmpty()) return procedures

        val idx = procedures.indexOfFirst { overlap(it.cueKeywords, cue) >= MATCH_THRESHOLD }
        val updated = if (idx >= 0) {
            val p = procedures[idx]
            val merged = p.copy(
                // Keep the latest *successful* steps; a failure reinforces the stats but not the recipe.
                steps = if (success) steps else p.steps,
                timesApplied = p.timesApplied + 1,
                timesSucceeded = p.timesSucceeded + if (success) 1 else 0,
                lastUsedMs = nowMs,
            )
            procedures.toMutableList().also { it[idx] = merged }
        } else {
            if (!success) return procedures
            procedures + Procedure(nameFor(request), cue, steps, 1, 1, nowMs, nowMs)
        }
        return capped(updated, cap)
    }

    /** The best practiced, reliable procedure matching [request], or null. */
    fun recall(
        procedures: List<Procedure>,
        request: String,
        minReliability: Double = RELIABILITY_THRESHOLD,
    ): Procedure? {
        val cue = keywords(request)
        if (cue.isEmpty()) return null
        return procedures
            .map { it to overlap(it.cueKeywords, cue) }
            .filter { (p, ov) -> ov >= MATCH_THRESHOLD && p.practiced() && p.reliability >= minReliability }
            .maxByOrNull { (p, ov) -> ov * 0.5 + p.reliability * 0.5 }
            ?.first
    }

    /** Compact "procedures you've learned" block for the system prompt, most-reliable first. Empty when
     *  nothing is trustworthy yet. */
    fun digest(procedures: List<Procedure>, max: Int = 6): String {
        val top = procedures
            .filter { it.practiced() && it.reliability >= RELIABILITY_THRESHOLD }
            .sortedWith(compareByDescending<Procedure> { it.reliability }.thenByDescending { it.lastUsedMs })
            .take(max)
        if (top.isEmpty()) return ""
        return buildString {
            append("Procedures you've learned that work (follow the matching one):")
            top.forEach { p ->
                append("\n• ").append(p.name).append(": ").append(p.steps.joinToString(" → "))
                append("  (").append((p.reliability * 100).toInt()).append("% over ").append(p.timesApplied).append(")")
            }
        }
    }

    /** Evict to [cap], keeping the most reliable then most-recently-used. */
    fun capped(procedures: List<Procedure>, cap: Int = MAX_PROCEDURES): List<Procedure> {
        if (procedures.size <= cap) return procedures
        return procedures.sortedWith(
            compareByDescending<Procedure> { it.reliability }.thenByDescending { it.lastUsedMs },
        ).take(cap)
    }
}
