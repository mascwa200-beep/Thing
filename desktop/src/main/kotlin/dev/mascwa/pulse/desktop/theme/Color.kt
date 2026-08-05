package dev.mascwa.pulse.desktop.theme

import androidx.compose.ui.graphics.Color

/** Ported from the Android app's `ui/theme/Color.kt` — trimmed to just the palette shape itself, since
 *  there's no accent-picker/AMOLED-toggle machinery to replicate here: LCARS is the desktop app's one fixed
 *  identity too, same as the Android app's own post-overhaul direction. */
data class NightwirePalette(
    val accent: Color,
    val void: Color,
    val carbon: Color,
    val panel: Color,
    val raise: Color,
    val line: Color,
    val lineSoft: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val faint: Color,
    val magenta: Color,
    val amber: Color,
    val positive: Color,
    val negative: Color,
    val violet: Color,
    val sky: Color,
) {
    /** Colour for a market/percent value. */
    fun trend(positive: Boolean): Color = if (positive) this.positive else this.negative
}

/** Ported verbatim (same values) from the Android app's `ui/theme/LcarsPalette.kt`. Style/interaction-
 *  language homage only — no franchise branding assets. */
val lcarsPalette = NightwirePalette(
    accent = Color(0xFFFF9C42),
    void = Color(0xFF000000),
    carbon = Color(0xFF0A0A0C),
    panel = Color(0xFF121214),
    raise = Color(0xFF1C1C1F),
    line = Color(0xFF3A3A3D),
    lineSoft = Color(0xFF232326),
    ink = Color(0xFFF5F5F5),
    ink2 = Color(0xFFFFFFFF),
    muted = Color(0xFFB8B8BD),
    faint = Color(0xFF6E6E73),
    magenta = Color(0xFFFF9999),
    amber = Color(0xFFFFCC99),
    positive = Color(0xFF99CC99),
    negative = Color(0xFFCC6666),
    violet = Color(0xFFCC99CC),
    sky = Color(0xFF9999FF),
)
