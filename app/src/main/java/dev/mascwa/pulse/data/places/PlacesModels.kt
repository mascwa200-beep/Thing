package dev.mascwa.pulse.data.places

import kotlinx.serialization.Serializable

/** Nearest-place categories backed by OpenStreetMap (Overpass) tag filters. */
enum class PlaceCategory(
    val title: String,
    val overpassFilter: String,
    val radiusMeters: Int,
) {
    HOSPITAL("Hospitals", "[\"amenity\"~\"^(hospital|clinic|doctors)$\"]", 15000),
    SHELTER("Shelters", "[\"amenity\"=\"shelter\"]", 25000),
    FOOD_BANK("Food banks", "[\"social_facility\"=\"food_bank\"]", 25000),
    HOMELESS("Homeless shelters", "[\"social_facility\"=\"shelter\"]", 25000),
    COMM_TOWER("Comm towers", "[\"man_made\"~\"^(communications_tower|mast|tower)$\"]", 12000),
}

@Serializable
data class Place(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val bearing: Double,
    val phone: String? = null,
    val address: String? = null,
)

@Serializable
data class PlacesResult(
    val category: String,
    val originLat: Double,
    val originLon: Double,
    val places: List<Place>,
)
