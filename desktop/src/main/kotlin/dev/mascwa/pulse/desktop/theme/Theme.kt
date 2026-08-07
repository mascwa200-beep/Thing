package dev.mascwa.pulse.desktop.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Access the palette anywhere: `Pulse.colors.accent` — the same access pattern as the Android app's own
 *  `Pulse` object, so files ported from the Android app's `feature/common` package that reference
 *  `Pulse.colors` need no internal edits beyond their import line. */
val LocalNightwire = staticCompositionLocalOf { lcarsPalette }

object Pulse {
    val colors: NightwirePalette
        @Composable get() = LocalNightwire.current
}

/** The desktop shell's one theme — mirrors the Android app's `NightwireTheme`, minus the accent-picker/
 *  AMOLED parameters that theme keeps only for Android call-site stability (this module has no such
 *  call sites to stay compatible with). */
@Composable
fun PulseDesktopTheme(content: @Composable () -> Unit) {
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
    )
    CompositionLocalProvider(LocalNightwire provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
