package dev.mascwa.pulse.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Branded fallback palette (used when Material You dynamic colour is disabled).
private val Blue80 = Color(0xFFADC6FF)
private val Blue40 = Color(0xFF3D5BF6)
private val Blue20 = Color(0xFF00208E)
private val Cyan80 = Color(0xFF82D5E6)
private val Cyan40 = Color(0xFF00687C)

val PulseLightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBE1FF),
    onPrimaryContainer = Color(0xFF001257),
    secondary = Cyan40,
    onSecondary = Color.White,
    tertiary = Color(0xFF6B5778),
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    error = Color(0xFFBA1A1A),
)

val PulseDarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = Blue20,
    primaryContainer = Color(0xFF1E429E),
    onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = Cyan80,
    onSecondary = Color(0xFF003543),
    tertiary = Color(0xFFD6BEE4),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    error = Color(0xFFFFB4AB),
)

// Semantic colours for finance up/down used across markets/economy/fuel.
val PositiveGreen = Color(0xFF1B8E5A)
val PositiveGreenDark = Color(0xFF5BD99B)
val NegativeRed = Color(0xFFC5283D)
val NegativeRedDark = Color(0xFFFF8A8A)
