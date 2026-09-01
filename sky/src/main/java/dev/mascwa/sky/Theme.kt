package dev.mascwa.sky

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import dev.mascwa.pulse.sky.DeepSkyColors
import dev.mascwa.pulse.sky.SkyColors

/**
 * How this application looks: dark, plain, and the same on every phone.
 *
 * ⚠️ **A fixed dark scheme, and dynamic colour is deliberately not used.** Material You would tint
 * the chrome from the wallpaper, which for most phones means a light scheme — and a star map on a
 * light background is not a style choice anybody would make twice. It would also put the wallpaper's
 * accent next to a sky whose colours are measured, which is the one place in this application where
 * an arbitrary hue would be actively misleading.
 *
 * ⚠️ It is NOT the LCARS palette either. That application's amber-and-violet console is its own
 * identity; this one is a star map and should look like a star map in a launcher and on screen.
 */
@Composable
fun SkyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SCHEME, content = content)
}

private val SCHEME = darkColorScheme(
    // A cold blue-white, which is what a chart drawn on a night sky wants for its own furniture.
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF06121F),
    secondary = Color(0xFF8FA7C4),
    onSecondary = Color(0xFF06121F),
    // ⚠️ Near-black rather than Material's default dark surface, which is a light grey by comparison
    // and would sit in a visible rectangle around a sky drawn at [SkyColors.space].
    background = Color(0xFF000105),
    onBackground = Color(0xFFDCE6F5),
    surface = Color(0xFF080B12),
    onSurface = Color(0xFFDCE6F5),
    surfaceVariant = Color(0xFF141A26),
    onSurfaceVariant = Color(0xFF9AA8BE),
    outline = Color(0xFF39445A),
    error = Color(0xFFFFB4A6),
    onError = Color(0xFF3A0A03),
)

/**
 * Which ink the chart draws each layer of the sky with.
 *
 * ⚠️ **The whole of what this application hands the renderer**, and the mirror of the LCARS
 * application's own mapping — see [SkyColors], which names ROLES rather than hues for exactly this
 * reason. The two applications draw one sky and give it two looks, and this function is the entire
 * difference between them.
 *
 * ⚠️ **This map pulls apart four roles the LCARS one collapses**, which is the argument for thirteen
 * roles made concrete rather than hypothetically: there the equator and the borders share an ink and
 * the figures share one with the planets, because that palette has few colours to spend. Here a
 * planet is warm, a constellation figure is cold, the equator is dimmer than the ecliptic, and the
 * borders are dimmer than either — the conventions of a printed star chart, which is what this
 * application is imitating.
 */
@Composable
fun skyColours(): SkyColors = remember {
    SkyColors(
        // ⚠️ Very nearly black, and not pure black on purpose: a true #000 sky makes the faintest
        // stars — which the renderer draws at low alpha — vanish into the panel on an OLED screen
        // that turns those pixels off entirely. A few units of blue keeps them.
        space = Color(0xFF000105),
        // The fallback for a star with no measured colour, and the Milky Way's tint. Faintly warm
        // white rather than pure: a sky of #FFFFFF points reads as a screen, not as stars.
        starlight = Color(0xFFF2F5FA),
        moon = Color(0xFFE8E4D8),
        sun = Color(0xFFFFD07A),
        planet = Color(0xFFFFC98C),
        figure = Color(0xFF7FA8E0),
        asterism = Color(0xFF9C86D8),
        equator = Color(0xFF6E7C93),
        // Warmer and brighter than the equator, because the ecliptic is the line that PREDICTS
        // something: every planet and the Moon sit within a few degrees of it.
        ecliptic = Color(0xFFD9A64F),
        border = Color(0xFF4E5A70),
        label = Color(0xFF9AA8BE),
        horizon = Color(0xFF55617A),
        north = Color(0xFFFF8A6B),
        deepSky = DeepSkyColors(
            // The conventions of a printed atlas: galaxies warm, clusters yellow because they are
            // made of stars, nebulae cool, planetaries teal — which is genuinely what doubly-ionised
            // oxygen looks like through an eyepiece.
            galaxy = Color(0xFFE79BB4),
            cluster = Color(0xFFF0D48A),
            nebula = Color(0xFF9FD8E8),
            planetary = Color(0xFF7FE8D0),
            remnant = Color(0xFF9FD8E8),
            // The dimmest ink here, for the one kind of object that is a place with LESS light in it.
            dark = Color(0xFF3A4457),
            other = Color(0xFF8896AC),
        ),
    )
}
