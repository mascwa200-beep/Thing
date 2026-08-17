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
 * Five bundled families. All are SIL Open Font License 1.1; the notices ship in
 * `assets/fonts/NOTICE.txt`, which the licence requires and which was missing entirely.
 */

/**
 * The console voice: wide, squarish, all-caps.
 *
 * This is the era change. The condensed face below is the 1987 console's signature; the machine
 * this app is now dressed as is twenty years older, and its readouts are the opposite genre —
 * broad, geometric, squared-off capitals. Orbitron is the standard freely-licensed face in that
 * genre and it ships weights, which the closer look-alikes do not.
 *
 * ⚠️ **It is 1.86× wider than Antonio per capital** — 0.815 em against 0.433 em, measured from the
 * two files rather than judged by eye — and its capitals are *shorter* at the same nominal size
 * (0.720 em against 0.859). So every size it takes over is set smaller than the condensed face
 * used, and it deliberately does not take over everywhere: see the nav bar, where six labels have
 * to fit across a phone and only a condensed face makes that work.
 */
val Orbitron = FontFamily(
    variable(R.font.orbitron_var, FontWeight.Normal, 400),
    variable(R.font.orbitron_var, FontWeight.Medium, 500),
    variable(R.font.orbitron_var, FontWeight.SemiBold, 600),
    variable(R.font.orbitron_var, FontWeight.Bold, 700),
)

/**
 * The condensed face, kept for the one place width is the binding constraint.
 *
 * It carried display, headline and title until the console moved eras, and it is still here rather
 * than deleted because of the bottom navigation bar: six labels, one row, and the longest of them
 * is COMPUTER. Measured across a 411dp phone, each slot is 64.5dp wide and the label renders at
 * 37dp in Antonio at 9sp and **64dp in Orbitron** — a hair inside the slot on this phone and over
 * it on a 360dp one. That is not a judgement call, it is arithmetic, so the nav keeps the
 * condensed face and everything else moves.
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
    fontFamily = Orbitron,
    fontWeight = FontWeight.Bold,
    fontSize = 11.sp,
    letterSpacing = 1.4.sp,
)

/**
 * Material typography, now on Orbitron for anything that carries the console voice.
 *
 * ⚠️ **Every size here came down, and the tracking with it.** Not taste: the new face is 1.86×
 * wider per capital, so holding the old sizes would have pushed headlines off the side of the
 * screen. Tracking falls too — generous letterspacing is what makes a *condensed* face read as
 * engineered, and applying the same amount to a face that is already broad just makes it loose.
 * The sizes are cut by roughly a fifth rather than by the full width ratio, because Orbitron's
 * capitals are 16% shorter at the same nominal size and cutting for width parity would have left
 * a screen title smaller than the labels under it.
 *
 * Body stays Space Grotesk — a display face at paragraph length is punishing whichever genre it
 * belongs to — and every label stays JetBrains Mono because numbers on a console should line up in
 * columns.
 */
val NightwireTypography = Typography(
    displayLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 40.sp, letterSpacing = 0.5.sp),
    displayMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 31.sp, letterSpacing = 0.5.sp),
    displaySmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 25.sp, letterSpacing = 0.5.sp),
    headlineLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 23.sp, letterSpacing = 0.8.sp),
    headlineMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 19.sp, letterSpacing = 0.8.sp),
    headlineSmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.8.sp),
    titleLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 1.2.sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.8.sp),
    labelSmall = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Normal, fontSize = 9.sp, letterSpacing = 0.8.sp),
)
