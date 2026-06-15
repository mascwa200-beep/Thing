package dev.mascwa.pulse.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.mascwa.pulse.data.settings.ThemeMode

@Composable
fun PulseTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> PulseDarkColors
        else -> PulseLightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = PulseTypography,
        content = content,
    )
}

/** Semantic up/down colour aware of light/dark. */
@Composable
fun trendColor(positive: Boolean): Color {
    val dark = MaterialTheme.colorScheme.background.approxLuminance() < 0.5f
    return when {
        positive && dark -> PositiveGreenDark
        positive -> PositiveGreen
        dark -> NegativeRedDark
        else -> NegativeRed
    }
}

private fun Color.approxLuminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
