package dev.mascwa.pulse.feature.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The NAV map's POI filter categories — the cyberpunk "legend". Each maps to an OpenStreetMap tag
 * [filter] (queried via Overpass), a neon [colorArgb] used for both the legend chip and the map
 * marker, and an [icon] for the filter bar. Kept separate from the survival `PlaceCategory` so the
 * two feature sets stay independent.
 */
enum class NavCategory(
    val id: String,
    val label: String,
    val colorArgb: Long,
    val icon: ImageVector,
    val filter: String,
    val radius: Int = 2500,
) {
    FOOD("nav_food", "Food", 0xFFFFC542, Icons.Filled.Restaurant, "[\"amenity\"~\"^(restaurant|fast_food|cafe|food_court)$\"]"),
    FUEL("nav_fuel", "Fuel", 0xFFFF8A3D, Icons.Filled.LocalGasStation, "[\"amenity\"=\"fuel\"]"),
    MEDICAL("nav_medical", "Medical", 0xFF46F9A0, Icons.Filled.LocalHospital, "[\"amenity\"~\"^(pharmacy|hospital|clinic|doctors)$\"]"),
    SHOP("nav_shop", "Shops", 0xFF9B8CFF, Icons.Filled.Storefront, "[\"shop\"~\"^(supermarket|convenience|mall|department_store)$\"]"),
    DRINK("nav_drink", "Bars", 0xFFFF3864, Icons.Filled.LocalBar, "[\"amenity\"~\"^(bar|pub|nightclub|biergarten)$\"]"),
    TRANSIT("nav_transit", "Transit", 0xFF5AD1FF, Icons.Filled.DirectionsTransit, "[\"public_transport\"=\"station\"]"),
    MONEY("nav_money", "ATM / Bank", 0xFFFCEE0A, Icons.Filled.AccountBalance, "[\"amenity\"~\"^(atm|bank)$\"]"),
    LODGING("nav_lodging", "Lodging", 0xFFB061FF, Icons.Filled.Hotel, "[\"tourism\"~\"^(hotel|hostel|motel|guest_house)$\"]"),
    ;

    /** "#RRGGBB" for MapLibre marker colours / legend dots. */
    val colorHex: String get() = "#%06X".format(colorArgb and 0xFFFFFF)
}
