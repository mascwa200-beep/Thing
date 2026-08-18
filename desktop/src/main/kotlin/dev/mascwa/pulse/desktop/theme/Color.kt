package dev.mascwa.pulse.desktop.theme

import androidx.compose.ui.graphics.Color

/** Ported from the Android app's `ui/theme/Color.kt` — trimmed to just the palette shape itself, since
 *  there's no accent-picker/AMOLED-toggle machinery to replicate here: the console is the desktop app's one
 *  fixed identity too, same as the Android app's own post-overhaul direction. */
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

/**
 * The original-series console palette — ported value-for-value from the Android app's
 * `ui/theme/TosPalette.kt`, so the two consoles cannot drift apart.
 *
 * ⚠️ Replaces the LCARS set, and the difference is saturation more than hue: LCARS is a 1987 design
 * whose signature is pastel laid in swept blocks, while the 1966 bridge is black plates carrying
 * vivid, nearly primary backlit colour. Homage only — no franchise branding assets.
 */
val tosPalette = NightwirePalette(
    accent = Color(0xFFFFB000),
    void = Color(0xFF000000),
    carbon = Color(0xFF08080A),
    panel = Color(0xFF121216),
    raise = Color(0xFF1B1B21),
    line = Color(0xFF3C3C44),
    lineSoft = Color(0xFF24242B),
    ink = Color(0xFFF4F4F6),
    ink2 = Color(0xFFFFFFFF),
    muted = Color(0xFFACACB6),
    faint = Color(0xFF63636E),
    magenta = Color(0xFFE0409A),
    amber = Color(0xFFFFC61A),
    positive = Color(0xFF24C04A),
    negative = Color(0xFFE02020),
    violet = Color(0xFF8A5CE0),
    sky = Color(0xFF22B8E0),
)
