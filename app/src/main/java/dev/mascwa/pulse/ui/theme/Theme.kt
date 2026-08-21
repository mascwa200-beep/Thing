package dev.mascwa.pulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.mascwa.pulse.data.settings.AccentColor
import dev.mascwa.pulse.notifications.AlertCondition
import dev.mascwa.pulse.notifications.AlertStatus

/**
 * Access the console palette anywhere: `Pulse.colors.accent`.
 *
 * ⚠️ The default is [tosPalette], which is what the KDoc on [NightwireTheme] below has claimed all
 * along — it said "both the composition-local default here and the Material3 scheme are built from
 * tosPalette directly", and only the second half was true. `PulseApp` provides the real palette
 * unconditionally, so the stale default was unreachable from any screen; the one place that DID
 * reach it was the widget config activity, which never entered that provider and so rendered in a
 * cyberpunk cyan two palettes out of date.
 */
val LocalNightwire = staticCompositionLocalOf { tosPalette }

/**
 * The rail block colours in force.
 *
 * Not `static`, unlike the palette: this one genuinely changes at runtime when the ship goes to red,
 * and a static local does not invalidate its readers.
 */
val LocalConsoleBlocks = compositionLocalOf { TosBlocks }

object Pulse {
    val colors: NightwirePalette
        @Composable get() = LocalNightwire.current
}

/**
 * [accent]/[amoledBlack] are vestigial — LCARS became the app's one fixed palette (see `PulseApp.kt`'s
 * unconditional `LocalNightwire provides tosPalette`), so both the composition-local default here and the
 * Material3 [darkColorScheme] below are built from [tosPalette] directly rather than the old
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
    // The ship's condition, set by BriefEngine when it publishes the board. Only RED moves the
    // palette: yellow means pay attention, and recolouring the whole console for it would spend the
    // signal long before anything is actually wrong.
    val condition by AlertStatus.condition.collectAsState()
    val alert = condition == AlertCondition.RED
    val palette = if (alert) tosRedAlert else tosPalette

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

    CompositionLocalProvider(
        LocalNightwire provides palette,
        // The rail blocks are not in the palette (they are a list, and the palette is a fixed set of
        // roles), so they travel separately. Providing them here means the rails and segment bars go
        // red with everything else and no screen has to know.
        LocalConsoleBlocks provides if (alert) TosAlertBlocks else TosBlocks,
    ) {
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
