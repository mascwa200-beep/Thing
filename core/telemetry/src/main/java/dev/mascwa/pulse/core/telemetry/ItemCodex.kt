package dev.mascwa.pulse.core.telemetry

/**
 * The item codex — a completionist tracker over the [Items] catalog. Every acquisition path (loot, scavenge,
 * shop, craft, encounter reward) marks an item "discovered"; discovery is monotonic (selling or using the
 * last one never un-discovers it), so it's a durable "gotta find 'em all" goal that rewards scavenging.
 *
 * Pure + CI-tested. The on-device store persists the discovered id set; this object turns it into progress,
 * per-kind breakdowns, the still-missing list (for silhouettes) and a flavour rank. Unknown ids in the set
 * (a catalog item removed later) are ignored so the count never exceeds the real total.
 */
object ItemCodex {

    /** Total number of real catalog items to discover. */
    val TOTAL: Int = Items.ALL.size

    /** How many real items have been discovered (ignores stale/unknown ids). */
    fun found(discovered: Set<String>): Int = Items.ALL.count { it.id in discovered }

    /** Fraction 0f..1f of the catalog discovered. */
    fun completion(discovered: Set<String>): Float =
        if (TOTAL == 0) 0f else found(discovered).toFloat() / TOTAL

    /** The discovered items, in stable catalog order. */
    fun discoveredItems(discovered: Set<String>): List<Item> = Items.ALL.filter { it.id in discovered }

    /** The still-missing items, in stable catalog order (for "???"/silhouette rows). */
    fun undiscovered(discovered: Set<String>): List<Item> = Items.ALL.filter { it.id !in discovered }

    /** Per-kind progress: kind → (found, total). Only kinds that exist in the catalog appear. */
    fun byKind(discovered: Set<String>): Map<ItemKind, Pair<Int, Int>> =
        Items.ALL.groupBy { it.kind }.mapValues { (_, items) ->
            items.count { it.id in discovered } to items.size
        }

    /** A flavour rank for how much of the catalog you've catalogued — a small completionist reward. */
    fun rank(discovered: Set<String>): String {
        val pct = completion(discovered)
        return when {
            pct >= 1f -> "Archivist"
            pct >= 0.75f -> "Curator"
            pct >= 0.5f -> "Collector"
            pct >= 0.25f -> "Scavenger"
            pct > 0f -> "Picker"
            else -> "Greenhorn"
        }
    }
}
