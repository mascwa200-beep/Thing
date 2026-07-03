package dev.mascwa.pulse.core.telemetry

/**
 * In-lore "environmental scan" flavour for the AR **wasteland vision** mode — how the world reads two
 * centuries after the bombs. Original post-nuclear lore (no trademarked text), tagged by subject so the HUD
 * can surface a rotating line over the camera. Pure + CI-tested; the on-device layer rotates by a
 * wall-clock-derived index so the caption drifts on its own.
 */
object WastelandLore {

    /** A tagged scan line: a short SUBJECT + a one-sentence observation of the wasteland. */
    data class Scan(val tag: String, val text: String)

    val LINES: List<Scan> = listOf(
        Scan("ATMOSPHERE", "The sky hangs rust-orange, the sun a pale coin behind a permanent particulate haze."),
        Scan("ATMOSPHERE", "Air tastes of iron and old ash. Every breath carries the dust of a burned century."),
        Scan("RADIATION", "Background count elevated but survivable. Hot spots pool in the low ground — give craters a wide berth."),
        Scan("RADIATION", "Geiger chatter rises near standing water. The rain here remembers the fallout."),
        Scan("FLORA", "What grows now grows wrong — thorned, grey-leafed, thriving where nothing should."),
        Scan("FLORA", "Fungal blooms crust the ruins, feeding on rot and rad alike. Do not eat what you cannot name."),
        Scan("FAUNA", "Movement in the scrub. The creatures that lived through the fire came out larger, and hungrier."),
        Scan("FAUNA", "Insects the size of dogs nest in the culverts. They hunt by vibration — tread soft."),
        Scan("RUINS", "Pre-war bones jut from the dust: rebar ribs, glass teeth, the skeletons of a world that trusted the sky."),
        Scan("RUINS", "Every standing wall is a graveyard and a store-room. Someone died here; something useful was left behind."),
        Scan("SETTLEMENTS", "Smoke on the horizon means people — and people out here are a coin-toss between trade and trouble."),
        Scan("SETTLEMENTS", "The living gather behind scrap walls, trading water for bullets and stories for both."),
        Scan("WEATHER", "A rad-storm is building to the west — a wall of glowing dust that strips flesh and fries circuits."),
        Scan("WEATHER", "The wind carries grit that sandblasts paint from steel. Goggles down when it howls."),
        Scan("HAZARD", "Mines, tripwires, and worse were left by hands long dead. The wasteland keeps its promises."),
        Scan("HAZARD", "That green glow is not a light. Turn back, or count the seconds you linger."),
        Scan("WATER", "Clean water is the only true currency. What runs in the rivers will kill you slower than thirst — but it will kill you."),
        Scan("HISTORY", "Two hundred years since the last siren. The old world's maps are useless; its roads lead only to ruins."),
        Scan("HISTORY", "The Great War lasted a single afternoon. The wasteland has lasted ever since."),
        Scan("SALVAGE", "Everything is salvage now. A working circuit board is worth more than the gold it was soldered with."),
        Scan("NIGHT", "After dark the wasteland belongs to the things that hunt by heat. Keep the fire small and your back to a wall."),
        Scan("FACTIONS", "Raiders claim the roads, traders the crossings, and older things the deep places. Learn whose ground you stand on."),
        Scan("SURVIVAL", "Out here you are three days from dying of thirst, three hours from the cold, and three seconds from a bad decision."),
        Scan("MUTATION", "The rads rewrite what they touch. Some of it walks upright and remembers being human."),
        Scan("SIGNAL", "Static on every band but one — a looping distress call, decades old, from a voice long gone quiet."),
        Scan("TERRAIN", "Glass deserts mark the airburst zones, sand fused smooth where a city used to breathe."),
        Scan("SKY", "On a clear night you can still find the old stars, indifferent above the ash."),
        Scan("DECAY", "Rust is the wasteland's true religion. Given time, it claims steel, memory, and hope alike."),
    )

    /** The scan line at [index], wrapping (negative or large indices are safe). */
    fun scan(index: Int): Scan = LINES[((index % LINES.size) + LINES.size) % LINES.size]

    /** A one-line rendering: "TAG · text". */
    fun render(index: Int): String = scan(index).let { "${it.tag} · ${it.text}" }
}
