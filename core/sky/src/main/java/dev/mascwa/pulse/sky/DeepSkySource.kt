package dev.mascwa.pulse.sky

import android.content.Context
import dev.mascwa.pulse.core.telemetry.DeepSky

/**
 * Getting the bundled deep-sky catalogue off disk.
 *
 * The same division of labour as [ConstellationSource]: [DeepSky] is pure and takes a string, this
 * is the one piece that knows the bytes live in an Android asset. That is what lets the parse be run
 * against the real file on a build machine rather than merely compiled.
 *
 * ⚠️ **No `noCompress`, and unlike the constellations that is worth a second's thought rather than a
 * shrug.** This asset is 632 kB, six times theirs. But it is still read once and whole — there is no
 * index and no random access to preserve — and it deflates to 239 kB, so letting Android compress it
 * saves nearly four hundred kilobytes of install for one decompression at startup. The star
 * catalogue's warning applies to the star catalogue, which is memory-mapped.
 */
object DeepSkySource {

    /** Where the bundled galaxies, clusters and nebulae live. */
    const val ASSET = "sky/deepsky.tsv"

    /**
     * Read and decode, or an empty list.
     *
     * ⚠️ Blocking: it opens a file. The caller runs it off the main thread.
     *
     * Empty covers both "not in this build" and "not readable", and the caller treats them the same
     * because there is nothing useful to say about the difference — either way the map draws stars
     * and constellations and no deep sky, which is a working map.
     */
    fun open(context: Context, asset: String = ASSET): List<DeepSky.Entry> =
        runCatching { context.assets.open(asset).use { it.readBytes().decodeToString() } }
            .getOrNull()
            ?.let { DeepSky.parse(it) }
            .orEmpty()
}
