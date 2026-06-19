package dev.mascwa.pulse.feature.tacnet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import dev.mascwa.pulse.ui.theme.NightwirePalette

/**
 * Fallout Pip-Boy phosphor-green palette shared across the TACNET / PIP-BOY screens — a monochrome
 * CRT look. Contact types and readouts are told apart by glyph + brightness rather than hue
 * (emergencies get the one off-green accent). Internal to the tacnet package.
 */
internal object Pip {
    val bg = Color(0xFF04130A)        // deep CRT green-black
    val gridSoft = Color(0xFF0C2415)  // faint grid
    val grid = Color(0xFF15462A)      // range rings / ticks
    val dim = Color(0xFF2E8F52)       // secondary text
    val mid = Color(0xFF3FCB74)       // standard contact
    val bright = Color(0xFF5BFF9B)    // sweep / primary
    val glow = Color(0xFF9CFFC4)      // brightest highlight (ISS/selected text)
    val alert = Color(0xFFE6FF66)     // emergency — off-green amber, still in-palette
}

/**
 * The whole NIGHTWIRE palette remapped to phosphor green. Provided over `LocalNightwire` around the
 * PIP-BOY bodies so every NeonPanel / SectionBar / StatTile / Text inside re-themes to the Pip-Boy
 * CRT look with no per-screen edits. Everything is one of the [Pip] greens (the off-green [Pip.alert]
 * stands in for the warm accents so emphasis still reads on a monochrome tube).
 */
internal val PipPalette = NightwirePalette(
    accent = Pip.mid,
    void = Pip.bg,
    carbon = Color(0xFF061A0D),
    panel = Color(0xFF08220F),
    raise = Color(0xFF0C2A15),
    line = Pip.grid,
    lineSoft = Pip.gridSoft,
    ink = Pip.glow,
    ink2 = Pip.bright,
    muted = Pip.dim,
    faint = Color(0xFF1C5733),
    magenta = Pip.alert,
    amber = Pip.alert,
    positive = Pip.bright,
    negative = Pip.alert,
    violet = Pip.mid,
    sky = Pip.bright,
)

/** Tiled dark horizontal lines over a surface for a CRT scanline texture. */
internal fun DrawScope.crtScanlines(color: Color, gap: Float = 3f) {
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
        y += gap
    }
}
