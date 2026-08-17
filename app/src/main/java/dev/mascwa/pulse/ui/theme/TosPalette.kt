package dev.mascwa.pulse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The original-series console colour set — black plates carrying banks of vivid backlit buttons.
 *
 * ⚠️ **This replaces the LCARS palette, and the difference is saturation.** LCARS is a 1987 design
 * and its signature is *pastel*: tan, lilac, cornflower, salmon, laid in swept blocks. The 1966
 * bridge is the opposite — matte black plates with saturated, almost primary, backlit colour
 * scattered across them, so the impression is darkness punctuated by vivid light rather than large
 * areas of soft hue. Reproducing that is mostly a matter of pushing the secondaries hard and letting
 * the black dominate; a desaturated version of this set would simply read as LCARS again.
 *
 * Style and interaction-language homage only. No franchise branding, insignia or artwork.
 *
 * Provided over `LocalNightwire` around the whole NavHost, so this is the app's only live palette —
 * which is why swapping it here repaints every screen with no per-screen edit.
 */
val tosPalette = NightwirePalette(
    // Command gold. Deliberately yellower and more saturated than the LCARS orange it replaces
    // (#FF9900), because the two sit close enough that a timid shift would read as a tweak rather
    // than a different console.
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

/**
 * The button bank — the single most recognisable thing about a 1966 console.
 *
 * A rail indexes into this by position, so the same screen always draws the same colours; a rail that
 * reshuffled between visits would read as a fault rather than as decoration. Ordered so adjacent
 * blocks contrast hard, which is how the real banks were laid out — neighbouring buttons had to be
 * told apart at a glance and under red light.
 *
 * ⚠️ [NightwirePalette.positive] and [NightwirePalette.negative] carry meaning elsewhere (a market
 * moving up or down) and must never be borrowed for decoration. The green and red here are the
 * bank's own, chosen distinct from the semantic pair for that reason.
 */
val TosBlocks: List<Color> = listOf(
    Color(0xFFFFB000), // command gold
    Color(0xFF22B8E0), // electric cyan
    Color(0xFFE02020), // signal red
    Color(0xFF24C04A), // scanner green
    Color(0xFFFFC61A), // amber
    Color(0xFF8A5CE0), // violet
    Color(0xFFE0409A), // magenta
    Color(0xFFFF7A1A), // orange
)

/**
 * Red alert — the bridge bathed in red.
 *
 * Not a user choice: driven by the brief's own `BriefUrgency.RED`, so the console changes colour
 * because something is actually wrong. Deliberately keeps the greys and the ink exactly as they are,
 * so text stays as legible as it was and only the accents move. An alert state that made the
 * readouts harder to read would be precisely backwards.
 */
val tosRedAlert = tosPalette.copy(
    accent = Color(0xFFE01414),
    magenta = Color(0xFFFF5555),
    amber = Color(0xFFFF7A3C),
    violet = Color(0xFFC0405C),
    sky = Color(0xFFE06666),
)

/** The button bank under red alert — the same structure, drained into the alert range. */
val TosAlertBlocks: List<Color> = listOf(
    Color(0xFFE01414),
    Color(0xFFFF5555),
    Color(0xFF991010),
    Color(0xFFFF7A3C),
    Color(0xFFC03030),
    Color(0xFF660A0A),
)
