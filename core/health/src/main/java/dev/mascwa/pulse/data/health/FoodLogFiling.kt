package dev.mascwa.pulse.data.health

/**
 * Where a month's entries are filed, and the two decisions about that which are worth testing.
 *
 * ⚠️ **No Android import in this file, deliberately.** Both rules below sit on paths that need a
 * `Context` and a DataStore to reach, so inside [FoodLogStore] they could only ever be checked by
 * reading them — and each one, wrong, destroys something the user cannot get back. Pulled out here
 * they are plain functions over plain values and a JVM test that actually runs holds them. The same
 * reasoning put `TranscriptSeal` in its own file.
 */
internal object FoodLogFiling {

    /** Where a month's entries live, under `filesDir`. */
    const val SHARD_DIR = "food_log"

    /** The prefix the shards used while they were preference keys. Migration only. */
    const val SHARD_PREFIX = "food_"

    /**
     * Decoded months held at once. Four: `recentFoods` wants two, `entriesFor` one, and a decoded
     * month is roughly 300 kB once its two nutrient maps are objects — so about 1.2 MB.
     */
    const val MAX_RESIDENT_SHARDS = 4

    /**
     * `2026-08`.
     *
     * ⚠️ **The anchors change nothing today and are still worth having, which is the opposite of the
     * usual reading.** Kotlin's `Regex.matches` already requires the WHOLE input, so removing them
     * does not loosen anything — measured, the shape test stays green either way. What they buy is
     * that the rule survives `matches` being swapped for `containsMatchIn`, an edit somebody makes
     * when a match looks too strict. Measured both ways: with the anchors that swap is invisible to
     * every test here; without them it is caught. Two independent statements of one rule, which is
     * what makes it hold rather than what makes it dead weight.
     */
    private val MONTH = Regex("^\\d{4}-\\d{2}$")

    /**
     * The month a legacy preference key held, or null if that key is not a month shard.
     *
     * ⚠️ **This is why the test is on the SHAPE and not on the prefix.** The index lives under
     * `food_index`, which starts with `food_` exactly as `food_2026-08` does. A prefix test would
     * call the index a month named "index", write the whole index JSON out as `index.json`, and then
     * delete `food_index` — every day's totals gone, with the entries themselves sitting intact
     * behind them. That is about the worst shape a bug in this store could take, and it is one
     * character of carelessness away.
     */
    fun legacyMonth(keyName: String): String? {
        if (!keyName.startsWith(SHARD_PREFIX)) return null
        return keyName.removePrefix(SHARD_PREFIX).takeIf { MONTH.matches(it) }
    }

    /**
     * Which resident months to drop, given [resident] least-recently-used first.
     *
     * ⚠️ **A dirty month is never returned.** Its entries exist nowhere else until the flush writes
     * them, so dropping one loses a logged meal — and loses it silently, because the next read finds
     * the file without it and reports a smaller day that looks perfectly ordinary.
     *
     * ⚠️ That means the cache can legitimately sit ABOVE [max]: an import touching sixty months holds
     * sixty of them until they are written. Returning fewer than "enough" is the correct answer
     * there, not a failure to enforce the bound.
     */
    fun evictable(resident: List<String>, dirty: Set<String>, max: Int): List<String> {
        var size = resident.size
        if (size <= max) return emptyList()
        val out = mutableListOf<String>()
        for (month in resident) {
            if (size <= max) break
            if (month in dirty) continue
            out += month
            size--
        }
        return out
    }
}
