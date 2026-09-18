package dev.mascwa.pulse.sky

import android.content.Context
import dev.mascwa.pulse.core.telemetry.Constellations

/**
 * Getting the bundled constellation asset off disk.
 *
 * The line counterpart of [SkyCatalogSource], and the same division of labour: [Constellations] is
 * pure and takes a string, this is the one piece that knows the bytes live in an Android asset. That
 * is what lets the parse be run against the real file on a build machine rather than merely compiled.
 *
 * ⚠️ **No `noCompress` needed here, unlike the star catalogue.** This one is 65 kB of JSON read once
 * and whole; there is no index and no random access to preserve, so letting Android deflate it is
 * free. The star catalogue's warning does not transfer.
 */
object ConstellationSource {

    /** Where the bundled figures, asterisms and borders live. */
    const val ASSET = "sky/constellations.json"

    /**
     * Read and decode, or null.
     *
     * ⚠️ Blocking: it opens a file. The caller runs it off the main thread.
     *
     * Null covers both "not in this build" and "not readable", and the caller treats them the same
     * because there is nothing useful to say about the difference — either way the map draws stars
     * and no constellations, which is a working map.
     */
    fun open(context: Context, asset: String = ASSET): Constellations.Data? =
        runCatching { context.assets.open(asset).use { it.readBytes().decodeToString() } }
            .getOrNull()
            ?.let { Constellations.parse(it) }
}
