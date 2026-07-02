package dev.mascwa.pulse.core.telemetry

/**
 * Rarity-weighted loot for the S.P.E.C.I.A.L. game — the pure model behind scavenging and random drops.
 * Common salvage falls often, rare gear rarely; a high [Special.LUCK] tilts the odds toward the good stuff
 * (never worse). Deterministic given the injected roll(s), so the on-device layer can supply randomness and
 * CI can still pin the distribution. Reuses the [Items] catalog — a drop is just an item id.
 *
 * QUEST items are never generic loot. GEAR/CHEM can drop but their high rarity makes them scarce.
 */
object LootTable {

    /** Everything eligible as generic loot — the catalog minus story/quest items. */
    val LOOTABLE: List<Item> = Items.ALL.filter { it.kind != ItemKind.QUEST }

    /** Base draw weight by rarity (1 common … 5 rare). Steeply favours common salvage. */
    fun baseWeight(rarity: Int): Int = when (rarity.coerceIn(1, 5)) {
        1 -> 100
        2 -> 55
        3 -> 25
        4 -> 8
        else -> 3 // rarity 5
    }

    /**
     * Draw weight for [item] given the scavenger's [luck]. LUCK only lifts the rare tiers (4/5) — it never
     * lowers a common item's weight — so a lucky operator finds more good gear without the floor dropping out.
     */
    fun weight(item: Item, luck: Int): Int {
        val l = luck.coerceIn(1, 10)
        val rareLift = when (item.rarity) {
            4 -> l
            5 -> l * 2
            else -> 0
        }
        return baseWeight(item.rarity) + rareLift
    }

    /**
     * Pick one item from [pool] weighted by [weight], using [roll] in `[0,1)` over the cumulative weight.
     * Deterministic: `roll = 0.0` yields the first item, a roll approaching 1.0 the last.
     */
    fun pick(luck: Int, roll: Double, pool: List<Item> = LOOTABLE): Item {
        require(pool.isNotEmpty()) { "loot pool is empty" }
        val weights = pool.map { weight(it, luck) }
        val total = weights.sum()
        var target = roll.coerceIn(0.0, 0.999999) * total
        for (i in pool.indices) {
            target -= weights[i]
            if (target < 0) return pool[i]
        }
        return pool.last()
    }

    /**
     * A scavenge bundle: one item per entry in [rolls], merged to `id → count`. Give it as many rolls as the
     * find is worth (e.g. 1–3, scaled by LUCK on the caller side).
     */
    fun scavenge(luck: Int, rolls: List<Double>, pool: List<Item> = LOOTABLE): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (r in rolls) {
            val item = pick(luck, r, pool)
            out[item.id] = (out[item.id] ?: 0) + 1
        }
        return out
    }
}
