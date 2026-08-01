package dev.mascwa.pulse.core.telemetry

/**
 * The offline animal-habitat map — "which creatures inhabit the ground you're standing on, and what to do
 * about them." This is the pure, CI-tested, FULLY-OFFLINE brain: from a real coordinate it estimates the
 * continent + biome (deterministic latitude/longitude heuristics — no network, no map tiles) and resolves
 * the characteristic fauna of that region — the dangerous ones (bears, big cats, venomous snakes, crocs)
 * AND the ordinary ones (deer, foxes, birds) that usually occupy it. Each [Animal] carries genuinely useful
 * field survival detail: how to identify it, how it behaves, what to do if you meet it, and bite/sting first
 * aid. The on-device layer draws this as a location-centred, clickable danger heat-map.
 *
 * Honesty: this is a REGIONAL model — "species known to inhabit this biome", not a live GPS tracker of
 * individual animals (that data doesn't exist offline). The biome estimate is a coarse climate heuristic
 * (latitude bands + coarse continental boxes), deliberately conservative. No Android types; the app supplies
 * the coordinate. Content lives in [AnimalCatalog].
 */

/** The seven continental landmasses — chooses region-appropriate fauna (a savanna in Africa ≠ one in Australia). */
enum class Continent(val label: String) {
    NORTH_AMERICA("North America"), SOUTH_AMERICA("South America"), EUROPE("Europe"),
    AFRICA("Africa"), ASIA("Asia"), OCEANIA("Oceania"), ANTARCTICA("Antarctica"),
}

/** A coarse climate/vegetation zone, estimated from latitude + region. Drives the fauna list. */
enum class Biome(val label: String, val blurb: String) {
    TROPICAL_RAINFOREST("Tropical Rainforest", "Hot, wet, dense canopy — teeming with life, much of it venomous or camouflaged."),
    SAVANNA("Savanna & Grassland", "Warm open grassland with scattered trees — home to big grazers and the predators that hunt them."),
    DESERT("Desert & Arid", "Hot, dry, sparse cover — reptiles, arachnids and heat-adapted mammals rule here."),
    MEDITERRANEAN("Mediterranean Scrub", "Warm dry summers, mild wet winters — scrub, snakes, boar and small predators."),
    TEMPERATE_FOREST("Temperate Forest", "Four-season woodland — deer, bears, big cats, and forest snakes."),
    GRASSLAND("Temperate Grassland", "Open plains and prairie — grazers, rodents, coyotes and grassland snakes."),
    BOREAL_FOREST("Boreal Forest (Taiga)", "Cold northern conifer forest — bears, wolves, moose, lynx."),
    TUNDRA("Tundra", "Frozen treeless ground — hardy mammals, and in the far north, polar bears."),
    MOUNTAIN("Mountain & Alpine", "High, cold, steep — goats, big cats, bears and altitude hazards."),
    WETLAND("Wetland & Swamp", "Water-logged ground — crocodilians, water snakes, leeches and biting insects."),
    COAST("Coast & Reef", "Shoreline and shallow sea — marine stingers, sharks, rays and shorebirds."),
    POLAR("Polar", "Ice and extreme cold — polar bears, seals, and little else on land."),
}

/** How dangerous a region reads at a glance, for the heat-map colour ramp. */
enum class DangerLevel(val label: String) { CALM("Calm"), LOW("Low"), MODERATE("Moderate"), HIGH("High"), SEVERE("Severe") }

enum class AnimalKind(val label: String) {
    MAMMAL("Mammal"), REPTILE("Reptile"), ARACHNID("Arachnid"), INSECT("Insect"),
    BIRD("Bird"), AMPHIBIAN("Amphibian"), MARINE("Marine"),
}

/**
 * One species with the field detail that matters when you meet it. [danger] 0 (harmless) … 4 (can kill).
 * [hostile] = will actively threaten/attack a human (vs. dangerous only if provoked/handled).
 */
data class Animal(
    val id: String,
    val name: String,
    val kind: AnimalKind,
    val danger: Int,
    val hostile: Boolean,
    /** How to recognise it. */
    val identify: String,
    /** Typical behaviour — and when it becomes a threat. */
    val behavior: String,
    /** Exactly what to do if you encounter it. */
    val ifEncountered: String,
    /** Bite/sting/attack first aid, or null if not applicable. */
    val firstAid: String? = null,
)

/** The resolved read of a location: where it is, its biome, and the fauna that occupy it. */
data class Habitat(
    val continent: Continent,
    val biome: Biome,
    /** Fauna of this region, most dangerous first (hostiles lead, harmless ones still listed). */
    val animals: List<Animal>,
) {
    /** The peak danger present, for the heat-map colour. */
    fun dangerLevel(): DangerLevel = when (animals.maxOfOrNull { it.danger } ?: 0) {
        0 -> DangerLevel.CALM
        1 -> DangerLevel.LOW
        2 -> DangerLevel.MODERATE
        3 -> DangerLevel.HIGH
        else -> DangerLevel.SEVERE
    }

    val hostiles: List<Animal> get() = animals.filter { it.hostile }
    fun summary(): String {
        val threats = animals.filter { it.danger >= 3 }.take(3).joinToString(", ") { it.name }
        return if (threats.isBlank()) "${biome.label}: no major animal threats recorded here."
        else "${biome.label}: watch for $threats."
    }
}

object AnimalHabitats {

    /** Estimate the continent from a coordinate via coarse bounding boxes (offline, deterministic). */
    fun continentFor(lat: Double, lon: Double): Continent {
        // Normalise longitude to -180..180.
        val x = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return when {
            lat < -60.0 -> Continent.ANTARCTICA
            // Australia + New Zealand + near Pacific.
            lat in -48.0..-9.0 && x in 110.0..179.0 -> Continent.OCEANIA
            // The Americas sit in the western hemisphere.
            x in -170.0..-52.0 && lat >= 7.0 -> Continent.NORTH_AMERICA
            x in -82.0..-34.0 && lat < 13.0 -> Continent.SOUTH_AMERICA
            x in -92.0..-52.0 && lat in 7.0..14.0 -> Continent.NORTH_AMERICA // Central America
            // Africa: straddles the equator, west-central longitudes.
            lat in -35.0..37.0 && x in -18.0..52.0 -> Continent.AFRICA
            // Europe: north of the Mediterranean, west of the Urals-ish.
            lat in 36.0..72.0 && x in -25.0..40.0 -> Continent.EUROPE
            // Everything else in the eastern hemisphere at temperate/tropical latitudes → Asia.
            x in 25.0..180.0 && lat in -11.0..78.0 -> Continent.ASIA
            // Fallbacks by hemisphere.
            x < -30.0 -> if (lat >= 13.0) Continent.NORTH_AMERICA else Continent.SOUTH_AMERICA
            else -> Continent.ASIA
        }
    }

    /**
     * Estimate the biome from a coordinate. Latitude drives the climate band; a handful of well-known arid /
     * rainforest regions are special-cased so the common cases read right. Coarse but deterministic + offline.
     */
    fun biomeFor(lat: Double, lon: Double): Biome {
        val x = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val a = kotlin.math.abs(lat)
        // Polar / subpolar first.
        if (a >= 66.0) return if (a >= 75.0) Biome.POLAR else Biome.TUNDRA
        if (a in 55.0..66.0) return Biome.BOREAL_FOREST

        // Well-known deserts (subtropical highs).
        if (isDesert(lat, x)) return Biome.DESERT
        // Well-known tropical rainforest belts.
        if (isRainforest(lat, x)) return Biome.TROPICAL_RAINFOREST

        // Tropical grassland / savanna band around the deserts and equator.
        if (a < 23.5) return Biome.SAVANNA

        // Mediterranean-climate west coasts / basins ~30-45°.
        if (a in 30.0..45.0 && isMediterranean(lat, x)) return Biome.MEDITERRANEAN

        // Temperate: forest by default; interior continental longitudes read as grassland/prairie/steppe.
        return if (isContinentalInterior(lat, x)) Biome.GRASSLAND else Biome.TEMPERATE_FOREST
    }

    private fun isDesert(lat: Double, x: Double): Boolean {
        val a = kotlin.math.abs(lat)
        return when {
            lat in 12.0..33.0 && x in -17.0..40.0 -> true          // Sahara / Arabian
            lat in 15.0..35.0 && x in 40.0..60.0 -> true           // Arabian / Iranian
            lat in 32.0..48.0 && x in 60.0..112.0 -> true          // Central Asian / Gobi
            lat in -33.0..-18.0 && x in 113.0..142.0 -> true       // Australian outback
            lat in 24.0..38.0 && x in -118.0..-103.0 -> true       // US Southwest / Mexican
            lat in -30.0..-18.0 && x in -72.0..-65.0 -> true       // Atacama
            lat in -30.0..-20.0 && x in 14.0..24.0 -> true         // Namib / Kalahari edge
            a in 20.0..30.0 && x in 68.0..78.0 -> true             // Thar
            else -> false
        }
    }

    private fun isRainforest(lat: Double, x: Double): Boolean = when {
        lat in -10.0..8.0 && x in -80.0..-45.0 -> true             // Amazon
        lat in -5.0..7.0 && x in 8.0..30.0 -> true                 // Congo
        lat in -10.0..20.0 && x in 92.0..155.0 -> true             // SE Asia / Indonesia / New Guinea
        lat in -18.0..-14.0 && x in 145.0..149.0 -> true           // NE Australia (Daintree)
        else -> false
    }

    private fun isMediterranean(lat: Double, x: Double): Boolean = when {
        lat in 30.0..45.0 && x in -10.0..37.0 -> true              // the Mediterranean basin itself
        lat in 32.0..42.0 && x in -123.0..-116.0 -> true           // California
        lat in -37.0..-30.0 && x in 18.0..26.0 -> true             // South African Cape
        lat in -38.0..-31.0 && x in 115.0..140.0 -> true           // SW/S Australia
        lat in -37.0..-30.0 && x in -73.0..-70.0 -> true           // Central Chile
        else -> false
    }

    private fun isContinentalInterior(lat: Double, x: Double): Boolean = when {
        lat in 30.0..50.0 && x in -105.0..-90.0 -> true            // Great Plains
        lat in 45.0..55.0 && x in 30.0..90.0 -> true               // Eurasian steppe
        lat in -40.0..-32.0 && x in -66.0..-58.0 -> true           // Pampas
        else -> false
    }

    /**
     * The full offline read for a coordinate: continent + biome + the region's fauna, most-dangerous first
     * (hostiles lead; harmless residents still listed so it's the WHOLE animal picture, not just threats).
     */
    fun habitatFor(lat: Double, lon: Double): Habitat {
        val continent = continentFor(lat, lon)
        val biome = biomeFor(lat, lon)
        val animals = AnimalCatalog.faunaFor(biome, continent)
            .mapNotNull { AnimalCatalog.ANIMALS[it] }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Animal> { it.hostile }.thenByDescending { it.danger }.thenBy { it.name })
        return Habitat(continent, biome, animals)
    }

    /** A short human label for a danger score. */
    fun dangerLabel(danger: Int): String = when (danger.coerceIn(0, 4)) {
        0 -> "Harmless"; 1 -> "Minor"; 2 -> "Caution"; 3 -> "Dangerous"; else -> "Deadly"
    }
}
