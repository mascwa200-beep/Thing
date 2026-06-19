package dev.mascwa.pulse.data.objectives

import kotlinx.serialization.Serializable

/**
 * Objective tiers — colour-coded on the map and in the quest tracker:
 *  - MAIN = **gold** — a main objective (you classify it as main),
 *  - SIDE = **white** — a side location you placed yourself,
 *  - WORK = **green** — from work / your calendar.
 */
enum class ObjectiveKind(val colorArgb: Long) {
    MAIN(0xFFFFC542),
    SIDE(0xFFEDF2FA),
    WORK(0xFF5BFF9B),
    ;

    val colorHex: String get() = "#%06X".format(colorArgb and 0xFFFFFF)
}

/** Where an objective originated. */
enum class ObjectiveSource { CALENDAR, MANUAL }

/** A user-saved destination, persisted in settings JSON (so no Room dependency in :app). */
@Serializable
data class Waypoint(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val kind: ObjectiveKind = ObjectiveKind.SIDE,
    val note: String? = null,
)

/** A unified objective shown in the tracker — from the device calendar or a manual waypoint. */
data class Objective(
    val id: String,
    val title: String,
    val kind: ObjectiveKind,
    val latitude: Double,
    val longitude: Double,
    val source: ObjectiveSource,
    val whenLabel: String? = null,
    val distanceMeters: Double? = null,
)
