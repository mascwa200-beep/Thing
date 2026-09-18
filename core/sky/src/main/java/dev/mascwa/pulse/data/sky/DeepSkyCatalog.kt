package dev.mascwa.pulse.data.sky

import android.content.Context
import dev.mascwa.pulse.sky.DeepSkyLayer
import dev.mascwa.pulse.sky.DeepSkySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The twelve and a half thousand galaxies, clusters and nebulae, read once and kept.
 *
 * Mirrors [ConstellationCatalog]: in-memory once, a Mutex so two simultaneous first-draws do not
 * both parse it, and the blocking work moved off the caller's thread here rather than at each call
 * site.
 *
 * ⚠️ **The layer is built here rather than by the screen**, unlike the constellations, whose field
 * has to be re-cut on zoom. Nothing about a galaxy changes with the view, so its unit vector is
 * computed once on the IO thread with the parse — putting it in the composable would repeat twelve
 * thousand trigonometric conversions on every recomposition.
 *
 * ⚠️ **No failure note, for the reason [ConstellationCatalog] gives.** The star catalogues report
 * theirs because a missing or wrongly-compressed asset is a build mistake that produces no crash and
 * would never otherwise be noticed. This one is read whole and its absence draws no deep sky, which
 * is plainly visible.
 */
class DeepSkyCatalog(private val context: Context) {

    private val mutex = Mutex()
    private var layer: DeepSkyLayer? = null
    private var attempted = false

    /** The deep sky, or null if the asset is absent or unreadable. */
    suspend fun layer(): DeepSkyLayer? = mutex.withLock {
        layer?.let { return it }
        if (attempted) return null
        attempted = true
        withContext(Dispatchers.IO) {
            DeepSkySource.open(context).takeIf { it.isNotEmpty() }?.let { DeepSkyLayer(it) }
        }.also { layer = it }
    }
}
