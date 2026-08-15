package dev.mascwa.pulse.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The LCARS colour set — flat black ground, orange primary, and the periwinkle/mauve/salmon/pale-gold
 * secondaries the style is built from.
 *
 * Several of these are the canonical values rather than approximations of them: `#FFCC99` tan,
 * `#CC6666` red, `#CC99CC` lilac and `#9999FF` cornflower are exact. The accent moved to the
 * canonical `#FF9900` — it had been `#FF9C42`, a lighter and slightly pinker orange, which is the
 * one value that read as "inspired by" rather than as the thing itself.
 *
 * Style and interaction-language homage only; no franchise branding assets.
 *
 * Provided over `LocalNightwire` around the whole NavHost, so this is the app's only live palette.
 */
val lcarsPalette = NightwirePalette(
    accent = Color(0xFFFF9900),
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

/**
 * The block colours a rail cycles through.
 *
 * An LCARS frame is a column of stacked blocks in six-or-so distinct hues, and the palette above
 * could not build one: `positive` and `negative` are semantic (a market moving up or down) and must
 * never be borrowed for decoration, which left only about four usable colours. These three complete
 * the set — pale yellow, golden tanoi and butterscotch are all part of the canonical vocabulary and
 * none of them carries meaning elsewhere in the app.
 *
 * Ordered so adjacent blocks contrast. A rail indexes into this by position, so the same screen
 * always draws the same colours — a rail that reshuffled between visits would read as a fault.
 */
val LcarsBlocks: List<Color> = listOf(
    Color(0xFFFF9900), // orange — the primary
    Color(0xFFCC99CC), // lilac
    Color(0xFFFFCC99), // tan
    Color(0xFF9999FF), // cornflower
    Color(0xFFFFCC66), // golden tanoi
    Color(0xFFCC6666), // rust
    Color(0xFFFFFF99), // pale yellow
    Color(0xFFFF9966), // butterscotch
)

/**
 * The alert palette — the ship going to red.
 *
 * Not a user choice: this is driven by the brief's own `BriefUrgency.RED`, so the console changes
 * colour because something is actually wrong. Deliberately keeps the greys and the ink, so text
 * stays as legible as it was; only the accents move. A red alert that made the readouts harder to
 * read would be exactly backwards.
 */
val lcarsRedAlert = lcarsPalette.copy(
    accent = Color(0xFFCC2222),
    magenta = Color(0xFFFF6666),
    amber = Color(0xFFFF9966),
    violet = Color(0xFFCC6699),
    sky = Color(0xFFCC8888),
)

/** Rail blocks under red alert — the same structure, drained to the alert range. */
val LcarsAlertBlocks: List<Color> = listOf(
    Color(0xFFCC2222),
    Color(0xFFFF6666),
    Color(0xFF992222),
    Color(0xFFFF9966),
    Color(0xFFCC4444),
    Color(0xFF661111),
)
