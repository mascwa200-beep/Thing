package dev.mascwa.pulse.data.safety

import dev.mascwa.pulse.core.telemetry.SafetyCoverage
import kotlinx.serialization.Serializable

enum class IncidentType(val label: String) {
    EARTHQUAKE("Earthquake"),
    WEATHER("Weather"),
    DISASTER("Disaster"),
    FIRE("Wildfire"),
    CRIME("Crime"),
    OTHER("Alert"),
}

enum class Severity { LOW, MODERATE, HIGH, EXTREME }

@Serializable
data class Incident(
    val id: String,
    val type: String,             // IncidentType name
    val title: String,
    val severity: String,         // Severity name
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val bearing: Double,
    val timeEpochMs: Long,
    val source: String,
    val url: String? = null,
    val magnitude: Double? = null,
    /**
     * The rest of what USGS sends about an earthquake, all of which used to be parsed away.
     *
     * Every field is defaulted so a `SafetyResult` cached before they existed still decodes — the
     * same reason `sourceStates` above is defaulted. A blob without them grades exactly as it did,
     * because `Seismic.alertLevel` treats absent as no information rather than as safety.
     *
     * Only earthquakes populate these; the other three sources have no equivalent.
     */
    val depthKm: Double? = null,
    /**
     * USGS's tsunami flag. Set when the event is somewhere a tsunami evaluation is warranted, so
     * it marks an assessment in progress rather than a warning in force — but it is the single
     * most consequential field in the feed and this app discarded it.
     */
    val tsunami: Boolean = false,
    /** PAGER humanitarian-impact estimate: green, yellow, orange, red. Absent on most events. */
    val pagerAlert: String? = null,
    /** USGS's own significance score, folding in magnitude, felt reports and estimated impact. */
    val significance: Int? = null,
    /** Which magnitude scale was used. `mb` saturates around 6.5 and understates larger events. */
    val magType: String? = null,
    /** How many people filed a "did you feel it" report. */
    val feltReports: Int? = null,
    /** Instrumental shaking intensity, Modified Mercalli. */
    val shakingIntensity: Double? = null,
    /**
     * What the issuing authority says to actually do. Weather alerts carry it on nearly every
     * message and it was parsed away, which is a strange thing for a safety feature to discard.
     */
    val instruction: String? = null,
    /** "HAPPENING NOW" / "LIKELY SOON" / "FORECAST" — from CAP urgency and certainty. */
    val timing: String? = null,
    /** When the issuer says this stops applying, so a cached alert is not shown as current. */
    val expiresEpochMs: Long? = null,
    /** The counties or zones the alert covers, as the issuer describes them. */
    val areaDescription: String? = null,
)

@Serializable
data class SafetyResult(
    val originLat: Double,
    val originLon: Double,
    val incidents: List<Incident>,
    /**
     * What each source did on this fetch, keyed by `SafetyCoverage.Source` name.
     *
     * Defaulted so results cached before this field existed still decode. Stored as strings because
     * `core:telemetry` deliberately carries no serialization dependency; converted at the boundary.
     *
     * Without this the screen could not tell three different situations apart — the source looked
     * and found nothing, the source does not operate here, and the source failed — and it asserted
     * the first one in all three cases.
     */
    val sourceStates: Map<String, String> = emptyMap(),
) {
    /**
     * [sourceStates] back as the typed map the core reasons over.
     *
     * Names that no longer resolve are dropped rather than guessed at — a result cached before a
     * source was renamed should lose that one entry, not poison the whole reading.
     */
    fun coverage(): Map<SafetyCoverage.Source, SafetyCoverage.Availability> =
        sourceStates.mapNotNull { (source, state) ->
            val s = SafetyCoverage.Source.entries.firstOrNull { it.name == source }
            val a = SafetyCoverage.Availability.entries.firstOrNull { it.name == state }
            if (s != null && a != null) s to a else null
        }.toMap()
}
