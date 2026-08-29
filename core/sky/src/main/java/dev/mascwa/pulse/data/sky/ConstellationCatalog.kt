package dev.mascwa.pulse.data.sky

import android.content.Context
import dev.mascwa.pulse.core.telemetry.Constellations
import dev.mascwa.pulse.sky.ConstellationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The 88 figures, their asterisms and the IAU borders, read once and kept.
 *
 * Mirrors [DeepStarCatalog]: in-memory once, a Mutex so two simultaneous first-draws do not both
 * parse it, and the blocking work moved off the caller's thread here rather than at each call site.
 *
 * ⚠️ **No failure note, unlike the star catalogues, and that is deliberate.** Their two failure
 * modes are build mistakes that produce no crash and would never otherwise be noticed — a missing
 * asset makes the map look thin, a compressed one makes it cost twenty-five megabytes. This asset
 * has neither hazard: it is 65 kB, read whole, and its absence draws no constellation lines, which
 * is plainly visible. Reporting it would be chrome nobody reads.
 */
class ConstellationCatalog(private val context: Context) {

    private val mutex = Mutex()
    private var data: Constellations.Data? = null
    private var attempted = false

    /** The constellations, or null if the asset is absent or unreadable. */
    suspend fun data(): Constellations.Data? = mutex.withLock {
        data?.let { return it }
        if (attempted) return null
        attempted = true
        withContext(Dispatchers.IO) { ConstellationSource.open(context) }.also { data = it }
    }
}
