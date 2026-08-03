package dev.mascwa.pulse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole NIGHTWIRE palette remapped to an LCARS (Library Computer Access/Retrieval System) look —
 * flat black surfaces with bold orange as the primary accent and periwinkle/mauve/salmon/pale-gold as
 * the secondary accents. Provided over [LocalNightwire] across the entire TOOLS feed section (see
 * PulseApp) so every feed screen re-themes with no per-screen edits. Style/interaction-language homage
 * only — no franchise branding assets, same boundary the earlier Pip-Boy skin held to.
 */
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
