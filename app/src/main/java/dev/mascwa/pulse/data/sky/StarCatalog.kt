package dev.mascwa.pulse.data.sky

import android.content.Context
import dev.mascwa.pulse.core.telemetry.StarNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The 8,404 naked-eye stars, read once from the bundled catalogue.
 *
 * ⚠️ **Everything here works with the radio off, and that is the point.** A star chart that needs a
 * network is a star chart that is useless in the one place you would want it — a field at night,
 * miles from anywhere. The whole catalogue costs about a hundred kilobytes in the APK.
 *
 * ## What it does not carry, said once
 *
 * ⚠️ **Positions are J2000 and are used as if they were of-date.** Precession moves a star by about
 * 50 arcseconds a year, so by 2050 the whole sky will have drifted roughly two fifths of a degree
 * from these coordinates — which is a fifth of the Moon's width, smaller than the dot a star is
 * drawn as, and far smaller than how well anybody can point a phone. Proper motion is smaller still
 * for all but a handful. Correcting for either would be arithmetic nobody could see.
 *
 * ⚠️ **A star that genuinely varies is plotted at the catalogue's magnitude, which may be its
 * maximum.** Across the whole naked-eye sky that matters for one entry — the recurrent nova T
 * Coronae Borealis, listed at second magnitude for its outburst while it normally sits near tenth —
 * and `tools/sky/build_star_catalog.py` says why a "variable" flag would be worse than the gap.
 */
class StarCatalog(private val context: Context) {

    /**
     * One star, as bundled.
     *
     * ⚠️ Right ascension and declination are **equatorial**, not the horizon coordinates the map
     * draws in. The conversion needs the observer and the time, so it belongs to whoever is drawing
     * rather than to the catalogue, which is the same shape [dev.mascwa.pulse.core.telemetry.Ephemeris]
     * uses for the Sun and Moon.
     */
    data class Star(
        val rightAscensionDeg: Double,
        val declinationDeg: Double,
        val magnitude: Double,
        /** B-V colour index. Null when the catalogue has none — about 3% of entries. */
        val colourIndex: Double?,
        val bayer: String,
        val flamsteed: String,
        val constellation: String,
    ) {
        /** "Sirius", "α Canis Majoris", "61 Cygni", or null for a star with no designation. */
        val name: String? get() = StarNames.label(bayer, flamsteed, constellation)

        /** The compact form for drawing on a crowded chart. */
        val shortName: String? get() = StarNames.shortLabel(bayer, flamsteed, constellation)
    }

    private val mutex = Mutex()
    private var loaded: List<Star>? = null

    /**
     * Every star, brightest first.
     *
     * ⚠️ **Loaded once and kept.** 8,404 rows is about 800 kB of objects, which is small beside the
     * image cache and far cheaper than re-parsing a quarter of a megabyte every time somebody drags
     * the chart. The Mutex is what stops two simultaneous first-draws each parsing the whole file.
     */
    suspend fun all(): List<Star> {
        loaded?.let { return it }
        return mutex.withLock {
            loaded ?: withContext(Dispatchers.IO) { parse() }.also { loaded = it }
        }
    }

    /**
     * Every star brighter than [limit], which is the only query the map makes.
     *
     * ⚠️ Relies on the file being sorted brightest first, so this is a **prefix** rather than a
     * filter — `takeWhile` stops at the first star too faint to draw instead of walking the other
     * eight thousand. At a wide field that is a few hundred rows out of 8,404.
     */
    suspend fun brighterThan(limit: Double): List<Star> = all().takeWhile { it.magnitude <= limit }

    /**
     * ⚠️ Returns an empty list rather than throwing if the asset is missing or unreadable. A star
     * chart with no stars is visibly broken and the screen says so; a crash on opening a page is
     * worse, and this is the one subsystem in the app whose data cannot go stale or wrong at
     * runtime — if it fails, it failed at build time and CI's own asset test would have caught it.
     */
    private fun parse(): List<Star> = runCatching {
        context.assets.open(ASSET).bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val f = line.split('\t')
                if (f.size < 7) return@mapNotNull null
                val ra = f[0].toDoubleOrNull() ?: return@mapNotNull null
                val dec = f[1].toDoubleOrNull() ?: return@mapNotNull null
                val mag = f[2].toDoubleOrNull() ?: return@mapNotNull null
                Star(ra, dec, mag, f[3].toDoubleOrNull(), f[4], f[5], f[6])
            }.toList()
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val ASSET = "sky/stars.tsv"
    }
}
