package dev.mascwa.pulse.data.radar

import kotlinx.serialization.Serializable

/** What a radar contact represents. Drives its colour + icon on the scope. */
enum class ContactKind { AIRCRAFT, ISS, QUAKE }

/**
 * A single tracked object on the scope. Position is real (live ADS-B, the ISS, or a USGS quake
 * epicentre); [distanceMeters]/[bearingDeg] are recomputed from the live GPS origin on every read
 * so the plot stays correct even when served from cache offline.
 *
 * The feed returns about forty fields per aircraft. Most of them were being downloaded and thrown
 * away, so everything below the position block exists because it was already arriving — it costs
 * nothing extra to carry and answers questions the bare position cannot: what the aircraft is about
 * to do, how trustworthy the fix is, and whether it is declaring an emergency the squawk does not
 * show.
 *
 * Every field added here is defaulted, so a cached blob written by an older build still decodes.
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
    val emergency: Boolean = false,
    val distanceMeters: Double = 0.0,
    val bearingDeg: Double = 0.0,

    // ---- identity, straight off the wire ----
    val registration: String? = null,
    /** ICAO type designator, e.g. B738. */
    val typeCode: String? = null,
    /** Plain-language aircraft description — adsb.fi serves this, adsb.lol does not. */
    val description: String? = null,
    /** Registered owner or operator — again adsb.fi only. */
    val operator: String? = null,

    // ---- state the position alone cannot show ----
    /** GPS altitude, which differs from the barometric figure by the local pressure error. */
    val altitudeGeomM: Double? = null,
    /** Vertical rate from GPS rather than the barometer. */
    val verticalRateGeomFpm: Int? = null,
    /** Where the nose points, as opposed to where the aircraft is actually going over the ground. */
    val trueHeadingDeg: Double? = null,
    val onGround: Boolean = false,

    // ---- intent: what it is about to do, not what it is doing ----
    /** Altitude dialled into the autopilot, feet. */
    val selectedAltitudeFt: Int? = null,
    /** Heading dialled into the autopilot. */
    val selectedHeadingDeg: Double? = null,
    /** Altimeter setting the crew has set, hPa. */
    val qnhHpa: Double? = null,
    /** Engaged autopilot modes, e.g. autopilot, althold, lnav, tcas. */
    val navModes: List<String> = emptyList(),

    // ---- how much to trust the plot ----
    /** Signal strength in dBFS; less negative is stronger. */
    val rssiDb: Double? = null,
    val messageCount: Int? = null,
    /** Seconds since any message. */
    val seenSec: Double? = null,
    /** Seconds since the last *position*, which is what actually goes stale on the scope. */
    val seenPosSec: Double? = null,
    /** adsb_icao, mlat, tisb, adsc — a multilaterated position is an estimate, not a report. */
    val sourceType: String? = null,

    // ---- catalogue flags (dbFlags bits) ----
    val interesting: Boolean = false,
    /** Privacy ICAO Address — the hex is deliberately rotated, so identity is not stable. */
    val pia: Boolean = false,
    /** Limiting Aircraft Data Displayed — the owner asked not to be tracked publicly. */
    val ladd: Boolean = false,

    /** The feed's own emergency word: none, general, lifeguard, minfuel, nordo, unlawful, downed. */
    val emergencyText: String? = null,
    val alert: Boolean = false,
    /** Ident pressed — the crew is identifying themselves to a controller right now. */
    val ident: Boolean = false,

    // ---- seismic events ----
    val magnitude: Double? = null,
    /**
     * Depth below the surface, kilometres.
     *
     * Not a property in the USGS feed — it is the third element of the geometry coordinates, which
     * is easy to miss and easy to get silently wrong. It matters enormously: a shallow magnitude 5
     * does more damage than a deep magnitude 6.
     */
    val depthKm: Double? = null,
    /** Which magnitude scale was used: mww, mb, ml, ms, md. They are not interchangeable. */
    val magType: String? = null,
    /** USGS PAGER humanitarian-impact estimate: green, yellow, orange, red. */
    val pagerAlert: String? = null,
    val tsunami: Boolean = false,
    /** How many people filed a "did you feel it" report. */
    val feltReports: Int? = null,
    /** Community-reported intensity from those reports. */
    val communityIntensity: Double? = null,
    /** Instrumental shaking intensity, Modified Mercalli. */
    val shakingIntensity: Double? = null,
    /** USGS's own significance score, which folds in magnitude, felt reports and impact. */
    val significance: Int? = null,
    /** "reviewed" once a seismologist has checked it; "automatic" solutions can be revised. */
    val reviewStatus: String? = null,
    val eventTimeMs: Long? = null,
    val infoUrl: String? = null,
) {
    /** True when this position was estimated rather than reported by the aircraft itself. */
    val positionIsEstimated: Boolean
        get() = sourceType == "mlat" || sourceType == "tisb" || sourceType == "adsb_other"

    /** No position update for a while: the symbol is coasting on its last known point. */
    val coasting: Boolean get() = (seenPosSec ?: 0.0) > COASTING_AFTER_SEC

    /**
     * The emergency word when the aircraft is actually declaring one.
     *
     * Squawk 7500/7600/7700 covers hijack, radio failure and general emergency, but a medical
     * flight or a fuel emergency is broadcast in this field and nothing else — so before this was
     * read, those were invisible.
     */
    val declaredEmergency: String?
        get() = emergencyText?.lowercase()?.takeIf { it.isNotBlank() && it != "none" }

    /** Plain-English emitter category. The raw A0..C7 code means nothing to a reader. */
    val categoryLabel: String?
        get() = when (category?.uppercase()) {
            "A0" -> "Light aircraft"
            "A1" -> "Light aircraft"
            "A2" -> "Small aircraft"
            "A3" -> "Large aircraft"
            "A4" -> "High-vortex large"
            "A5" -> "Heavy aircraft"
            "A6" -> "High performance"
            "A7" -> "Rotorcraft"
            "B1" -> "Glider"
            "B2" -> "Balloon or airship"
            "B3" -> "Parachutist"
            "B4" -> "Ultralight"
            "B6" -> "Drone"
            "B7" -> "Spacecraft"
            "C1", "C2" -> "Surface vehicle"
            "C3" -> "Obstruction"
            else -> null
        }

    /** Everything worth saying about identity, in one line. */
    val identityLine: String
        get() = listOfNotNull(
            registration,
            typeCode,
            description?.takeIf { it != typeCode },
            operator,
        ).distinct().joinToString(" · ")

    private companion object {
        const val COASTING_AFTER_SEC = 60.0
    }
}

@Serializable
data class RadarData(
    val contacts: List<Contact> = emptyList(),
    val source: String = "",      // which feed answered (adsb.lol / adsb.fi)
    val updatedEpochMs: Long = System.currentTimeMillis(),
) {
    fun aircraftCount(): Int = contacts.count { it.kind == ContactKind.AIRCRAFT.name }

    /** Aircraft declaring an emergency, by squawk or by the feed's own word. */
    fun emergencies(): List<Contact> =
        contacts.filter { it.emergency || it.declaredEmergency != null }

    /** Contacts whose position is a multilateration estimate rather than a self-report. */
    fun estimatedCount(): Int = contacts.count { it.positionIsEstimated }
}
