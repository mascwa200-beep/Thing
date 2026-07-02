package dev.mascwa.pulse.core.telemetry

/**
 * The geo-gated wasteland: a real-world place ([GameLocation]-style coords + an OSM category) becomes a
 * wasteland SITE you can only engage by physically standing at it. This is the pure, CI-tested foundation of
 * the "to get things and do things, first I must go there" overhaul — a deterministic mapping from a real
 * place to a stable site (same place → same site, every scan): its type, its wasteland name, how dangerous
 * it is, and what it offers (a shop, fights, a quest, salvage).
 *
 * Classification ([typeFor]) mirrors [GameLocations.kindFor] but widens it to the hostile/exploration types
 * (gang camps, monster dens, tribes, ruins, vaults). Naming ([nameFor]) is seeded by the place's id so it
 * never churns. No Android types — the on-device layer supplies coords + category and gates on presence.
 */
enum class SiteType(
    val label: String,
    /** 1 (safe town) … 5 (a vault full of horrors). */
    val threat: Int,
    /** Whether arriving means a fight, not a handshake. */
    val hostile: Boolean,
    /** The shop this site runs (buy/sell), or null if it isn't a trading site. */
    val shopKind: LocationKind?,
) {
    SETTLEMENT("Settlement", 1, false, LocationKind.TRADER),
    TRADER("Trading Post", 1, false, LocationKind.TRADER),
    MEDIC("Clinic", 1, false, LocationKind.MEDIC),
    FIXER("Workshop", 1, false, LocationKind.FIXER),
    BARKEEP("Watering Hole", 1, false, LocationKind.BARKEEP),
    OUTPOST("Outpost", 1, false, LocationKind.OUTPOST),
    TRIBE("Tribal Camp", 2, false, null),
    RUINS("Ruins", 2, false, null),
    GANG_CAMP("Gang Camp", 3, true, null),
    MONSTER_DEN("Monster Den", 4, true, null),
    VAULT("Vault", 5, true, null),
}

/** A real-world place, reimagined as a wasteland site. [lat]/[lon] come from the POI; [id] is its stable id. */
data class WorldSite(
    val id: String,
    val name: String,
    val type: SiteType,
    val lat: Double,
    val lon: Double,
)

object WorldSites {

    /** Classify an OSM category / query tag into a [SiteType] (defaults to [SiteType.RUINS] — an unknown ruin). */
    fun typeFor(osmCategory: String): SiteType {
        val c = osmCategory.lowercase()
        return when {
            c.contains("city") || c.contains("town") || c.contains("village") || c.contains("settlement") -> SiteType.SETTLEMENT
            c.contains("pharmacy") || c.contains("hospital") || c.contains("clinic") || c.contains("doctor") ||
                c.contains("health") || c.contains("medic") -> SiteType.MEDIC
            c.contains("hardware") || c.contains("doityourself") || c.contains("electronic") || c.contains("trade") ||
                c.contains("car_repair") || c.contains("fixer") -> SiteType.FIXER
            c.contains("bar") || c.contains("pub") || c.contains("cafe") || c.contains("restaurant") ||
                c.contains("fast_food") || c.contains("barkeep") -> SiteType.BARKEEP
            c.contains("fuel") || c.contains("charging") || c.contains("kiosk") || c.contains("outpost") -> SiteType.OUTPOST
            c.contains("supermarket") || c.contains("convenience") || c.contains("general") || c.contains("mall") ||
                c.contains("department") || c.contains("grocery") || c.contains("trader") -> SiteType.TRADER
            c.contains("park") || c.contains("camp") || c.contains("tribe") || c.contains("nature_reserve") ||
                c.contains("village_green") -> SiteType.TRIBE
            c.contains("industrial") || c.contains("works") || c.contains("factory") || c.contains("warehouse") ||
                c.contains("gang") || c.contains("scrap") || c.contains("yard") -> SiteType.GANG_CAMP
            c.contains("water") || c.contains("wood") || c.contains("forest") || c.contains("wetland") ||
                c.contains("swamp") || c.contains("monster") || c.contains("den") -> SiteType.MONSTER_DEN
            c.contains("military") || c.contains("bunker") || c.contains("vault") -> SiteType.VAULT
            c.contains("ruins") || c.contains("disused") || c.contains("abandoned") || c.contains("historic") -> SiteType.RUINS
            else -> SiteType.RUINS
        }
    }

    // --- Deterministic name pools (seeded by the place id so a site never renames). ---
    private val SETTLEMENT_PREFIX = listOf("New", "Fort", "Camp", "Port", "Nova", "Old", "Little")
    private val SETTLEMENT_CORE = listOf("Haven", "Reach", "Hollow", "Ridge", "Crossing", "Junction", "Wells", "Landing", "Rest", "Bend")
    private val SHOP_KEEPER = listOf("Mick", "Dara", "Sal", "Junko", "Rivet", "Reyes", "Kell", "Gus", "Tally", "Wrenn")
    private val TRIBE_NAMES = listOf("The Ashfolk", "The Dust Walkers", "The Sunborn", "The Deeproot Clan", "The Salt Kings", "The Emberkin", "The Pale Herd")
    private val GANG_ADJ = listOf("Rusted", "Ashen", "Iron", "Bloody", "Broken", "Feral", "Scarred", "Black")
    private val GANG_NOUN = listOf("Fangs", "Vipers", "Wolves", "Nails", "Reavers", "Jackals", "Hounds", "Scorpions")
    private val BEAST = listOf("Deathclaw", "Radscorpion", "Mirelurk", "Ghoul", "Bloatfly", "Molerat", "Yao Guai")
    private val DEN_FEATURE = listOf("Gorge", "Nest", "Warren", "Sink", "Hollow", "Burrow", "Pit")
    private val BAR_ADJ = listOf("Rusty", "Broken", "Lucky", "Last", "Crooked", "Dusty")
    private val BAR_NOUN = listOf("Nail", "Bottle", "Cap", "Spur", "Lantern", "Barrel")

    private fun pick(pool: List<String>, seed: Int): String = pool[(seed % pool.size + pool.size) % pool.size]

    /** A stable, deterministic wasteland name for a site of [type], seeded (e.g. by the POI id's hash). */
    fun nameFor(type: SiteType, seed: Int): String {
        val s2 = seed / 7 // a second, decorrelated index for composite names
        return when (type) {
            SiteType.SETTLEMENT -> "${pick(SETTLEMENT_PREFIX, seed)} ${pick(SETTLEMENT_CORE, s2)}"
            SiteType.TRADER -> "${pick(SHOP_KEEPER, seed)}'s Exchange"
            SiteType.MEDIC -> "${pick(SHOP_KEEPER, seed)}'s Clinic"
            SiteType.FIXER -> "${pick(SHOP_KEEPER, seed)}'s Workshop"
            SiteType.BARKEEP -> "The ${pick(BAR_ADJ, seed)} ${pick(BAR_NOUN, s2)}"
            SiteType.OUTPOST -> "${pick(SETTLEMENT_CORE, seed)} Outpost"
            SiteType.TRIBE -> pick(TRIBE_NAMES, seed)
            SiteType.RUINS -> "${pick(SETTLEMENT_CORE, seed)} Ruins"
            SiteType.GANG_CAMP -> "The ${pick(GANG_ADJ, seed)} ${pick(GANG_NOUN, s2)}"
            SiteType.MONSTER_DEN -> "${pick(BEAST, seed)} ${pick(DEN_FEATURE, s2)}"
            SiteType.VAULT -> "Vault ${(seed % 90 + 90) % 90 + 10}" // Vault 10..99
        }
    }

    /** Build the wasteland site for a real place. [id] seeds the (stable) name via its hash. */
    fun siteFor(id: String, lat: Double, lon: Double, osmCategory: String): WorldSite {
        val type = typeFor(osmCategory)
        return WorldSite(id = id, name = nameFor(type, id.hashCode()), type = type, lat = lat, lon = lon)
    }

    /** The S.P.E.C.I.A.L. stats a site's encounters lean on (for biasing which fights/challenges appear there). */
    fun favoredStats(type: SiteType): Set<Special> = when (type) {
        SiteType.GANG_CAMP -> setOf(Special.STRENGTH, Special.AGILITY, Special.PERCEPTION)
        SiteType.MONSTER_DEN -> setOf(Special.ENDURANCE, Special.PERCEPTION, Special.STRENGTH)
        SiteType.VAULT -> setOf(Special.INTELLIGENCE, Special.AGILITY)
        SiteType.RUINS -> setOf(Special.PERCEPTION, Special.LUCK)
        SiteType.TRIBE -> setOf(Special.CHARISMA, Special.ENDURANCE)
        else -> emptySet()
    }

    /** Whether standing at a [type] site can spawn an encounter/fight (vs. a pure trade stop). */
    fun spawnsEncounter(type: SiteType): Boolean = when (type) {
        SiteType.GANG_CAMP, SiteType.MONSTER_DEN, SiteType.VAULT, SiteType.RUINS, SiteType.TRIBE, SiteType.SETTLEMENT -> true
        else -> false
    }

    /** A one-line arrival brief for walking up to a [site]. */
    fun intro(site: WorldSite): String = when (site.type) {
        SiteType.SETTLEMENT -> "${site.name} — smoke and voices. A settlement holding on. Trade, talk, take work."
        SiteType.TRADER -> "${site.name}. The counter's open. Caps talk."
        SiteType.MEDIC -> "${site.name}. Antiseptic and old blood. Patch up or stock up."
        SiteType.FIXER -> "${site.name}. Sparks and swearing. Gear and salvage change hands here."
        SiteType.BARKEEP -> "${site.name}. Low light, lower company. Drinks, chems, and word from the road."
        SiteType.OUTPOST -> "${site.name}. A waystation. Rest, resupply, move on."
        SiteType.TRIBE -> "${site.name} watch you approach. Outsider. Prove you're worth their time."
        SiteType.RUINS -> "${site.name}. Pre-war bones, picked over — but not all the way. Watch your step."
        SiteType.GANG_CAMP -> "${site.name}. Sentries, fires, and bad intentions. This will not be talked out."
        SiteType.MONSTER_DEN -> "${site.name}. The stench hits first. Something big lairs here, and it's home."
        SiteType.VAULT -> "${site.name}. A sealed door groans open on two centuries of dark. Whatever's inside has waited."
    }
}
