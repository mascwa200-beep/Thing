package dev.mascwa.pulse.core.telemetry

/**
 * Crafting for the S.P.E.C.I.A.L. game — turn scavenged JUNK into useful gear/aid at a workbench. Gives
 * salvage a second life beyond selling, and rewards INTELLIGENCE (the tech recipes are stat-gated). Pure +
 * CI-tested; reuses the engine's inventory ops. A [Recipe] consumes [inputs] (itemId → count) and yields
 * [outputQty] of [outputId], granting a little [xp] for the effort.
 */
data class Recipe(
    val id: String,
    val name: String,
    val desc: String,
    val inputs: Map<String, Int>,
    val outputId: String,
    val outputQty: Int = 1,
    /** Optional stat gate — you need [minStat] in [stat] to attempt the craft (null = anyone can). */
    val stat: Special? = null,
    val minStat: Int = 0,
    val xp: Int = 0,
)

object Recipes {
    val ALL: List<Recipe> = listOf(
        Recipe("craft_patchkit", "Field Patch Kit", "Combine dressings and water into a proper medkit.",
            mapOf("bandage" to 2, "clean_water" to 1), "medkit", xp = 6),
        Recipe("craft_grip", "Grip Wraps", "Wrap scrap and wire into a firmer grip.",
            mapOf("scrap_metal" to 2, "wire_spool" to 1), "grip_gloves", xp = 6),
        Recipe("craft_rig", "Reinforced Rig", "Beat scrap into a chest rig that soaks a hit.",
            mapOf("scrap_metal" to 3, "wire_spool" to 1), "leather_rig", xp = 8),
        Recipe("craft_boots", "Runner's Kit", "Line boots with light alloy for the long roads.",
            mapOf("scrap_metal" to 2, "rare_alloy" to 1), "runner_boots", xp = 8),
        Recipe("craft_slate", "Signal Slate", "Solder boards and wire into a working data slate.",
            mapOf("circuit_board" to 2, "wire_spool" to 1), "data_slate", stat = Special.INTELLIGENCE, minStat = 5, xp = 10),
        Recipe("craft_visor", "Optic Rig", "Build a heads-up visor from salvaged optics.",
            mapOf("circuit_board" to 1, "scrap_metal" to 2, "wire_spool" to 1), "optics_visor", stat = Special.INTELLIGENCE, minStat = 5, xp = 10),
        Recipe("craft_injector", "Trauma Injector", "Rig a one-shot auto-injector from a medkit and a board.",
            mapOf("medkit" to 1, "circuit_board" to 1), "auto_injector", stat = Special.INTELLIGENCE, minStat = 6, xp = 12),
        Recipe("craft_focus", "Focus Compound", "Refine alloy traces into a focus stimulant.",
            mapOf("rare_alloy" to 1, "wire_spool" to 1), "focus_tabs", stat = Special.INTELLIGENCE, minStat = 6, xp = 8),
        // --- AID ---
        Recipe("craft_trauma", "Trauma Patch", "Pack a dressing with a clotting agent for a deeper seal.",
            mapOf("bandage" to 2, "rare_alloy" to 1), "trauma_patch", xp = 6),
        Recipe("craft_surgeon", "Surgeon's Kit", "Assemble a full field-surgery kit from a medkit and an injector.",
            mapOf("medkit" to 1, "auto_injector" to 1), "surgeon_kit", stat = Special.INTELLIGENCE, minStat = 6, xp = 14),
        // --- GEAR: tier up a +1 piece into its +2 version with a power source ---
        Recipe("craft_gauntlet", "Power Gauntlet", "Bolt a fusion cell to your grip gloves for a servo-assisted fist.",
            mapOf("grip_gloves" to 1, "fusion_cell" to 1, "scrap_metal" to 2), "power_gauntlet", stat = Special.INTELLIGENCE, minStat = 6, xp = 14),
        Recipe("craft_recon", "Recon Optics", "Upgrade the visor with a fusion-fed sensor board.",
            mapOf("optics_visor" to 1, "fusion_cell" to 1, "circuit_board" to 1), "recon_optics", stat = Special.INTELLIGENCE, minStat = 7, xp = 14),
        Recipe("craft_webbing", "Combat Webbing", "Weave alloy plating into the rig for real protection.",
            mapOf("leather_rig" to 1, "fusion_cell" to 1, "scrap_metal" to 2), "combat_webbing", stat = Special.INTELLIGENCE, minStat = 6, xp = 14),
        Recipe("craft_negotiator", "Negotiator's Suit", "Trim the comms badge into a sharp gold-threaded suit.",
            mapOf("comms_badge" to 1, "gold_trinket" to 1, "wire_spool" to 2), "negotiator_suit", stat = Special.INTELLIGENCE, minStat = 6, xp = 14),
        Recipe("craft_servos", "Sprint Servos", "Fit the boots with fusion-driven leg servos.",
            mapOf("runner_boots" to 1, "fusion_cell" to 1, "rare_alloy" to 1), "sprint_servos", stat = Special.INTELLIGENCE, minStat = 6, xp = 14),
        Recipe("craft_neural", "Neural Implant", "Fuse a data slate and cells into a cortical implant.",
            mapOf("data_slate" to 1, "circuit_board" to 2, "fusion_cell" to 1), "neural_implant", stat = Special.INTELLIGENCE, minStat = 8, xp = 18),
        Recipe("craft_idol", "Fortune Idol", "Cast salvaged gold and alloy into a lucky idol.",
            mapOf("gold_trinket" to 2, "rare_alloy" to 1), "fortune_idol", stat = Special.LUCK, minStat = 6, xp = 16),
        // --- PROVISIONS: cook food from basic supplies (no gate — anyone can run a camp stove) ---
        Recipe("craft_canteen", "Fill a Canteen", "Rig scrap into a canteen and seal a water ration.",
            mapOf("clean_water" to 1, "scrap_metal" to 1), "water_ration", xp = 4),
        Recipe("craft_stew", "Cook a Stew", "Simmer rations and water into a hot, filling meal.",
            mapOf("ration_pack" to 1, "clean_water" to 1), "hearty_stew", xp = 6),
        Recipe("craft_jerky", "Cure Jerky", "Salt and dry a couple of ration packs into trail jerky.",
            mapOf("ration_pack" to 2), "trail_jerky", xp = 5),
        // --- MEDICINE: brew cures for the afflictions (INT-gated — it's chemistry) ---
        Recipe("craft_antibiotics", "Distill Antibiotics", "Culture a medkit's agents into infection-clearing pills.",
            mapOf("medkit" to 1, "clean_water" to 1), "antibiotics", stat = Special.INTELLIGENCE, minStat = 5, xp = 10),
        Recipe("craft_dentalkit", "Field Dental Kit", "Fashion a brush, floss and a filling from a dressing and wire.",
            mapOf("bandage" to 1, "wire_spool" to 1), "dental_kit", stat = Special.INTELLIGENCE, minStat = 5, xp = 8),
        Recipe("craft_painkillers", "Mix Painkillers", "Refine a medkit and alloy traces into a broad analgesic.",
            mapOf("medkit" to 1, "rare_alloy" to 1), "painkillers", stat = Special.INTELLIGENCE, minStat = 6, xp = 10),
    )

    private val byId: Map<String, Recipe> = ALL.associateBy { it.id }
    fun byId(id: String): Recipe? = byId[id]
}
