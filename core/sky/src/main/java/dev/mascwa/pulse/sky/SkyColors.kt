package dev.mascwa.pulse.sky

import androidx.compose.ui.graphics.Color

/**
 * Every ink the star chart draws with, named for what it draws rather than for a palette.
 *
 * ⚠️ **This is the whole of what an application supplies to the chart.** Measured before it was
 * written: the canvas reached into the LCARS palette in exactly twenty-eight places for ten distinct
 * colours, and touched nothing else of that application — no typeface, no icon, no string resource,
 * no shape. So the seam between "the star renderer" and "an application's look" is this and nothing
 * more, which is what lets one canvas serve two applications rather than two canvases drifting.
 *
 * ⚠️ **Thirteen roles for ten colours, deliberately.** The LCARS chart draws the celestial equator
 * and the IAU borders in one ink and the ecliptic in another, the constellation figures and the
 * planets in a third; a different application may want the equator and the ecliptic to match, or the
 * planets to stand apart from the figures. Naming the ROLE rather than the hue is what makes that a
 * choice instead of a fork. Nothing here forbids passing the same `Color` to several of them — the
 * LCARS mapping does exactly that in five places.
 *
 * ⚠️ Deep-sky colours are **not** repeated here: [DeepSkyLayer]'s own [DeepSkyColors] already
 * carries them, per kind, and has since that layer shipped. A second copy of galaxy/cluster/nebula
 * would be the duplicated-definition drift this module exists to avoid.
 */
data class SkyColors(
    /**
     * The empty sky, and the shadow behind a planet's disc.
     *
     * ⚠️ One role rather than two even though it is drawn twice, because an unlit half of Venus is
     * the same nothing as the space around it — a chart that drew them differently would put a
     * visible edge where the terminator is meant to disappear.
     */
    val space: Color,
    /**
     * A star with no measured colour, and the Milky Way's diffuse light.
     *
     * ⚠️ **NOT every star, and the first version of this line said otherwise.** `StarNames.bandArgb`
     * supplies a real colour from the catalogue's own B−V for the great majority; this is the
     * fallback for the roughly three per cent that carry none, which the renderer names
     * `unmeasuredColour` for exactly that reason — absent is a fact, and painting it a plausible
     * white would be a claim about a measurement nobody made. So an application changing this
     * changes what "unmeasured" looks like and the tint of the Milky Way, and moves no star that
     * was actually measured.
     */
    val starlight: Color,
    /** The Moon's disc. Separate from [starlight] because it is reflected light on a resolved body. */
    val moon: Color,
    /** The Sun's disc. */
    val sun: Color,
    /** A planet's disc or marker. */
    val planet: Color,
    /** The constellation stick figures. */
    val figure: Color,
    /** The asterisms — the Plough, the Summer Triangle — which are not constellations. */
    val asterism: Color,
    /** The celestial equator. */
    val equator: Color,
    /**
     * The ecliptic.
     *
     * ⚠️ Its own role, and in the LCARS chart its own colour, because the ecliptic is the one line
     * on the map that PREDICTS something: every planet and the Moon sit within a few degrees of it,
     * so once it is drawn half the sky is ruled out at a glance. A line that looks like the equator
     * says nothing.
     */
    val ecliptic: Color,
    /** The IAU constellation boundaries. */
    val border: Color,
    /** Star names, deep-sky names, and the cardinal letters other than north. */
    val label: Color,
    /** The horizon itself. */
    val horizon: Color,
    /** The letter N on the horizon, which is the one bearing worth finding without reading. */
    val north: Color,
    /** How each kind of galaxy, cluster and nebula is drawn. */
    val deepSky: DeepSkyColors,
)
