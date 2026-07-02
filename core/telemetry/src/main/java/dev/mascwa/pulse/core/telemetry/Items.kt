package dev.mascwa.pulse.core.telemetry

/**
 * The wasteland item economy for the S.P.E.C.I.A.L. game. Pure data + a stable catalog, so the engine
 * ([SpecialGame]) can grant loot from encounters, let the player heal or buff a check, and trade caps at
 * shops. On-device inventory is a plain `Map<itemId, count>` on the [Character]; this file is what those
 * ids mean. No Android types — CI-testable.
 *
 * Kinds:
 *  - [ItemKind.AID]  — used from the inventory to restore HP (consumed).
 *  - [ItemKind.CHEM] — used on a single stat check for a temporary [statBonus] (consumed).
 *  - [ItemKind.GEAR] — a passive [statBonus] to every matching check while it's carried (never consumed).
 *  - [ItemKind.JUNK] — no effect; sells for caps.
 *  - [ItemKind.QUEST]— story/keys; never sold or consumed by the generic paths.
 *
 * Names are original homage (no trademarked product names), leaning survivalist to match the "the game
 * bleeds into the real world" direction — rations, water, medkits, field gear.
 */
enum class ItemKind { AID, CHEM, GEAR, JUNK, QUEST }

/**
 * One item type. [value] is the caps price at a shop (sells for roughly half). [statBonus]/[statBonusAmt]
 * apply for CHEM (on a chosen check) and GEAR (passive). [healAmt] is the HP an AID restores. [rarity]
 * (1 common … 5 rare) drives loot weighting for shops/drops later.
 */
data class Item(
    val id: String,
    val name: String,
    val desc: String,
    val kind: ItemKind,
    val statBonus: Special? = null,
    val statBonusAmt: Int = 0,
    val healAmt: Int = 0,
    val value: Int = 0,
    val rarity: Int = 1,
)

object Items {
    // --- AID: restore HP ---
    val WATER = Item("clean_water", "Clean Water", "A sealed canteen. Restores 8 HP.", ItemKind.AID, healAmt = 8, value = 10, rarity = 1)
    val BANDAGE = Item("bandage", "Field Dressing", "Stops the bleeding. Restores 12 HP.", ItemKind.AID, healAmt = 12, value = 14, rarity = 1)
    val RATION = Item("ration_pack", "Ration Pack", "A day's calories. Restores 16 HP.", ItemKind.AID, healAmt = 16, value = 18, rarity = 1)
    val MEDKIT = Item("medkit", "Field Medkit", "Proper triage. Restores 28 HP.", ItemKind.AID, healAmt = 28, value = 40, rarity = 2)
    val TRAUMA_PATCH = Item("trauma_patch", "Trauma Patch", "Clots and seals a wound. Restores 20 HP.", ItemKind.AID, healAmt = 20, value = 26, rarity = 2)
    val INJECTOR = Item("auto_injector", "Auto-Injector", "One-shot trauma kit. Restores 45 HP.", ItemKind.AID, healAmt = 45, value = 75, rarity = 3)
    val SURGEON_KIT = Item("surgeon_kit", "Surgeon's Kit", "A full field surgery. Restores 60 HP.", ItemKind.AID, healAmt = 60, value = 120, rarity = 4)

    // --- CHEM: temporary +stat on one check (consumed) ---
    val BRUTE = Item("brute_serum", "Brute Serum", "+3 STRENGTH on your next check.", ItemKind.CHEM, Special.STRENGTH, 3, value = 30, rarity = 2)
    val OWL_DROPS = Item("owl_drops", "Owl Drops", "+3 PERCEPTION on your next check.", ItemKind.CHEM, Special.PERCEPTION, 3, value = 28, rarity = 2)
    val GRIT = Item("grit_ration", "Grit Ration", "+3 ENDURANCE on your next check.", ItemKind.CHEM, Special.ENDURANCE, 3, value = 26, rarity = 2)
    val SILVER_TONGUE = Item("silver_tongue", "Silver Tongue", "+3 CHARISMA on your next check.", ItemKind.CHEM, Special.CHARISMA, 3, value = 28, rarity = 2)
    val FOCUS_TABS = Item("focus_tabs", "Focus Tabs", "+3 INTELLIGENCE on your next check.", ItemKind.CHEM, Special.INTELLIGENCE, 3, value = 30, rarity = 2)
    val ADRENALINE = Item("adrenaline", "Adrenaline Shot", "+3 AGILITY on your next check.", ItemKind.CHEM, Special.AGILITY, 3, value = 28, rarity = 2)
    val RABBIT_FOOT = Item("rabbit_foot", "Rabbit's Foot", "+3 LUCK on your next check.", ItemKind.CHEM, Special.LUCK, 3, value = 45, rarity = 3)
    // Rare high-grade chems — a bigger one-check kick.
    val TITAN_SERUM = Item("titan_serum", "Titan Serum", "+4 STRENGTH on your next check.", ItemKind.CHEM, Special.STRENGTH, 4, value = 60, rarity = 4)
    val QUICKSILVER = Item("quicksilver", "Quicksilver", "+4 AGILITY on your next check.", ItemKind.CHEM, Special.AGILITY, 4, value = 58, rarity = 4)
    val FORTUNE_VIAL = Item("fortune_vial", "Fortune Vial", "+4 LUCK on your next check.", ItemKind.CHEM, Special.LUCK, 4, value = 90, rarity = 5)

    // --- GEAR: passive +1 to a stat while carried ---
    val GRIP_GLOVES = Item("grip_gloves", "Grip Gloves", "+1 STRENGTH while carried.", ItemKind.GEAR, Special.STRENGTH, 1, value = 55, rarity = 3)
    val OPTICS_VISOR = Item("optics_visor", "Optics Visor", "+1 PERCEPTION while carried.", ItemKind.GEAR, Special.PERCEPTION, 1, value = 70, rarity = 3)
    val LEATHER_RIG = Item("leather_rig", "Leather Rig", "+1 ENDURANCE while carried.", ItemKind.GEAR, Special.ENDURANCE, 1, value = 60, rarity = 3)
    val COMMS_BADGE = Item("comms_badge", "Comms Badge", "+1 CHARISMA while carried.", ItemKind.GEAR, Special.CHARISMA, 1, value = 60, rarity = 3)
    val DATA_SLATE = Item("data_slate", "Data Slate", "+1 INTELLIGENCE while carried.", ItemKind.GEAR, Special.INTELLIGENCE, 1, value = 65, rarity = 3)
    val RUNNER_BOOTS = Item("runner_boots", "Runner Boots", "+1 AGILITY while carried.", ItemKind.GEAR, Special.AGILITY, 1, value = 55, rarity = 3)
    val LUCKY_CHARM = Item("lucky_charm", "Lucky Charm", "+1 LUCK while carried.", ItemKind.GEAR, Special.LUCK, 1, value = 70, rarity = 3)
    // Rare +2 gear — a full higher tier for the well-equipped operative.
    val POWER_GAUNTLET = Item("power_gauntlet", "Power Gauntlet", "+2 STRENGTH while carried.", ItemKind.GEAR, Special.STRENGTH, 2, value = 140, rarity = 4)
    val RECON_OPTICS = Item("recon_optics", "Recon Optics", "+2 PERCEPTION while carried.", ItemKind.GEAR, Special.PERCEPTION, 2, value = 150, rarity = 4)
    val COMBAT_WEBBING = Item("combat_webbing", "Combat Webbing", "+2 ENDURANCE while carried.", ItemKind.GEAR, Special.ENDURANCE, 2, value = 140, rarity = 4)
    val NEGOTIATOR_SUIT = Item("negotiator_suit", "Negotiator's Suit", "+2 CHARISMA while carried.", ItemKind.GEAR, Special.CHARISMA, 2, value = 145, rarity = 4)
    val NEURAL_IMPLANT = Item("neural_implant", "Neural Implant", "+2 INTELLIGENCE while carried.", ItemKind.GEAR, Special.INTELLIGENCE, 2, value = 160, rarity = 5)
    val SPRINT_SERVOS = Item("sprint_servos", "Sprint Servos", "+2 AGILITY while carried.", ItemKind.GEAR, Special.AGILITY, 2, value = 140, rarity = 4)
    val FORTUNE_IDOL = Item("fortune_idol", "Fortune Idol", "+2 LUCK while carried.", ItemKind.GEAR, Special.LUCK, 2, value = 170, rarity = 5)

    // --- JUNK: sell for caps ---
    val SCRAP = Item("scrap_metal", "Scrap Metal", "Salvage. Sells for caps.", ItemKind.JUNK, value = 8, rarity = 1)
    val WIRE = Item("wire_spool", "Wire Spool", "Salvage. Sells for caps.", ItemKind.JUNK, value = 6, rarity = 1)
    val CIRCUIT = Item("circuit_board", "Circuit Board", "Intact electronics. Sells well.", ItemKind.JUNK, value = 16, rarity = 2)
    val FUSION_CELL = Item("fusion_cell", "Fusion Cell", "A charged pre-collapse power cell. Sells well.", ItemKind.JUNK, value = 24, rarity = 3)
    val ALLOY = Item("rare_alloy", "Rare Alloy", "Pre-collapse metallurgy. Fetches a premium.", ItemKind.JUNK, value = 34, rarity = 3)
    val GOLD_TRINKET = Item("gold_trinket", "Gold Trinket", "Untarnished old-world gold. A trader's delight.", ItemKind.JUNK, value = 50, rarity = 4)

    /** Every item, in a stable order. */
    val ALL: List<Item> = listOf(
        WATER, BANDAGE, RATION, MEDKIT, TRAUMA_PATCH, INJECTOR, SURGEON_KIT,
        BRUTE, OWL_DROPS, GRIT, SILVER_TONGUE, FOCUS_TABS, ADRENALINE, RABBIT_FOOT, TITAN_SERUM, QUICKSILVER, FORTUNE_VIAL,
        GRIP_GLOVES, OPTICS_VISOR, LEATHER_RIG, COMMS_BADGE, DATA_SLATE, RUNNER_BOOTS, LUCKY_CHARM,
        POWER_GAUNTLET, RECON_OPTICS, COMBAT_WEBBING, NEGOTIATOR_SUIT, NEURAL_IMPLANT, SPRINT_SERVOS, FORTUNE_IDOL,
        SCRAP, WIRE, CIRCUIT, FUSION_CELL, ALLOY, GOLD_TRINKET,
    )

    private val byId: Map<String, Item> = ALL.associateBy { it.id }

    /** Look up an item by its stable id, or null if unknown (defensive — persisted ids may drift). */
    fun byId(id: String): Item? = byId[id]

    /** Items of a given [kind], in catalog order. */
    fun ofKind(kind: ItemKind): List<Item> = ALL.filter { it.kind == kind }
}
