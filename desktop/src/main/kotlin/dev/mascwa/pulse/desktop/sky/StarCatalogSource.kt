package dev.mascwa.pulse.desktop.sky

import dev.mascwa.pulse.core.telemetry.StarNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The 8,404 naked-eye stars, read once from the bundled catalogue.
 *
 * ⚠️ **The same file the phone draws from, not a second copy of it.** `stars.tsv` lives once in the
 * repository, under the Android app's asset directory, and `build.gradle.kts` copies it into this
 * module's resources at build time — exactly as the Knowledge Base and the typefaces are borrowed.
 * A checked-in duplicate would drift the first time the catalogue was rebuilt, and the two consoles
 * would quietly disagree about where a star is.
 *
 * ⚠️ **No network, ever.** A star chart that needs a connection is useless in the one place anybody
 * wants one. A quarter of a megabyte of tab-separated text is the whole cost.
 *
 * ## What it does not carry, said once — the same caveats the phone's copy states
 *
 * Positions are J2000 and are used as if they were of-date. Precession moves a star about 50
 * arcseconds a year, so by 2050 the sky will have drifted roughly two fifths of a degree from these
 * coordinates — a fifth of the Moon's width, smaller than the dot a star is drawn as. Proper motion
 * is smaller still for all but a handful. Correcting for either would be arithmetic nobody could
 * see.
 *
 * A star that genuinely varies is listed at the catalogue's magnitude, which may be its maximum.
 * Across the whole naked-eye sky that matters for one entry.
 */
class StarCatalogSource {

    /**
     * One star, as bundled.
     *
     * ⚠️ Right ascension and declination are **equatorial**, not the horizon coordinates a chart
     * draws in. The conversion needs the observer and the time, so it belongs to whoever is drawing
     * rather than to the catalogue — the same shape `Ephemeris` uses for the Sun and Moon.
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
     * ⚠️ **Loaded once and kept.** 8,404 rows is about 800 kB of objects, which is nothing on a
     * machine with a power cable and far cheaper than re-parsing a quarter of a megabyte every time
     * the page is opened. The Mutex is what stops two simultaneous first-draws each parsing the
     * whole file — and on this platform that is a live case rather than a theoretical one, since
     * the same screen can be torn off into its own window and shown on the ops wall at once.
     */
    suspend fun all(): List<Star> {
        loaded?.let { return it }
        return mutex.withLock {
            loaded ?: withContext(Dispatchers.IO) { parse() }.also { loaded = it }
        }
    }

    /**
     * Every star brighter than [limit], which is the only query a chart makes.
     *
     * ⚠️ Relies on the file being sorted brightest first, so this is a **prefix** rather than a
     * filter — `takeWhile` stops at the first star too faint to draw instead of walking the other
     * eight thousand.
     */
    suspend fun brighterThan(limit: Double): List<Star> = all().takeWhile { it.magnitude <= limit }

    /**
     * ⚠️ Returns an empty list rather than throwing if the resource is missing or unreadable. A
     * chart with no stars is visibly wrong and the screen says so; a crash on opening a page is
     * worse. This is the one subsystem here whose data cannot go stale or wrong at runtime — if it
     * fails it failed at build time, which is what `StarCatalogBundleTest` exists to catch.
     */
    private fun parse(): List<Star> = runCatching {
        javaClass.getResourceAsStream(RESOURCE)!!.bufferedReader().useLines { lines ->
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

    internal companion object {
        /**
         * ⚠️ Absolute, so it resolves against the jar root rather than this class's own package.
         * `build.gradle.kts` copies the asset directory into `sky/`; a relative path here would look
         * for it under `dev/mascwa/pulse/desktop/sky/`, find nothing, and produce an empty sky with
         * no error anywhere — which is why the bundle test asserts on this same constant rather than
         * on a string typed twice.
         */
        const val RESOURCE = "/sky/stars.tsv"
    }
}
