package dev.mascwa.pulse.desktop.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bank of block colours the rail, the segment bars and the button banks draw from.
 *
 * ⚠️ Values copied from the phone's `ui/theme/TosPalette.kt` rather than re-picked. The owner's
 * standing instruction is that the two look the same; a colour chosen twice is a colour that will
 * diverge, and this app has already corrected exactly that mistake four times (the accent hue drifted
 * into the launcher icon, two notification colours and this very module).
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
 * Deliberately keeps the greys and the ink exactly as they are, so text stays as legible as it was
 * and only the accents move. An alert state that made the readouts harder to read would be precisely
 * backwards.
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

/**
 * The block bank in force right now.
 *
 * ⚠️ Its own composition local rather than a field on the palette, because it is a LIST — the palette
 * is a data class of single colours and adding a collection to it would make every `copy()` in the
 * theme carry one. On the phone this exact separation is what let the alert state reach the rail
 * without any screen knowing alerts exist.
 */
val LocalConsoleBlocks = compositionLocalOf { TosBlocks }

/**
 * Where in the app you are, as one short word for the header.
 *
 * Read from a composition local rather than passed down, so every framed screen gains the readout
 * without any of them being edited — the trick that gave the phone's thirty-five screens a location
 * line in one commit.
 */
val LocalConsoleSection = compositionLocalOf { "" }

/** The condition the whole console is in. Mirrors the phone's `AlertCondition`. */
enum class AlertCondition { ROUTINE, YELLOW, RED }

/**
 * The ship's condition, app-wide.
 *
 * A plain object with flows rather than anything injected, for the same reason the phone's is: the
 * alert has to be settable from wherever the news arrives and readable from the frame, which sits
 * above every screen — threading it through would mean touching every screen to carry a value almost
 * all of them ignore.
 *
 * ⚠️ Set on EVERY evaluation, not only when something is wrong. A condition that is only ever raised
 * never stands down, and a console stuck in red is a console nobody looks at.
 */
object AlertStatus {
    private val _condition = MutableStateFlow(AlertCondition.ROUTINE)
    val condition: StateFlow<AlertCondition> = _condition.asStateFlow()

    private val _headline = MutableStateFlow("")

    /** What raised it, in a few words. Blank is legitimate — a condition with no story still shows. */
    val headline: StateFlow<String> = _headline.asStateFlow()

    fun set(condition: AlertCondition, headline: String = "") {
        _condition.value = condition
        _headline.value = headline
    }
}
