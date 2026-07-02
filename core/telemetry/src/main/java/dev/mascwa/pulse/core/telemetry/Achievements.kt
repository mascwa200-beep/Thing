package dev.mascwa.pulse.core.telemetry

/**
 * Achievements for the S.P.E.C.I.A.L. game — the layer that ties **using the app** to **progressing the
 * character**, per the "keep player focus on the grind" direction. Each achievement watches one metric
 * ([AchMetric]) and clears at a [Achievement.threshold]; clearing grants XP / caps / an item, so playing
 * the game AND using Pulse both feed the wasteland operative. Pure + CI-tested; the on-device layer
 * assembles a [GameMetrics] snapshot (from the character + app usage + travel) and calls [evaluate].
 */
enum class AchMetric {
    LEVEL, WINS, CRITS, VENTURES, PERKS, DISTINCT_ITEMS, CAPS,
    APP_VISITS, DISTINCT_FEATURES, DISTANCE_M, PLACES_VISITED,
    ITEMS_DISCOVERED,
}

/** One achievement: watch [metric], clear at [threshold], pay out XP/caps/an item. */
data class Achievement(
    val id: String,
    val name: String,
    val desc: String,
    val metric: AchMetric,
    val threshold: Int,
    val rewardXp: Int = 0,
    val rewardCaps: Int = 0,
    val rewardItemId: String? = null,
)

/**
 * Everything achievements measure, assembled on-device: game progress (level/wins/crits/…), real app
 * usage (visits/distinct features), and real-world travel (distance/places — fed by the map slice).
 * Pure snapshot; [value] projects a metric out of it.
 */
data class GameMetrics(
    val level: Int = 1,
    val wins: Int = 0,
    val crits: Int = 0,
    val ventures: Int = 0,
    val perks: Int = 0,
    val distinctItems: Int = 0,
    val caps: Int = 0,
    val appVisits: Int = 0,
    val distinctFeatures: Int = 0,
    val distanceM: Int = 0,
    val placesVisited: Int = 0,
    val itemsDiscovered: Int = 0,
) {
    fun value(m: AchMetric): Int = when (m) {
        AchMetric.LEVEL -> level
        AchMetric.WINS -> wins
        AchMetric.CRITS -> crits
        AchMetric.VENTURES -> ventures
        AchMetric.PERKS -> perks
        AchMetric.DISTINCT_ITEMS -> distinctItems
        AchMetric.CAPS -> caps
        AchMetric.APP_VISITS -> appVisits
        AchMetric.DISTINCT_FEATURES -> distinctFeatures
        AchMetric.DISTANCE_M -> distanceM
        AchMetric.PLACES_VISITED -> placesVisited
        AchMetric.ITEMS_DISCOVERED -> itemsDiscovered
    }
}

object Achievements {
    /** The catalog. Rewards escalate; item rewards use ids from [Items]. */
    val ALL: List<Achievement> = listOf(
        // --- Combat / encounters ---
        Achievement("first_blood", "First Blood", "Win your first encounter.", AchMetric.WINS, 1, rewardXp = 20),
        Achievement("survivor", "Survivor", "Win 10 encounters.", AchMetric.WINS, 10, rewardCaps = 30, rewardItemId = "medkit"),
        Achievement("veteran", "Veteran", "Win 50 encounters.", AchMetric.WINS, 50, rewardCaps = 100, rewardItemId = "auto_injector"),
        Achievement("crit_streak", "Against The Odds", "Land 5 critical successes.", AchMetric.CRITS, 5, rewardItemId = "rabbit_foot"),
        // --- Progression ---
        Achievement("seasoned", "Seasoned", "Reach level 5.", AchMetric.LEVEL, 5, rewardCaps = 40),
        Achievement("legend", "Wasteland Legend", "Reach level 10.", AchMetric.LEVEL, 10, rewardItemId = "rare_alloy"),
        Achievement("specialist", "Specialist", "Earn 3 perks.", AchMetric.PERKS, 3, rewardXp = 60),
        Achievement("wanderer", "Wanderer", "Venture out 5 times.", AchMetric.VENTURES, 5, rewardXp = 15),
        Achievement("roamer", "Roamer", "Venture out 25 times.", AchMetric.VENTURES, 25, rewardCaps = 40),
        // --- Economy / loot ---
        Achievement("collector", "Collector", "Carry 10 different item types.", AchMetric.DISTINCT_ITEMS, 10, rewardCaps = 50),
        Achievement("caps_baron", "Caps Baron", "Bank 500 caps.", AchMetric.CAPS, 500, rewardXp = 100),
        // --- Codex / discovery (the completion loop: scavenge → discover → reward) ---
        Achievement("codex_10", "Rag And Bone", "Discover 10 different items.", AchMetric.ITEMS_DISCOVERED, 10, rewardXp = 30),
        Achievement("codex_20", "Curator", "Discover 20 different items.", AchMetric.ITEMS_DISCOVERED, 20, rewardCaps = 60, rewardItemId = "grit_ration"),
        Achievement("codex_all", "Archivist", "Discover every item in the catalog.", AchMetric.ITEMS_DISCOVERED, Items.ALL.size, rewardCaps = 200, rewardItemId = "fortune_idol", rewardXp = 100),
        // --- App usage (the grind bleeds into using Pulse) ---
        Achievement("online", "Operator Online", "Open 25 screens across Pulse.", AchMetric.APP_VISITS, 25, rewardXp = 20),
        Achievement("power_user", "Power User", "Open 200 screens across Pulse.", AchMetric.APP_VISITS, 200, rewardCaps = 60, rewardItemId = "data_slate"),
        Achievement("explorer", "Explorer", "Use 8 different Pulse features.", AchMetric.DISTINCT_FEATURES, 8, rewardXp = 40),
        Achievement("cartographer", "Cartographer", "Use 15 different Pulse features.", AchMetric.DISTINCT_FEATURES, 15, rewardItemId = "optics_visor"),
        // --- Real-world travel (fed by the map slice) ---
        Achievement("first_steps", "First Steps", "Travel 1 km while playing.", AchMetric.DISTANCE_M, 1000, rewardXp = 20),
        Achievement("trailblazer", "Trailblazer", "Travel 10 km while playing.", AchMetric.DISTANCE_M, 10_000, rewardCaps = 50),
        Achievement("tourist", "Tourist", "Reach 5 real-world locations.", AchMetric.PLACES_VISITED, 5, rewardItemId = "comms_badge"),
    )

    private val byId: Map<String, Achievement> = ALL.associateBy { it.id }
    fun byId(id: String): Achievement? = byId[id]

    /** Whether [m] has cleared [a]'s threshold. */
    fun isUnlocked(a: Achievement, m: GameMetrics): Boolean = m.value(a.metric) >= a.threshold

    /** Progress toward [a] in 0..1 (for a bar). */
    fun progress(a: Achievement, m: GameMetrics): Float =
        if (a.threshold <= 0) 1f else (m.value(a.metric).toFloat() / a.threshold).coerceIn(0f, 1f)

    /** Achievements newly cleared by [m] that aren't in [unlocked] yet — the ones to celebrate + reward. */
    fun evaluate(m: GameMetrics, unlocked: Set<String>): List<Achievement> =
        ALL.filter { it.id !in unlocked && isUnlocked(it, m) }

    /** Grant [a]'s reward to [c] (caps, then an item, then XP so a level-up heals last). */
    fun applyReward(c: Character, a: Achievement): Character {
        var updated = c
        if (a.rewardCaps != 0) updated = updated.copy(caps = (updated.caps + a.rewardCaps).coerceAtLeast(0))
        a.rewardItemId?.let { updated = SpecialGame.addItem(updated, it, 1) }
        if (a.rewardXp > 0) updated = SpecialGame.gainXp(updated, a.rewardXp)
        return updated
    }
}
