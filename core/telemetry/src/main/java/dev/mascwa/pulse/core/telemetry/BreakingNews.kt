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

    /**
     * One item per outlet — the newest each has — with the outlets themselves ordered newest first.
     *
     * A widget with three lines to spend should spend them on three different newsrooms. Three
     * headlines from one wire is one story told three times, and it reads as a broken feed rather
     * than as breadth.
     *
     * ⚠️ **[select] cannot do this, and the way it nearly can is the trap.** Passing the outlet as
     * its `key` does yield one item per outlet — but its dedupe is *first occurrence wins over the
     * INPUT order*, and it sorts only afterwards, so each outlet would be represented by whichever
     * article happened to arrive first rather than by its newest. Here the newest per outlet is the
     * whole point, so the grouping has to happen before any ordering.
     *
     * [prefer] fills the slots with known newsrooms first, and only then by recency — it is a
     * BUCKET, not a tiebreak, and calling it a tiebreak would be describing something the
     * comparator does not do. With only about three slots to spend, three recognised newsrooms'
     * latest beats one wire story and two aggregator reposts, and an aggregator can easily be the
     * most recent thing in the list. It is still not a filter: when no preferred outlet has
     * published, the unpreferred ones take every slot rather than the block going empty.
     *
     * Blank outlets are dropped; an item whose newsroom cannot be named cannot represent one.
     */
    fun <T> perOutlet(
        items: List<T>,
        outlet: (T) -> String,
        timeMs: (T) -> Long,
        max: Int,
        prefer: (String) -> Boolean = { false },
    ): List<T> {
        if (max <= 0) return emptyList()
        val newestPer = LinkedHashMap<String, T>()
        for (item in items) {
            val o = outlet(item).trim()
            if (o.isBlank()) continue
            val held = newestPer[o]
            if (held == null || timeMs(item) > timeMs(held)) newestPer[o] = item
        }
        return newestPer.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, T>> { prefer(it.key) }
                    .thenByDescending { timeMs(it.value) },
            )
            .map { it.value }
            .take(max)
    }
}
