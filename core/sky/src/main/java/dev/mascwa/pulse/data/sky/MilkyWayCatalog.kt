package dev.mascwa.pulse.data.sky

import android.content.Context
import dev.mascwa.pulse.core.telemetry.MilkyWay
import dev.mascwa.pulse.sky.MilkyWaySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The star-density raster, read once and kept.
 *
 * Mirrors [DeepSkyCatalog] and [ConstellationCatalog]: in-memory once, a Mutex so two simultaneous
 * first-draws do not both parse it, and the blocking work moved off the caller's thread here rather
 * than at each call site.
 *
 * ⚠️ **Nothing is precomputed from it, unlike the deep sky.** A galaxy's unit vector is worth
 * computing once at load because it never changes; the raster is already in exactly the form the
 * draw pass indexes, so there is no layer type here at all — just the sixty-five kilobytes.
 *
 * ⚠️ **No failure note, for the reason [ConstellationCatalog] gives.** The star catalogues report
 * theirs because a missing or wrongly-compressed asset is a build mistake that produces no crash and
 * would never otherwise be noticed. A missing raster draws no glow, which is plainly visible — and
 * [MilkyWay.readRaster] refuses a file it does not recognise rather than drawing it as noise, which
 * is the failure that would not have been.
 */
class MilkyWayCatalog(private val context: Context) {

    private val mutex = Mutex()
    private var raster: MilkyWay.Raster? = null
    private var attempted = false

    /** The raster, or null if the asset is absent, unreadable or not one this code understands. */
    suspend fun raster(): MilkyWay.Raster? = mutex.withLock {
        raster?.let { return it }
        if (attempted) return null
        attempted = true
        withContext(Dispatchers.IO) { MilkyWaySource.open(context) }.also { raster = it }
    }
}
