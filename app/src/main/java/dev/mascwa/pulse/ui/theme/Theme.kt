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

@Composable
fun NightwireTheme(
    accent: AccentColor,
    amoledBlack: Boolean,
    content: @Composable () -> Unit,
) {
    val accentColor = accentColorOf(accent)
    val palette = nightwirePalette(accentColor, amoledBlack)

    val scheme = darkColorScheme(
        primary = accentColor,
        onPrimary = Color(0xFF04121A),
        primaryContainer = accentColor.copy(alpha = 0.16f),
        onPrimaryContainer = accentColor,
        secondary = palette.magenta,
        onSecondary = Color(0xFF1A0207),
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
