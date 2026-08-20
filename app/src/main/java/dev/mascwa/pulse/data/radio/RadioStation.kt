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
    /**
     * The directory's own stable id for this station, where it came from a directory.
     *
     * ⚠️ Favourites are persisted as whole stations and were matched **by stream URL**, which is
     * the one field a directory entry is free to change. When a station moves its stream — a new
     * CDN, a switch to https — the favourite silently stops matching itself: the star goes out, and
     * starring it again just adds a second copy. This is the identity that does not move. Blank for
     * the curated SomaFM list, which is a constant in this repository and cannot drift.
     */
    val uuid: String = "",
    /**
     * The language the station broadcasts in, as the directory states it.
     *
     * Present on the directory response and discarded, which left a "near you" list in which
     * nothing distinguished a station you can understand from one you cannot.
     */
    val language: String = "",
) {
    /**
     * Whether this is the same station as [other], for favouriting.
     *
     * ⚠️ Prefers the directory's stable [uuid] and falls back to the stream URL, which is what this
     * used to compare and is the one field a directory entry is free to change. A station that
     * moves its stream would otherwise stop matching its own favourite — the star goes out and
     * starring it again adds a duplicate rather than restoring it. The fallback keeps every
     * favourite saved before this field existed working exactly as it did.
     */
    fun sameStation(other: RadioStation): Boolean =
        if (uuid.isNotBlank() && other.uuid.isNotBlank()) uuid == other.uuid
        else streamUrl == other.streamUrl
}

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
