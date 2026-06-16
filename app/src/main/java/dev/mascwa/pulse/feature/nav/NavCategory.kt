package dev.mascwa.pulse.feature.nav

/**
 * The NAV map's POI filter categories — the cyberpunk "legend". Each maps to an OpenStreetMap tag
 * [filter] (queried via Overpass) and a neon [colorArgb] used for both the legend chip and the map
 * marker. Kept separate from the survival `PlaceCategory` so the two feature sets stay independent.
 */
enum class NavCategory(
    val id: String,
    val label: String,
    val colorArgb: Long,
    val filter: String,
    val radius: Int = 2500,
) {
    FOOD("nav_food", "Food", 0xFFFFC542, "[\"amenity\"~\"^(restaurant|fast_food|cafe|food_court)$\"]"),
    FUEL("nav_fuel", "Fuel", 0xFFFF8A3D, "[\"amenity\"=\"fuel\"]"),
    MEDICAL("nav_medical", "Medical", 0xFF46F9A0, "[\"amenity\"~\"^(pharmacy|hospital|clinic|doctors)$\"]"),
    SHOP("nav_shop", "Shops", 0xFF9B8CFF, "[\"shop\"~\"^(supermarket|convenience|mall|department_store)$\"]"),
    DRINK("nav_drink", "Bars", 0xFFFF3864, "[\"amenity\"~\"^(bar|pub|nightclub|biergarten)$\"]"),
    TRANSIT("nav_transit", "Transit", 0xFF5AD1FF, "[\"public_transport\"=\"station\"]"),
    MONEY("nav_money", "ATM / Bank", 0xFFFCEE0A, "[\"amenity\"~\"^(atm|bank)$\"]"),
    LODGING("nav_lodging", "Lodging", 0xFFB061FF, "[\"tourism\"~\"^(hotel|hostel|motel|guest_house)$\"]"),
    ;

    /** "#RRGGBB" for MapLibre marker colours / legend dots. */
    val colorHex: String get() = "#%06X".format(colorArgb and 0xFFFFFF)
}
