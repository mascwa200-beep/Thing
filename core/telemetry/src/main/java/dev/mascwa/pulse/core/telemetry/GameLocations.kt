package dev.mascwa.pulse.core.telemetry

/**
 * Locations + NPCs for the S.P.E.C.I.A.L. game — the pure model behind the real-world map (the next
 * slice). A real shop POI becomes a game [GameLocation] whose [LocationKind] (derived from its OSM
 * category) decides who runs it, what they sell, and how they talk. Everything here is pure + CI-tested;
 * the on-device map layer supplies the coordinates and drives the shop ([SpecialGame.buyItem]) + the
 * conversation ([SpecialGame.resolve] over the repeatable [conversation] encounter).
 */
enum class LocationKind(val title: String, val role: String) {
    TRADER("Trader", "buys and sells general supplies"),
    MEDIC("Medic", "sells aid and stems the bleeding"),
    FIXER("Fixer", "deals in gear and salvage"),
    BARKEEP("Barkeep", "pours drinks, trades chems and rumours"),
    OUTPOST("Outpost", "a waystation with the basics"),
}

/** A real-world place, reimagined as a game location. [lat]/[lon] come from the POI on the map. */
data class GameLocation(
    val id: String,
    val name: String,
    val kind: LocationKind,
    val lat: Double,
    val lon: Double,
)

/** What an NPC offers: a shop (buy from [stock]) and a stat-gated [conversation]. */
sealed interface NpcAction {
    data class Shop(val stock: List<String>) : NpcAction
    data class Talk(val encounter: Encounter) : NpcAction
}

object GameLocations {

    /** Map an OSM shop/amenity category value to a game [LocationKind] (defaults to [LocationKind.TRADER]). */
    fun kindFor(osmCategory: String): LocationKind {
        val c = osmCategory.lowercase()
        return when {
            c.contains("pharmacy") || c.contains("chemist") || c.contains("hospital") ||
                c.contains("clinic") || c.contains("doctor") || c.contains("health") -> LocationKind.MEDIC
            c.contains("hardware") || c.contains("doityourself") || c.contains("electronic") ||
                c.contains("trade") || c.contains("car_repair") || c.contains("hifi") -> LocationKind.FIXER
            c.contains("bar") || c.contains("pub") || c.contains("cafe") || c.contains("restaurant") ||
                c.contains("fast_food") || c.contains("biergarten") -> LocationKind.BARKEEP
            c.contains("fuel") || c.contains("charging") || c.contains("kiosk") -> LocationKind.OUTPOST
            c.contains("supermarket") || c.contains("convenience") || c.contains("general") ||
                c.contains("mall") || c.contains("department") || c.contains("grocery") -> LocationKind.TRADER
            else -> LocationKind.TRADER
        }
    }

    /** The item ids a [kind] sells (buy at [Item.value] via [SpecialGame.buyItem]). All resolve in [Items]. */
    fun stock(kind: LocationKind): List<String> = when (kind) {
        LocationKind.TRADER -> listOf("clean_water", "ration_pack", "bandage", "trauma_patch", "scrap_metal", "wire_spool", "fusion_cell", "gold_trinket")
        LocationKind.MEDIC -> listOf("bandage", "trauma_patch", "medkit", "auto_injector", "surgeon_kit", "grit_ration")
        LocationKind.FIXER -> listOf("grip_gloves", "optics_visor", "leather_rig", "runner_boots", "data_slate", "comms_badge", "lucky_charm", "power_gauntlet", "recon_optics", "combat_webbing", "negotiator_suit", "neural_implant", "sprint_servos", "fortune_idol")
        LocationKind.BARKEEP -> listOf("brute_serum", "focus_tabs", "adrenaline", "silver_tongue", "rabbit_foot", "titan_serum", "quicksilver", "fortune_vial")
        LocationKind.OUTPOST -> listOf("clean_water", "ration_pack", "medkit", "scrap_metal", "circuit_board")
    }

    private val TRADER_NAMES = listOf("Mick", "Dara", "Old Pete", "Sal", "Junko")
    private val MEDIC_NAMES = listOf("Doc Reyes", "Nurse Kell", "Mabel", "Doc Ferro")
    private val FIXER_NAMES = listOf("Rivet", "Sparks", "Cog", "Wrenn")
    private val BARKEEP_NAMES = listOf("Whistle", "Gus", "Red", "Tally")
    private val OUTPOST_NAMES = listOf("the quartermaster", "the warden", "the keeper")

    /** A stable NPC name for a [kind], chosen by [seed] (e.g. the POI id's hash) so it doesn't churn. */
    fun npcName(kind: LocationKind, seed: Int): String {
        val pool = when (kind) {
            LocationKind.TRADER -> TRADER_NAMES
            LocationKind.MEDIC -> MEDIC_NAMES
            LocationKind.FIXER -> FIXER_NAMES
            LocationKind.BARKEEP -> BARKEEP_NAMES
            LocationKind.OUTPOST -> OUTPOST_NAMES
        }
        return pool[(seed % pool.size + pool.size) % pool.size]
    }

    /** A one-line greeting an NPC gives when you walk up. */
    fun greeting(kind: LocationKind, npc: String): String = when (kind) {
        LocationKind.TRADER -> "$npc looks up from the counter. \"Caps talk. What'll it be?\""
        LocationKind.MEDIC -> "$npc wipes their hands. \"Hurt, or just shopping?\""
        LocationKind.FIXER -> "$npc doesn't stop working. \"Parts, gear, or you selling?\""
        LocationKind.BARKEEP -> "$npc slides a rag down the bar. \"Drink? Or you after word from the road?\""
        LocationKind.OUTPOST -> "$npc waves you in. \"Traveller. Rest, resupply, move on.\""
    }

    /**
     * A repeatable, single-choice CHARISMA conversation with the NPC — win a small reward (a tip, a
     * freebie), lose nothing much. Resolved with the normal [SpecialGame.resolve] pipeline (perks, LUCK,
     * env, crits all apply), so it's just another kind of encounter.
     */
    fun conversation(kind: LocationKind, npc: String): Encounter {
        val choiceText: String
        val ok: Outcome
        val no: Outcome
        when (kind) {
            LocationKind.TRADER -> {
                choiceText = "Charm a deal out of $npc."
                ok = Outcome("$npc likes your face and slips you a few caps off the top.", xp = 10, caps = 14)
                no = Outcome("$npc has heard every line. \"Buy something or move along.\"")
            }
            LocationKind.MEDIC -> {
                choiceText = "Trade $npc a story for a freebie."
                ok = Outcome("$npc laughs and tosses you a dressing. \"On the house.\"", xp = 10, items = mapOf("bandage" to 1))
                no = Outcome("$npc isn't in the mood. \"I'm busy. Next.\"")
            }
            LocationKind.FIXER -> {
                choiceText = "Talk shop with $npc."
                ok = Outcome("$npc respects a fellow scavver and points you to a scrap cache.", xp = 12, items = mapOf("scrap_metal" to 1))
                no = Outcome("$npc grunts and goes back to the bench.")
            }
            LocationKind.BARKEEP -> {
                choiceText = "Buy $npc's ear for a rumour."
                ok = Outcome("$npc leans in. \"Word is there's caps out past the ridge…\"", xp = 12, caps = 18)
                no = Outcome("$npc shrugs. \"Nothing worth your caps tonight.\"")
            }
            LocationKind.OUTPOST -> {
                choiceText = "Ask $npc what's down the road."
                ok = Outcome("$npc marks a safe route on your map. Saves you a world of trouble.", xp = 10, caps = 8)
                no = Outcome("$npc has nothing new for you.")
            }
        }
        return Encounter(
            id = "talk_${kind.name.lowercase()}",
            title = npc,
            prompt = greeting(kind, npc),
            choices = listOf(Choice(choiceText, Special.CHARISMA, 10, ok, no)),
            repeatable = true,
        )
    }

    /** The actions available at [location] — browse the shop, and make conversation. */
    fun actionsFor(location: GameLocation): List<Pair<String, NpcAction>> {
        val npc = npcName(location.kind, location.id.hashCode())
        return listOf(
            "Browse wares" to NpcAction.Shop(stock(location.kind)),
            "Talk to $npc" to NpcAction.Talk(conversation(location.kind, npc)),
        )
    }
}
