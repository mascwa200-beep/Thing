package dev.mascwa.pulse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The whole NIGHTWIRE palette remapped to Fallout Pip-Boy phosphor green. Provided over
 * [LocalNightwire] across the entire TOOLS feed section (see PulseApp) so every feed screen —
 * PIP-BOY, NAV, OBJECTIVES, SURVIVE, SOCIAL, SEARCH — re-themes to the monochrome CRT look with no
 * per-screen edits. Warm accents (magenta/amber/negative) fall back to the off-green alert so
 * emphasis still reads on a single-colour tube. Mirrors the `Pip` greens used directly by RADSCOPE.
 */
val pipBoyPalette = NightwirePalette(
    accent = Color(0xFF3FCB74),
    void = Color(0xFF04130A),
    carbon = Color(0xFF061A0D),
    panel = Color(0xFF08220F),
    raise = Color(0xFF0C2A15),
    line = Color(0xFF15462A),
    lineSoft = Color(0xFF0C2415),
    ink = Color(0xFF9CFFC4),
    ink2 = Color(0xFF5BFF9B),
    muted = Color(0xFF2E8F52),
    faint = Color(0xFF1C5733),
    magenta = Color(0xFFE6FF66),
    amber = Color(0xFFE6FF66),
    positive = Color(0xFF5BFF9B),
    negative = Color(0xFFE6FF66),
    violet = Color(0xFF3FCB74),
    sky = Color(0xFF5BFF9B),
)
