package dev.mascwa.pulse.core.telemetry

/**
 * On-device usage analytics → tailored recommendations. Pure + CI-tested.
 *
 * Operates on AGGREGATED data only — per-feature visit counts plus a 24-slot hour-of-day histogram.
 * Never message content, never locations, never PII — so the same summary is safe whether it stays
 * on-device or is surfaced to a cloud brain through the assistant's existing opt-in path.
 *
 * This is the "integration surface" the assistant reads: [UsageSnapshot] in, ranked [Recommendation]s
 * out, with all the heuristics in one pure place so they can evolve without touching storage or UI.
 */

/** Aggregated usage for a single feature/screen. */
data class FeatureUsage(
    val key: String,
    val count: Int,
    val lastEpochMs: Long = 0L,
    /** Visits per hour-of-day (local time); always 24 entries (index 0 = midnight hour). */
    val hourHistogram: List<Int> = List(24) { 0 },
)

/** A point-in-time read of everything recorded so far. */
data class UsageSnapshot(
    val features: List<FeatureUsage>,
    val totalEvents: Int,
) {
    companion object {
        val EMPTY = UsageSnapshot(emptyList(), 0)
    }
}

/** Static metadata so recommendations read nicely and can pitch features never tried. */
data class FeatureMeta(val key: String, val label: String, val pitch: String = "")

/** A single tailored suggestion. [routeKey] lets a caller deep-link to the feature. */
data class Recommendation(val headline: String, val detail: String, val routeKey: String? = null)

object UsageInsights {

    /** Features with at least one visit, most-used first. */
    fun topFeatures(snapshot: UsageSnapshot, limit: Int = 5): List<FeatureUsage> =
        snapshot.features.filter { it.count > 0 }.sortedByDescending { it.count }.take(limit)

    /** Sum of visits in the 3-hour window centred on [hour] (wraps around midnight). */
    private fun windowCount(histogram: List<Int>, hour: Int): Int {
        if (histogram.size != 24) return 0
        var sum = 0
        for (delta in -1..1) sum += histogram[((hour + delta) % 24 + 24) % 24]
        return sum
    }

    /** The feature most associated with [hour], or null if nothing is used around then. */
    fun featureForHour(snapshot: UsageSnapshot, hour: Int): FeatureUsage? =
        snapshot.features
            .filter { it.count > 0 && windowCount(it.hourHistogram, hour) > 0 }
            .maxByOrNull { windowCount(it.hourHistogram, hour) }

    /**
     * Up to [max] ranked recommendations, tailored to how the app is actually used:
     *  1. the go-to feature (most visits),
     *  2. what's usually opened around [nowHour] (if different), and
     *  3. one worthwhile feature not yet tried (from [catalog]).
     *
     * [catalog] supplies display labels + pitches; an unknown key falls back to the raw key. Returns an
     * empty list when there's no usage yet, so callers can show a clean "nothing yet" state.
     */
    fun recommend(
        snapshot: UsageSnapshot,
        nowHour: Int,
        catalog: List<FeatureMeta> = emptyList(),
        max: Int = 3,
    ): List<Recommendation> {
        val meta = catalog.associateBy { it.key }
        fun label(key: String) = meta[key]?.label ?: key

        val recs = mutableListOf<Recommendation>()
        val used = topFeatures(snapshot, limit = Int.MAX_VALUE)

        // 1) Go-to feature.
        val top = used.firstOrNull()
        if (top != null) {
            recs += Recommendation(
                headline = "Your go-to is ${label(top.key)}",
                detail = "You've opened it ${top.count} time${if (top.count == 1) "" else "s"}. Jump back in.",
                routeKey = top.key,
            )
        }

        // 2) Right-now habit (only when it's a different feature from the go-to).
        val nowFeature = featureForHour(snapshot, nowHour)
        if (nowFeature != null && nowFeature.key != top?.key) {
            recs += Recommendation(
                headline = "Around now you check ${label(nowFeature.key)}",
                detail = "It's part of your routine at this hour.",
                routeKey = nowFeature.key,
            )
        }

        // 3) A worthwhile feature not yet tried.
        if (used.isNotEmpty()) {
            val usedKeys = used.map { it.key }.toSet()
            catalog.firstOrNull { it.key !in usedKeys && it.pitch.isNotBlank() }?.let { unused ->
                recs += Recommendation(
                    headline = "Try ${unused.label}",
                    detail = unused.pitch,
                    routeKey = unused.key,
                )
            }
        }

        return recs.take(max)
    }
}
