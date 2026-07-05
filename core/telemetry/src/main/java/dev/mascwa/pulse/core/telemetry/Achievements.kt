package dev.mascwa.pulse.core.telemetry

/**
 * Achievements for the S.P.E.C.I.A.L. game — the layer that ties **real-world activity** and **playing the
 * game** to **progressing the character**. Each achievement watches one metric ([AchMetric]) and clears at a
 * [Achievement.threshold]; clearing grants XP / caps / an item.
 *
 * The catalog is **1000+ strong**: a handful of hand-authored FEATURED achievements plus dense, procedurally
 * generated milestone LADDERS across every tracked metric (distance by each way of travelling, elevation
 * climbed, steps, caps, wins, days survived, places, exploration cells, …). Generating the ladders keeps the
 * count huge, the ids unique and the thresholds monotonic without 1000 hand-written entries. Pure + CI-tested;
 * the on-device layer assembles a [GameMetrics] snapshot and calls [evaluate].
 */
enum class AchMetric {
    // Game progression
    LEVEL, WINS, CRITS, VENTURES, PERKS, DISTINCT_ITEMS, CAPS, ITEMS_DISCOVERED,
    TALKS_WON, SCAVENGES, QUESTS_DONE, CRAFTED, TRADES, RENOWN, DAYS_SURVIVED,
    // App usage
    APP_VISITS, DISTINCT_FEATURES,
    // Real-world travel + tracking
    DISTANCE_M, PLACES_VISITED,
    WALK_M, RUN_M, CYCLE_M, DRIVE_M, ONFOOT_M,
    ELEVATION_M, STEPS_TOTAL, CELLS_EXPLORED,
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
 * Everything achievements measure, assembled on-device: game progress, real app usage, and real-world
 * travel/tracking (distance by mode, elevation, steps, exploration cells — fed by the transport + map slices).
 * Pure snapshot; [value] projects a metric out of it. All defaulted → old callers/saves stay valid.
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
    // Game counters
    val talksWon: Int = 0,
    val scavenges: Int = 0,
    val questsDone: Int = 0,
    val crafted: Int = 0,
    val trades: Int = 0,
    val renown: Int = 0,
    val daysSurvived: Int = 0,
    // Per-mode travel + real-time tracking
    val walkM: Int = 0,
    val runM: Int = 0,
    val cycleM: Int = 0,
    val driveM: Int = 0,
    val elevationM: Int = 0,
    val stepsTotal: Int = 0,
    val cellsExplored: Int = 0,
    // LocationKind.names of place-kinds physically reached (for VISIT_KIND quest completion; no achievement
    // keys off it — carried here only so the one QuestMetrics snapshot reads from the one GameMetrics bag).
    val visitedKinds: Set<String> = emptySet(),
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
        AchMetric.TALKS_WON -> talksWon
        AchMetric.SCAVENGES -> scavenges
        AchMetric.QUESTS_DONE -> questsDone
        AchMetric.CRAFTED -> crafted
        AchMetric.TRADES -> trades
        AchMetric.RENOWN -> renown
        AchMetric.DAYS_SURVIVED -> daysSurvived
        AchMetric.WALK_M -> walkM
        AchMetric.RUN_M -> runM
        AchMetric.CYCLE_M -> cycleM
        AchMetric.DRIVE_M -> driveM
        AchMetric.ONFOOT_M -> walkM + runM
        AchMetric.ELEVATION_M -> elevationM
        AchMetric.STEPS_TOTAL -> stepsTotal
        AchMetric.CELLS_EXPLORED -> cellsExplored
    }
}

object Achievements {

    /** Hand-authored, flavourful milestones — the ones with bespoke names + item rewards. */
    private val FEATURED: List<Achievement> = listOf(
        Achievement("first_blood", "First Blood", "Win your first encounter.", AchMetric.WINS, 1, rewardXp = 20),
        Achievement("survivor", "Survivor", "Win 10 encounters.", AchMetric.WINS, 10, rewardCaps = 30, rewardItemId = "medkit"),
        Achievement("veteran", "Veteran", "Win 50 encounters.", AchMetric.WINS, 50, rewardCaps = 100, rewardItemId = "auto_injector"),
        Achievement("crit_streak", "Against The Odds", "Land 5 critical successes.", AchMetric.CRITS, 5, rewardItemId = "rabbit_foot"),
        Achievement("seasoned", "Seasoned", "Reach level 5.", AchMetric.LEVEL, 5, rewardCaps = 40),
        Achievement("legend", "Wasteland Legend", "Reach level 10.", AchMetric.LEVEL, 10, rewardItemId = "rare_alloy"),
        Achievement("specialist", "Specialist", "Earn 3 perks.", AchMetric.PERKS, 3, rewardXp = 60),
        Achievement("collector", "Collector", "Carry 10 different item types.", AchMetric.DISTINCT_ITEMS, 10, rewardCaps = 50),
        Achievement("caps_baron", "Caps Baron", "Bank 500 caps.", AchMetric.CAPS, 500, rewardXp = 100),
        Achievement("codex_10", "Rag And Bone", "Discover 10 different items.", AchMetric.ITEMS_DISCOVERED, 10, rewardXp = 30),
        Achievement("codex_20", "Curator", "Discover 20 different items.", AchMetric.ITEMS_DISCOVERED, 20, rewardCaps = 60, rewardItemId = "grit_ration"),
        Achievement("codex_all", "Archivist", "Discover every item in the catalog.", AchMetric.ITEMS_DISCOVERED, Items.ALL.size, rewardCaps = 200, rewardItemId = "fortune_idol", rewardXp = 100),
        Achievement("online", "Operator Online", "Open 25 screens across Pulse.", AchMetric.APP_VISITS, 25, rewardXp = 20),
        Achievement("power_user", "Power User", "Open 200 screens across Pulse.", AchMetric.APP_VISITS, 200, rewardCaps = 60, rewardItemId = "data_slate"),
        Achievement("explorer", "Explorer", "Use 8 different Pulse features.", AchMetric.DISTINCT_FEATURES, 8, rewardXp = 40),
        Achievement("cartographer", "Cartographer", "Use 15 different Pulse features.", AchMetric.DISTINCT_FEATURES, 15, rewardItemId = "optics_visor"),
        Achievement("first_steps", "First Steps", "Travel 1 km while playing.", AchMetric.DISTANCE_M, 1000, rewardXp = 20),
        Achievement("trailblazer", "Trailblazer", "Travel 10 km while playing.", AchMetric.DISTANCE_M, 10_000, rewardCaps = 50),
        Achievement("tourist", "Tourist", "Reach 5 real-world locations.", AchMetric.PLACES_VISITED, 5, rewardItemId = "comms_badge"),
    )

    // --- Ladder builders (dense, monotonic milestone thresholds) ---

    /** A long distance ladder in metres: fine near the start, coarsening out to thousands of km. */
    private fun distanceLadder(): List<Int> = buildList {
        for (m in 500..10_000 step 500) add(m)          // 0.5–10 km every 500 m  (20)
        for (km in 12..50 step 2) add(km * 1000)         // 12–50 km every 2 km    (20)
        for (km in 55..100 step 5) add(km * 1000)        // 55–100 km every 5 km   (10)
        for (km in 125..500 step 25) add(km * 1000)      // 125–500 km every 25 km (16)
        for (km in 600..2000 step 100) add(km * 1000)    // 0.6–2 Mm every 100 km  (15)
        for (km in 2500..10_000 step 500) add(km * 1000) // 2.5–10 Mm every 500 km (16)
    }

    /** An elevation ladder in metres (vertical climb). */
    private fun elevationLadder(): List<Int> = buildList {
        for (m in 100..2000 step 100) add(m)             // 100 m – 2 km every 100 m (20)
        for (m in 2500..10_000 step 500) add(m)          // 2.5–10 km every 500 m    (16)
        for (km in 12..100 step 4) add(km * 1000)        // 12–100 km every 4 km     (23)
    }

    /** A steps ladder (cumulative lifetime steps). */
    private fun stepsLadder(): List<Int> = buildList {
        for (k in 1..20) add(k * 1000)                   // 1k–20k every 1k   (20)
        for (k in 25..100 step 5) add(k * 1000)          // 25k–100k every 5k (16)
        for (k in 150..1000 step 50) add(k * 1000)       // 150k–1M every 50k (18)
        for (m in 2..10) add(m * 1_000_000)              // 2M–10M every 1M   (9)
    }

    /** A caps ladder. */
    private fun capsLadder(): List<Int> = buildList {
        for (c in 100..1000 step 100) add(c)             // 100–1000 every 100 (10)
        for (c in 1500..10_000 step 500) add(c)          // 1.5k–10k every 500 (18)
        for (c in 15_000..100_000 step 5000) add(c)      // 15k–100k every 5k  (18)
        for (c in 150_000..1_000_000 step 50_000) add(c) // 150k–1M every 50k  (18)
    }

    /** A general count ladder (wins, ventures, talks, …). */
    private fun countLadder(): List<Int> = buildList {
        for (n in 1..20) add(n)                          // 1–20 every 1     (20)
        for (n in 25..100 step 5) add(n)                 // 25–100 every 5   (16)
        for (n in 125..500 step 25) add(n)               // 125–500 every 25 (16)
        for (n in 600..2000 step 100) add(n)             // 600–2000 e/100   (15)
    }

    /** A short count ladder (rarer events: crits, days, perks). */
    private fun shortLadder(cap: Int): List<Int> = buildList {
        for (n in 1..10) add(n)
        for (n in 12..30 step 2) add(n)
        for (n in 40..cap step 10) add(n)
    }.filter { it <= cap }

    private enum class Unit { METERS, COUNT, CAPS, STEPS }

    private data class Track(
        val metric: AchMetric,
        val prefix: String,
        val title: String,
        val verb: String,
        val ladder: List<Int>,
        val unit: Unit,
        val payCaps: Boolean, // true → reward caps, false → reward XP
    )

    private fun tracks(): List<Track> = listOf(
        Track(AchMetric.DISTANCE_M, "dist", "Wayfarer", "Travel", distanceLadder(), Unit.METERS, payCaps = false),
        Track(AchMetric.ONFOOT_M, "foot", "Pathwalker", "Cover on foot", distanceLadder(), Unit.METERS, payCaps = false),
        Track(AchMetric.WALK_M, "walk", "Rambler", "Walk", distanceLadder(), Unit.METERS, payCaps = false),
        Track(AchMetric.RUN_M, "run", "Roadrunner", "Run", distanceLadder(), Unit.METERS, payCaps = true),
        Track(AchMetric.CYCLE_M, "cycle", "Freewheeler", "Cycle", distanceLadder(), Unit.METERS, payCaps = true),
        Track(AchMetric.DRIVE_M, "drive", "Caravanner", "Drive", distanceLadder(), Unit.METERS, payCaps = true),
        Track(AchMetric.ELEVATION_M, "climb", "Highlander", "Climb", elevationLadder(), Unit.METERS, payCaps = false),
        Track(AchMetric.STEPS_TOTAL, "steps", "Footsore", "Walk", stepsLadder(), Unit.STEPS, payCaps = false),
        Track(AchMetric.CELLS_EXPLORED, "explore", "Surveyor", "Explore", countLadder(), Unit.COUNT, payCaps = false),
        Track(AchMetric.PLACES_VISITED, "places", "Globetrotter", "Reach", countLadder(), Unit.COUNT, payCaps = true),
        Track(AchMetric.CAPS, "caps", "Prospector", "Bank", capsLadder(), Unit.CAPS, payCaps = false),
        Track(AchMetric.WINS, "wins", "Warlord", "Win", countLadder(), Unit.COUNT, payCaps = false),
        Track(AchMetric.CRITS, "crits", "Sharpshooter", "Land", countLadder(), Unit.COUNT, payCaps = true),
        Track(AchMetric.VENTURES, "vent", "Trailhand", "Engage", countLadder(), Unit.COUNT, payCaps = false),
        Track(AchMetric.TALKS_WON, "talk", "Silver Tongue", "Win", countLadder(), Unit.COUNT, payCaps = true),
        Track(AchMetric.SCAVENGES, "scav", "Scrapper", "Scavenge", countLadder(), Unit.COUNT, payCaps = true),
        Track(AchMetric.QUESTS_DONE, "quest", "Questmaster", "Complete", countLadder(), Unit.COUNT, payCaps = false),
        Track(AchMetric.CRAFTED, "craft", "Tinkerer", "Craft", countLadder(), Unit.COUNT, payCaps = true),
        Track(AchMetric.TRADES, "trade", "Merchant", "Trade", countLadder(), Unit.COUNT, payCaps = true),
        Track(AchMetric.APP_VISITS, "visits", "Regular", "Open", countLadder(), Unit.COUNT, payCaps = false),
        Track(AchMetric.LEVEL, "lvl", "Ascendant", "Reach level", shortLadder(100), Unit.COUNT, payCaps = false),
        Track(AchMetric.RENOWN, "renown", "Renowned", "Reach renown", shortLadder(100), Unit.COUNT, payCaps = true),
        Track(AchMetric.DAYS_SURVIVED, "days", "Endurer", "Survive", countLadder(), Unit.COUNT, payCaps = false),
        Track(AchMetric.PERKS, "perks", "Talented", "Earn", shortLadder(30), Unit.COUNT, payCaps = false),
        Track(AchMetric.DISTINCT_ITEMS, "carry", "Packrat", "Carry", shortLadder(60), Unit.COUNT, payCaps = true),
        Track(AchMetric.ITEMS_DISCOVERED, "disc", "Loremaster", "Discover", shortLadder(120), Unit.COUNT, payCaps = false),
    )

    private fun fmt(v: Int, unit: Unit): String = when (unit) {
        Unit.METERS -> if (v >= 1000) "${v / 1000} km" else "$v m"
        Unit.STEPS -> if (v >= 1_000_000) "${v / 1_000_000}M steps" else if (v >= 1000) "${v / 1000}k steps" else "$v steps"
        Unit.CAPS -> "$v caps"
        Unit.COUNT -> "$v"
    }

    /** Reward scales with the tier index (a milestone item every 10th tier). */
    private fun reward(index: Int, payCaps: Boolean): Pair<Int, Int> {
        val step = (index + 1)
        val amount = (10 + step * 5).coerceAtMost(500)
        return if (payCaps) 0 to amount else amount to 0
    }

    private fun generate(): List<Achievement> = tracks().flatMap { t ->
        t.ladder.sorted().mapIndexed { i, threshold ->
            val (xp, caps) = reward(i, t.payCaps)
            Achievement(
                id = "${t.prefix}_$threshold",
                name = "${t.title} ${i + 1}",
                desc = "${t.verb} ${fmt(threshold, t.unit)}.",
                metric = t.metric,
                threshold = threshold,
                rewardXp = xp,
                rewardCaps = caps,
                // A GEAR/AID drop at every 10th rung of a track, cycling through the milestone items.
                rewardItemId = if ((i + 1) % 10 == 0) MILESTONE_ITEMS[(i / 10) % MILESTONE_ITEMS.size] else null,
            )
        }
    }

    private val MILESTONE_ITEMS = listOf("rare_alloy", "fusion_cell", "gold_trinket", "medkit", "grit_ration")

    /** The full catalog: featured + generated ladders. De-duped by id (featured wins on a collision). */
    val ALL: List<Achievement> = (FEATURED + generate()).distinctBy { it.id }

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

    /**
     * The next few achievements to chase: for each metric, the nearest un-cleared tier (closest to done
     * first). Keeps the huge catalog surfaced as a short, motivating "what's next" list, not a wall of 1000.
     */
    fun nextUp(m: GameMetrics, unlocked: Set<String>, limit: Int = 6): List<Achievement> =
        ALL.asSequence()
            .filter { it.id !in unlocked && !isUnlocked(it, m) }
            .groupBy { it.metric }
            .values
            .mapNotNull { forMetric -> forMetric.minByOrNull { it.threshold } }
            .sortedByDescending { progress(it, m) }
            .take(limit)

    /** Grant [a]'s reward to [c] (caps, then an item, then XP so a level-up heals last). */
    fun applyReward(c: Character, a: Achievement): Character {
        var updated = c
        if (a.rewardCaps != 0) updated = updated.copy(caps = (updated.caps + a.rewardCaps).coerceAtLeast(0))
        a.rewardItemId?.let { updated = SpecialGame.addItem(updated, it, 1) }
        if (a.rewardXp > 0) updated = SpecialGame.gainXp(updated, a.rewardXp)
        return updated
    }
}
