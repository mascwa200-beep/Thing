package dev.mascwa.pulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.mascwa.pulse.data.settings.AccentColor

/** Access the NIGHTWIRE palette anywhere: `Pulse.colors.accent`. */
val LocalNightwire = staticCompositionLocalOf {
    nightwirePalette(accentColorOf(AccentColor.CYAN), amoled = false)
}

object Pulse {
    val colors: NightwirePalette
        @Composable get() = LocalNightwire.current
}

/**
 * [accent]/[amoledBlack] are vestigial — LCARS became the app's one fixed palette (see `PulseApp.kt`'s
 * unconditional `LocalNightwire provides lcarsPalette`), so both the composition-local default here and the
 * Material3 [darkColorScheme] below are built from [lcarsPalette] directly rather than the old
 * accent-configurable [nightwirePalette]. Kept as parameters (not removed) so this stays a pure internal
 * simplification — no call-site or Settings-screen change needed. Before this, the Material3 `scheme` was
 * the ONE place still deriving from the old palette: any un-migrated default Material component (a system
 * dialog, an un-overridden `TextField`/`ScrollableTabRow` default) would have picked up the wrong colours.
 */
@Composable
fun NightwireTheme(
    accent: AccentColor,
    amoledBlack: Boolean,
    content: @Composable () -> Unit,
) {
    val palette = lcarsPalette

    val scheme = darkColorScheme(
        primary = palette.accent,
        onPrimary = palette.void,
        primaryContainer = palette.accent.copy(alpha = 0.16f),
        onPrimaryContainer = palette.accent,
        secondary = palette.magenta,
        onSecondary = palette.void,
        tertiary = palette.amber,
        background = palette.void,
        onBackground = palette.ink,
        surface = palette.panel,
        onSurface = palette.ink,
        surfaceVariant = palette.raise,
        onSurfaceVariant = palette.ink2,
        outline = palette.line,
        outlineVariant = palette.lineSoft,
        error = palette.negative,
        scrim = Color(0xCC02030A),
    )

    CompositionLocalProvider(LocalNightwire provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = NightwireTypography,
            content = content,
        )
    }
}

/** Semantic up/down colour. */
@Composable
fun trendColor(positive: Boolean): Color = LocalNightwire.current.trend(positive)
