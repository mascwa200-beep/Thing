package dev.mascwa.pulse.core.telemetry

/**
 * What the Oracle has learned about which of its own rules are worth your attention.
 *
 * The engine ranks by urgency × timeliness × relevance, which is a good prior and knows nothing
 * about *you*. This is the correction term: when an insight fires and you then go where it pointed,
 * that rule earned something; when it fires repeatedly and you never do, it earned the opposite.
 *
 * Everything needed was already being collected. Every screen visit is logged with a timestamp, and
 * every [Insight] already carries an [Insight.actionRoute]. No new sensing, no new permission, and
 * nothing leaves the device — the whole record is a handful of counters per rule.
 *
 * ⚠️ **This is correlation, and the code should not pretend otherwise.** Going to the map after the
 * Oracle said to leave is consistent with the advice working; it is not proof, and the reverse —
 * ignoring an insight — may only mean you already knew. That is exactly why the multiplier band is
 * narrow: this tilts a ranking that was already reasonable, and is not allowed to take it over. A
 * rule you have never acted on still surfaces when it is urgent enough.
 */
object OracleMemory {

    /**
     * How far the learned signal may move a score, as a multiplier.
     *
     * Deliberately narrow. At the extremes a rule you always act on is worth about a third more than
     * one you never do — enough to reorder two insights of similar standing, nowhere near enough to
     * push a CRITICAL below an AMBIENT.
     */
    const val MIN_WEIGHT = 0.75
    const val MAX_WEIGHT = 1.35

    /**
     * Below this many showings a rule is not judged at all.
     *
     * Two coin flips tell you nothing about the coin. Under the floor [affinity] returns exactly 1.0,
     * so a new rule ranks on its merits until there is something to learn from.
     */
    const val MIN_SHOWN = 4

    /** How long after an insight is shown a visit still counts as acting on it. */
    const val ATTRIBUTION_WINDOW_MS = 30 * 60 * 1000L

    /** Per-rule tally. [family] is [Insight.family] — the rule, never one firing of it. */
    data class RuleStat(
        val family: String,
        val shown: Int = 0,
        val acted: Int = 0,
        val lastShownMs: Long = 0L,
    )

    /** The whole learned record, keyed by rule family. */
    data class Learning(val stats: Map<String, RuleStat> = emptyMap()) {
        fun stat(family: String): RuleStat = stats[family] ?: RuleStat(family)
    }

    /** Note that these rules were put in front of the user. */
    fun recordShown(state: Learning, families: Collection<String>, nowMs: Long): Learning {
        if (families.isEmpty()) return state
        val next = state.stats.toMutableMap()
        for (f in families.distinct()) {
            val cur = next[f] ?: RuleStat(f)
            next[f] = cur.copy(shown = cur.shown + 1, lastShownMs = nowMs)
        }
        return Learning(next)
    }

    /**
     * Note that the user acted on a rule.
     *
     * Never counts more acts than showings: attribution runs over a window and could otherwise see
     * the same visit twice, which would let a rule's hit rate climb past 1 and quietly break the
     * arithmetic below.
     */
    fun recordActed(state: Learning, family: String): Learning {
        val cur = state.stats[family] ?: return state
        if (cur.acted >= cur.shown) return state
        return Learning(state.stats + (family to cur.copy(acted = cur.acted + 1)))
    }

    /**
     * How much this rule's score should be scaled, in [MIN_WEIGHT]..[MAX_WEIGHT].
     *
     * Laplace-smoothed — `(acted + 1) / (shown + 2)` — so a rule acted on once out of one is not
     * treated as infallible. The smoothed rate starts at 0.5 with no evidence, which maps to the
     * middle of the band, so learning begins from neutral rather than from a penalty.
     */
    fun affinity(state: Learning, family: String): Double {
        val s = state.stat(family)
        if (s.shown < MIN_SHOWN) return 1.0
        val rate = (s.acted + 1.0) / (s.shown + 2.0)
        return MIN_WEIGHT + (MAX_WEIGHT - MIN_WEIGHT) * rate
    }

    /**
     * Re-rank a read by what has actually helped.
     *
     * Scores are scaled rather than replaced, and the list is re-sorted — so the engine's own
     * reasoning still decides what is on the list and roughly where, and this decides the order
     * among insights it had ranked similarly.
     */
    fun reweight(insights: List<Insight>, state: Learning): List<Insight> =
        insights
            .map { it.copy(score = it.score * affinity(state, it.family)) }
            .sortedByDescending { it.score }

    /**
     * Which rules a visit log says were acted on.
     *
     * [shownRoutes] is what was last put in front of the user: rule family → the route it pointed
     * at, with the moment it was shown. A rule counts as acted on when a visit to that route lands
     * after it was shown and inside [ATTRIBUTION_WINDOW_MS].
     *
     * ⚠️ **[habitualRoute] is excluded, and that exclusion is the difference between learning and
     * flattering yourself.** If you open the map every morning at eight, a visit to the map after a
     * morning insight pointed there is not evidence of anything — the app would simply learn that
     * its advice matches your routine, and rank loudest the things you were going to do anyway.
     */
    fun attribute(
        shownRoutes: Map<String, Pair<String, Long>>,
        visits: List<Visit>,
        habitualRoute: String? = null,
    ): Set<String> {
        if (shownRoutes.isEmpty() || visits.isEmpty()) return emptySet()
        val acted = mutableSetOf<String>()
        for ((family, pointed) in shownRoutes) {
            val (route, shownMs) = pointed
            if (route.isBlank() || route == habitualRoute) continue
            val hit = visits.any { v ->
                v.route == route && v.atMs > shownMs && v.atMs - shownMs <= ATTRIBUTION_WINDOW_MS
            }
            if (hit) acted += family
        }
        return acted
    }

    /** One screen visit: the route and when. The caller maps its own log onto this. */
    data class Visit(val route: String, val atMs: Long)

    /** A one-line account of what has been learned, for the surface and the assistant's tool. */
    fun summary(state: Learning): String {
        val judged = state.stats.values.filter { it.shown >= MIN_SHOWN }
        if (judged.isEmpty()) return "Still learning — not enough shown yet to judge any rule."
        val best = judged.maxByOrNull { (it.acted + 1.0) / (it.shown + 2.0) } ?: return ""
        val worst = judged.minByOrNull { (it.acted + 1.0) / (it.shown + 2.0) } ?: return ""
        if (best.family == worst.family) return "${best.family}: acted on ${best.acted} of ${best.shown}."
        return "Most acted on: ${best.family} (${best.acted}/${best.shown}). " +
            "Least: ${worst.family} (${worst.acted}/${worst.shown})."
    }

    /** Forget everything learned. */
    fun cleared(): Learning = Learning()
}
