package dev.mascwa.pulse.data.radio

/** A tunable internet-radio station for the PIP-BOY RADIO feed. [band] is a short genre/flavour tag. */
data class RadioStation(
    val name: String,
    val band: String,
    val streamUrl: String,
)

/**
 * Curated free, listener-supported live streams (SomaFM) spanning the dial — a hacker channel, a
 * space-ops channel, public-safety-flavoured ambient, and music across moods. Direct stream URLs, no
 * key required. SomaFM permits direct streaming for personal listening and is listener-supported;
 * an on-screen note attributes it.
 */
val DEFAULT_STATIONS: List<RadioStation> = listOf(
    RadioStation("DEF CON Radio", "HACK · ELECTRONIC", "https://ice1.somafm.com/defcon-256-mp3"),
    RadioStation("Mission Control", "SPACE OPS", "https://ice1.somafm.com/missioncontrol-128-mp3"),
    RadioStation("SF 10-33", "PUBLIC SAFETY · AMBIENT", "https://ice1.somafm.com/sf1033-128-mp3"),
    RadioStation("Groove Salad", "AMBIENT · DOWNTEMPO", "https://ice1.somafm.com/groovesalad-256-mp3"),
    RadioStation("Drone Zone", "ATMOSPHERIC", "https://ice1.somafm.com/dronezone-256-mp3"),
    RadioStation("The Trip", "PROGRESSIVE", "https://ice1.somafm.com/thetrip-256-mp3"),
    RadioStation("Beat Blender", "DEEP HOUSE", "https://ice1.somafm.com/beatblender-128-mp3"),
    RadioStation("Indie Pop Rocks", "INDIE", "https://ice1.somafm.com/indiepop-128-mp3"),
    RadioStation("Lush", "VOCALS · CHILL", "https://ice1.somafm.com/lush-128-mp3"),
    RadioStation("Fluid", "INSTRUMENTAL HIP-HOP", "https://ice1.somafm.com/fluid-128-mp3"),
)
