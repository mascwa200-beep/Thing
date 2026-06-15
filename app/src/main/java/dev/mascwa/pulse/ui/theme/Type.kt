package dev.mascwa.pulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.R

/* Three bundled families that define the NIGHTWIRE look. */

val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_regular, FontWeight.Normal),
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

private fun variable(resId: Int, weight: FontWeight, w: Int) =
    Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val SpaceGrotesk = FontFamily(
    variable(R.font.space_grotesk_var, FontWeight.Normal, 400),
    variable(R.font.space_grotesk_var, FontWeight.Medium, 500),
    variable(R.font.space_grotesk_var, FontWeight.SemiBold, 600),
    variable(R.font.space_grotesk_var, FontWeight.Bold, 700),
)

val JetBrainsMono = FontFamily(
    variable(R.font.jetbrains_mono_var, FontWeight.Normal, 400),
    variable(R.font.jetbrains_mono_var, FontWeight.Medium, 500),
    variable(R.font.jetbrains_mono_var, FontWeight.SemiBold, 600),
    variable(R.font.jetbrains_mono_var, FontWeight.Bold, 700),
)

/** Material typography mapped onto Space Grotesk (body) + Chakra Petch (display). */
val NightwireTypography = Typography(
    displayLarge = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 36.sp),
    displaySmall = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    headlineLarge = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 26.sp),
    headlineMedium = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    headlineSmall = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleLarge = TextStyle(fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 9.sp, letterSpacing = 0.8.sp),
)
