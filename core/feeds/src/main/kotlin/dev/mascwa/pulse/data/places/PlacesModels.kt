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
    /**
     * What kind of place it is — the OSM `amenity`, or `healthcare` where that is the tag carrying
     * it. Present on effectively every result and discarded until now, which is why a GP surgery
     * and a major hospital used to render identically.
     *
     * Every field below is defaulted so a result cached before they existed still decodes.
     */
    val kind: String? = null,
    /** OSM `emergency` — whether a hospital actually has an A&E department. */
    val emergency: String? = null,
    /** OSM `opening_hours`, verbatim. Not parsed here; shown so a closed clinic can say so. */
    val openingHours: String? = null,
    /**
     * A fifth of results carry a website but no phone, so this is the only way to reach them.
     *
     * Always carries a scheme — OSM values are often bare hosts, and `ACTION_VIEW` on one of those
     * resolves to nothing at all.
     */
    val website: String? = null,
    /** OSM `email`. Rare (about one result in thirty) and the only channel on some of those. */
    val email: String? = null,
    /**
     * OSM `healthcare:speciality`, verbatim.
     *
     * A quarter of results declare one, and about seventy per cent of those are narrow practices —
     * podiatry, dermatology, fertility — which the row rendered identically to a general surgery.
     */
    val speciality: String? = null,
)

@Serializable
data class PlacesResult(
    val category: String,
    val places: List<Place>,
    /**
     * Whether the server's quota bound, so this list is an arbitrary slice rather than the nearest.
     *
     * Should be false in practice — the search narrows its radius until the quota stops binding —
     * but if it ever is true the screen must say so instead of presenting the rows as "nearest".
     */
    val truncated: Boolean = false,
    /** The radius the answer actually came from, which is not the category's maximum. */
    val searchRadiusMeters: Int = 0,
)
