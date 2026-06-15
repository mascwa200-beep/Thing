package dev.mascwa.pulse.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.mascwa.pulse.data.settings.AccentColor

/* ============================================================
   NIGHTWIRE palette — cyberpunk news/markets/world terminal
   ============================================================ */

// Surfaces
val Void = Color(0xFF05070D)
val Carbon = Color(0xFF0A0D15)
val Panel = Color(0xFF0E121C)
val Raise = Color(0xFF141A28)
val Line = Color(0xFF1E2636)
val LineSoft = Color(0xFF161D2B)
val TrueBlack = Color(0xFF000000)

// Fixed semantic neon
val Magenta = Color(0xFFFF3864)
val Amber = Color(0xFFFFC542)
val Positive = Color(0xFF46F9A0)
val Negative = Color(0xFFFF4D6D)
val Violet = Color(0xFF9B8CFF)
val SkyBlue = Color(0xFF5AD1FF)

// Text
val Ink = Color(0xFFE6EFFA)
val Ink2 = Color(0xFFA9B8CE)
val Muted = Color(0xFF5E708C)
val Faint = Color(0xFF36435A)

fun accentColorOf(a: AccentColor): Color = Color(a.argb)

/** Full NIGHTWIRE colour set exposed to composables via [LocalNightwire]. */
@Immutable
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

fun nightwirePalette(accent: Color, amoled: Boolean) = NightwirePalette(
    accent = accent,
    void = if (amoled) TrueBlack else Void,
    carbon = if (amoled) TrueBlack else Carbon,
    panel = if (amoled) Color(0xFF07090F) else Panel,
    raise = Raise,
    line = Line,
    lineSoft = LineSoft,
    ink = Ink,
    ink2 = Ink2,
    muted = Muted,
    faint = Faint,
    magenta = Magenta,
    amber = Amber,
    positive = Positive,
    negative = Negative,
    violet = Violet,
    sky = SkyBlue,
)
