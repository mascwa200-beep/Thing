package dev.mascwa.pulse.data.radar

import kotlinx.serialization.Serializable

/** What a radar contact represents. Drives its colour + icon on the scope. */
enum class ContactKind { AIRCRAFT, ISS, QUAKE }

/**
 * A single tracked object on the TACNET scope. Position is real (live ADS-B,
 * the ISS, or a USGS quake epicentre); [distanceMeters]/[bearingDeg] are
 * recomputed from the live GPS origin on every read so the plot stays correct
 * even when served from cache offline.
 */
@Serializable
data class Contact(
    val id: String,
    val label: String,            // callsign / "ISS" / "M4.2"
    val latitude: Double,
    val longitude: Double,
    val altitudeM: Double? = null,
    val groundSpeedKmh: Double? = null,
    val trackDeg: Double? = null, // heading 0..360 (aircraft only)
    val detail: String = "",      // registration · type / place
    val kind: String = ContactKind.AIRCRAFT.name,
    val squawk: String? = null,
    val verticalRateFpm: Int? = null,
    val category: String? = null, // ADS-B emitter category (e.g. A3)
    val military: Boolean = false,
    val emergency: Boolean = false, // squawk 7500/7600/7700
    val distanceMeters: Double = 0.0,
    val bearingDeg: Double = 0.0,
)

@Serializable
data class RadarData(
    val originLat: Double,
    val originLon: Double,
    val contacts: List<Contact> = emptyList(),
    val source: String = "",      // which feed answered (adsb.lol / adsb.fi)
    val updatedEpochMs: Long = System.currentTimeMillis(),
) {
    fun aircraftCount(): Int = contacts.count { it.kind == ContactKind.AIRCRAFT.name }
}
