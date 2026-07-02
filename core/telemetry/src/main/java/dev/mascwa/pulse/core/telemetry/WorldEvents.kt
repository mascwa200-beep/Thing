package dev.mascwa.pulse.core.telemetry

/**
 * Wasteland world events — a daily "situation" that colours the day's play. Each wasteland day (from the
 * [GameClock] day counter) gets one deterministic event that may favour certain S.P.E.C.I.A.L. stats
 * (biasing which encounters appear, like the circadian/perception bias) and/or modify the caps a winning
 * encounter pays. Pure + CI-tested; the on-device layer reads [eventFor] and threads [WorldEvent.favored]
 * into `venture()` + [WorldEvent.capsWinPct] into the win reward. A banner surfaces it on the STAT tab.
 */
data class WorldEvent(
    val id: String,
    val name: String,
    val desc: String,
    val favored: Set<Special> = emptySet(),
    /** +/- percent to the caps a winning encounter pays this day (0 = no change). */
    val capsWinPct: Int = 0,
)

object WorldEvents {
    val ALL: List<WorldEvent> = listOf(
        WorldEvent("calm", "A Calm Day", "The wasteland is still. Nothing stirs on the wind."),
        WorldEvent("radstorm", "Radstorm",
            "A rad-charged storm rolls in — endurance is everything, and the desperate pay well.",
            favored = setOf(Special.ENDURANCE), capsWinPct = 20),
        WorldEvent("bounty", "Bounty Posted", "Coin for the bold. Every win pays extra today.", capsWinPct = 30),
        WorldEvent("lucky_star", "Lucky Star", "Fortune hangs in the air — luck runs your way.",
            favored = setOf(Special.LUCK)),
        WorldEvent("the_hunt", "The Hunt", "Predators are on the move. Strength and reflexes will be tested.",
            favored = setOf(Special.STRENGTH, Special.AGILITY)),
        WorldEvent("market_fair", "Market Fair",
            "Traders and talkers fill the roads — a silver tongue wins the day.", favored = setOf(Special.CHARISMA)),
        WorldEvent("gloom", "Gloom", "A grey, grinding day. Caps come slow and hard.", capsWinPct = -15),
        WorldEvent("clear_skies", "Clear Skies", "A sharp, bright day — keen eyes and clear minds prosper.",
            favored = setOf(Special.PERCEPTION, Special.INTELLIGENCE)),
    )

    private val byId: Map<String, WorldEvent> = ALL.associateBy { it.id }
    fun byId(id: String): WorldEvent? = byId[id]

    /**
     * The deterministic event for wasteland [day] (day 1, 2, …). Step-7 over a size-8 catalog is a full
     * permutation (gcd(7,8)=1), so each event appears exactly once per cycle with no back-to-back repeats.
     */
    fun eventFor(day: Int): WorldEvent {
        val n = ALL.size
        val idx = ((day * 7 + 3) % n + n) % n
        return ALL[idx]
    }
}
