package dev.mascwa.pulse.desktop.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * The real bundled typefaces, at last.
 *
 * This module rendered in the system sans and monospace from the day it was created — a placeholder
 * that outlived several releases and was the single loudest reason the companion looked like a
 * generic desktop application beside a phone that looks like a console. The letterforms *are* the
 * identity here; the palette and the cut plates get you most of the way and the type is the rest.
 *
 * The files are not duplicated into this module. `processResources` copies them straight out of
 * `app/src/main/res/font`, the same trick that bundles the guide library, so one copy of each `.ttf`
 * lives in the repository and the two platforms cannot drift onto different versions of a face.
 *
 * ⚠️ **Variable fonts register one weight here, and that is deliberate.** Desktop `Font(resource)`
 * hands the bytes to Skia, which instantiates a variable font at its *default* master — there is no
 * parameter for a variation axis, so registering the same file four times under four weights would
 * produce four identical 400-weight faces and Compose, seeing an exact match, would stop looking.
 * Registering only the weight that genuinely exists leaves it free to synthesise the heavier ones.
 * Chakra Petch is the exception because it ships as four real static files, so it gets all four.
 *
 * ⚠️ **Nothing here is subsetted or instanced.** Orbitron carries a Reserved Font Name under the
 * OFL, so a modified derivative could not keep the name; shipping the published file byte-for-byte
 * sidesteps that entirely. It also happens to be why the paragraph above is a constraint rather
 * than a choice.
 */

/** The console voice: wide, squared capitals. Headers, titles, alert banners. */
val Orbitron = FontFamily(
    Font(resource = "font/orbitron_var.ttf", weight = FontWeight.Normal),
)

/** The workhorse: headings, values, most body text. Four real weights. */
val ChakraPetch = FontFamily(
    Font(resource = "font/chakra_petch_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "font/chakra_petch_medium.ttf", weight = FontWeight.Medium),
    Font(resource = "font/chakra_petch_semibold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "font/chakra_petch_bold.ttf", weight = FontWeight.Bold),
)

/** Every reading, label and numeric column, so figures line up. */
val JetBrainsMono = FontFamily(
    Font(resource = "font/jetbrains_mono_var.ttf", weight = FontWeight.Normal),
)
