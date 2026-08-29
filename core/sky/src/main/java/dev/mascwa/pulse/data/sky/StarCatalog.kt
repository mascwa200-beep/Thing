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
 * ⚠️ **Positions are J2000, and both things that move them are now corrected — this note used to
 * say neither was, and that reasoning went stale the day the map's field floor dropped to a quarter
 * of a degree.** It argued that precession's 22 arcminutes was "smaller than the dot a star is
 * drawn as", which was true at the old four-degree floor and is 1,573 pixels at the new one.
 * Precession is handled once for the whole sky by `SkyFrame`, which builds its basis in J2000;
 * proper motion is per-star and is carried from these columns by `ProperMotion` wherever the layer
 * is filled.
 *
 * ⚠️ **The proper-motion columns were added for a measured reason.** The map draws this catalogue
 * AND a deep Gaia one whole, overlapping — and 12,602 Gaia records fall inside this catalogue's own
 * magnitude limit, so they are the same stars drawn twice. Carrying only Gaia's copy forward would
 * put 1,339 of those pairs more than four pixels apart at the narrowest field, the worst by 226.
 * Two dots where there is one star. Both sets carry proper motion or neither does.
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
        /**
         * The PROJECTED right-ascension motion, `cos(dec) * d(ra)/dt`, in milliarcseconds a year.
         *
         * ⚠️ Projected, as the catalogue's own ReadMe states it, and in the same unit the deep Gaia
         * set uses — so `ProperMotion.carry` serves both without a conversion that could be
         * forgotten at one of the two call sites. Zero for the four entries with none recorded, and
         * zero is also what an older asset with only seven columns yields.
         */
        val pmRaMasPerYear: Double = 0.0,
        val pmDecMasPerYear: Double = 0.0,
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
                Star(
                    ra, dec, mag, f[3].toDoubleOrNull(), f[4], f[5], f[6],
                    // ⚠️ `getOrNull`, so an asset written before these columns existed still parses
                    // into a catalogue that simply does not move. The guard above still admits a
                    // seven-column row; silently refusing every star would be a blank sky.
                    f.getOrNull(7)?.toDoubleOrNull() ?: 0.0,
                    f.getOrNull(8)?.toDoubleOrNull() ?: 0.0,
                )
            }.toList()
        }
    }.getOrDefault(emptyList())

    companion object {
        internal const val ASSET = "sky/stars.tsv"

        /**
         * The epoch these positions are referred to, as a Julian year.
         *
         * ⚠️ **Public where everything else in this companion is internal, and the day this class
         * moved into `:core:sky` that stopped being a style question.** `internal` is scoped to a
         * Gradle module, so the whole companion being internal was fine while the class lived in
         * `:app` beside its two readers and became a compile error the moment it did not — the same
         * shape that stopped a test seeing `StoredEntry` when the health layer was carved out.
         * [ASSET] stays internal because nothing outside this file has ever named it.
         *
         * ⚠️ **Here rather than on either of the two view models that carry proper motion from it.**
         * The epoch is a property of the file, not of a screen, and there are now two readers — the
         * map's star layer and the occultation search — so a constant on one of them would be a
         * second statement of the same fact, free to drift the day the asset is rebuilt against a
         * different catalogue.
         *
         * ⚠️ A constant at all, rather than a column, because the whole file is one epoch. The deep
         * Gaia catalogue is the other way round: it states its epoch in its own header and
         * `StarCatalogReader` reads it, because that builder can be pointed at a different data
         * release. This asset's second header line says J2000 and
         * `tools/sky/build_star_catalog.py` is what put it there.
         */
        const val EPOCH_YEAR = 2000.0
    }
}
