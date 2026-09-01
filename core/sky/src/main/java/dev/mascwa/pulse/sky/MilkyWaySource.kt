package dev.mascwa.pulse.sky

import android.content.Context
import dev.mascwa.pulse.core.telemetry.MilkyWay

/**
 * Getting the bundled Milky Way raster off disk.
 *
 * The same division of labour as [DeepSkySource] and [ConstellationSource]: [MilkyWay.readRaster] is
 * pure and takes bytes — so it can be run against the real file on a build machine — and this is the
 * one piece that knows they live in an Android asset.
 *
 * ⚠️ **No `noCompress`, and here it costs almost nothing to check.** The file is 64,816 bytes and
 * deflates to 45,876, it is read once and whole, and there is no index or random access to preserve.
 * The star catalogue's warning applies to the star catalogue, which is memory-mapped.
 */
object MilkyWaySource {

    /** Where the bundled star-density raster lives. */
    const val ASSET = "sky/milkyway.bin"

    /**
     * Read and decode, or null.
     *
     * ⚠️ Blocking: it opens a file. The caller runs it off the main thread.
     *
     * Null covers "not in this build", "not readable" and "not a raster this code understands", and
     * the caller treats them the same because there is nothing useful to say about the difference —
     * the map draws stars and constellations over a plain black sky, which is a working map.
     */
    fun open(context: Context, asset: String = ASSET): MilkyWay.Raster? =
        runCatching { context.assets.open(asset).use { it.readBytes() } }
            .getOrNull()
            ?.let { MilkyWay.readRaster(it) }
}
