package dev.mascwa.pulse.core.telemetry

/**
 * Picks the "just reported" set for a Breaking news feed out of a pooled, possibly-duplicated list of
 * items aggregated from several sources. Pure + CI-tested; the repository feeds it articles via accessor
 * lambdas so this stays free of any Android / app types.
 */
object BreakingNews {

    const val DEFAULT_WINDOW_MS = 6L * 60 * 60 * 1000 // 6 hours

    /**
     * Dedupe [items] by [key] (first occurrence wins; blank keys dropped), order newest ([timeMs]) first,
     * and prefer items published within [windowMs] of [nowMs]. If fewer than [minRecent] are that fresh,
     * fall back to the freshest overall so the feed is never empty during a quiet stretch. Capped at [cap].
     * Future-dated timestamps (clock skew / bad feeds) don't count as "recent".
     */
    fun <T> select(
        items: List<T>,
        nowMs: Long,
        key: (T) -> String,
        timeMs: (T) -> Long,
        windowMs: Long = DEFAULT_WINDOW_MS,
        minRecent: Int = 6,
        cap: Int = 24,
    ): List<T> {
        val deduped = LinkedHashMap<String, T>()
        for (item in items) {
            val k = key(item)
            if (k.isBlank() || k in deduped) continue
            deduped[k] = item
        }
        val sorted = deduped.values.sortedByDescending { timeMs(it) }
        val lower = nowMs - windowMs
        val recent = sorted.filter { val t = timeMs(it); t in lower..nowMs }
        val chosen = if (recent.size >= minRecent) recent else sorted
        return chosen.take(cap.coerceAtLeast(0))
    }
}
