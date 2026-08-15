package dev.mascwa.pulse.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.R

/** One weight instance of a variable font. */
private fun variable(resId: Int, weight: FontWeight, w: Int) =
    Font(resId, weight, variationSettings = FontVariation.Settings(FontVariation.weight(w)))

/*
 * Four bundled families. All are SIL Open Font License 1.1; the notices ship in
 * `assets/fonts/NOTICE.txt`, which the licence requires and which was missing entirely.
 */

/**
 * The LCARS voice.
 *
 * The palette here has been authentic for a while — four of its values are byte-identical to the
 * canonical Okuda set — but the app still read as a dark application with square corners, because
 * the other half of the LCARS signature is the letterform: ultra-condensed, all-caps, generously
 * letterspaced. Chakra Petch is angular but normal-width, which is a different genre entirely.
 *
 * Antonio is the standard freely-licensed stand-in for the Swiss 911 family the show used. It takes
 * display, headline and title. It is emphatically **not** for body text — at a paragraph's length an
 * ultra-condensed face is punishing — and not for numbers, which stay monospaced below.
 */
val Antonio = FontFamily(
    variable(R.font.antonio_var, FontWeight.Normal, 400),
    variable(R.font.antonio_var, FontWeight.Medium, 500),
    variable(R.font.antonio_var, FontWeight.SemiBold, 600),
    variable(R.font.antonio_var, FontWeight.Bold, 700),
)

val ChakraPetch = FontFamily(
    Font(R.font.chakra_petch_regular, FontWeight.Normal),
    Font(R.font.chakra_petch_medium, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_bold, FontWeight.Bold),
)

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

/**
 * The house label style: caps, wide tracking, condensed.
 *
 * LCARS text is nearly always set this way, and the app has been approximating it by calling
 * `.uppercase()` ad hoc at individual call sites with whatever letterspacing each one guessed. One
 * style, so a header looks the same wherever it is written.
 */
val LcarsLabel = TextStyle(
    fontFamily = Antonio,
    fontWeight = FontWeight.Bold,
    fontSize = 13.sp,
    letterSpacing = 2.sp,
)

/**
 * Material typography, now on Antonio for anything that carries the LCARS voice.
 *
 * Display, headline and title-large are the app's shouting registers and take the condensed face
 * with real tracking — LCARS letterspacing is generous and it is a large part of why the style reads
 * as engineered rather than merely dark. Body stays Space Grotesk because a condensed face at
 * paragraph length is punishing, and every label stays JetBrains Mono because numbers on a console
 * should line up in columns.
 */
val NightwireTypography = Typography(
    displayLarge = TextStyle(fontFamily = Antonio, fontWeight = FontWeight.Bold, fontSize = 52.sp, letterSpacing = 1.sp),
    displayMedium = TextStyle(fontFamily = Antonio, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 1.sp),
    displaySmall = TextStyle(fontFamily = Antonio, fontWeight = FontWeight.Bold, fontSize = 31.sp, letterSpacing = 1.sp),
    headlineLarge = TextStyle(fontFamily = Antonio, fontWeight = FontWeight.Bold, fontSize = 29.sp, letterSpacing = 1.2.sp),
    headlineMedium = TextStyle(fontFamily = Antonio, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = 1.2.sp),
    headlineSmall = TextStyle(fontFamily = Antonio, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, letterSpacing = 1.2.sp),
    titleLarge = TextStyle(fontFamily = Antonio, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = 1.6.sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 9.sp, letterSpacing = 0.8.sp),
)
