package dev.mascwa.pulse.data.radio

import kotlinx.serialization.Serializable

/** A tunable internet-radio station for the LCARS RADIO feed. [band] is a short genre/flavour tag.
 *  Serializable so favourited stations persist in the settings JSON. */
@Serializable
data class RadioStation(
    val name: String,
    val band: String,
    val streamUrl: String,
    /**
     * How the stream is encoded and at what rate, when the source says so.
     *
     * ⚠️ Both were already being parsed off every directory result and read by nothing — this
     * repository's most recurring defect. They matter together: 64 kbps of AAC and 64 kbps of MP3 do
     * not sound alike, and on a metered connection the rate is what the listening costs. Defaulted,
     * so favourites persisted before this decode unchanged.
     */
    val codec: String = "",
    val kbps: Int = 0,
)

/**
 * Curated free, listener-supported live streams (SomaFM) spanning the dial — a hacker channel, a
 * space-ops channel, public-safety-flavoured ambient, and music across moods. Direct stream URLs, no
 * key required. SomaFM permits direct streaming for personal listening and is listener-supported;
 * an on-screen note attributes it.
 */
val DEFAULT_STATIONS: List<RadioStation> = listOf(
    RadioStation("DEF CON Radio", "HACK · ELECTRONIC", "https://ice1.somafm.com/defcon-256-mp3", codec = "MP3", kbps = 256),
    RadioStation("Mission Control", "SPACE OPS", "https://ice1.somafm.com/missioncontrol-128-mp3", codec = "MP3", kbps = 128),
    RadioStation("SF 10-33", "PUBLIC SAFETY · AMBIENT", "https://ice1.somafm.com/sf1033-128-mp3", codec = "MP3", kbps = 128),
    RadioStation("Groove Salad", "AMBIENT · DOWNTEMPO", "https://ice1.somafm.com/groovesalad-256-mp3", codec = "MP3", kbps = 256),
    RadioStation("Drone Zone", "ATMOSPHERIC", "https://ice1.somafm.com/dronezone-256-mp3", codec = "MP3", kbps = 256),
    RadioStation("The Trip", "PROGRESSIVE", "https://ice1.somafm.com/thetrip-256-mp3", codec = "MP3", kbps = 256),
    RadioStation("Beat Blender", "DEEP HOUSE", "https://ice1.somafm.com/beatblender-128-mp3", codec = "MP3", kbps = 128),
    RadioStation("Indie Pop Rocks", "INDIE", "https://ice1.somafm.com/indiepop-128-mp3", codec = "MP3", kbps = 128),
    RadioStation("Lush", "VOCALS · CHILL", "https://ice1.somafm.com/lush-128-mp3", codec = "MP3", kbps = 128),
    RadioStation("Fluid", "INSTRUMENTAL HIP-HOP", "https://ice1.somafm.com/fluid-128-mp3", codec = "MP3", kbps = 128),
)
