package dev.mascwa.pulse.sky

import android.content.Context
import dev.mascwa.pulse.core.telemetry.StarCatalogReader
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Getting a few hundred megabytes of packed stars into a reader without loading them.
 *
 * [StarCatalogReader] is deliberately pure and takes a [ByteBuffer]; this is the one piece that
 * knows the bytes live in an Android asset. Keeping the two apart is what lets the decoder be run
 * against the real catalogue on a build machine instead of merely compiled.
 *
 * ## ⚠️ The asset MUST be stored uncompressed, and forgetting costs the whole design
 *
 * Android deflates assets by default, and a deflated asset cannot be memory-mapped or read at a
 * random offset — the only way to get at it is to inflate the entire thing, which for this file is
 * hundreds of megabytes of heap on a phone the app is meant to run on. That defeats the point of a
 * tile index: the format exists so a view reads a few kilobytes rather than the lot.
 *
 * So each application that bundles `stars.skycat` has to declare
 *
 *     androidResources { noCompress += "skycat" }
 *
 * ⚠️ **and that declaration cannot live here.** Packaging is decided by the module that builds the
 * APK, so a library's setting does not propagate — every application bundling this asset has to say
 * it separately, which is exactly the kind of thing that gets forgotten when a second one is added.
 * It is nearly free: measured, the packed binary deflates to 93.7% of its size, so storing it
 * uncompressed costs about 1.5 MB and buys zero-copy random access.
 *
 * The cost of forgetting is not a crash. It is the map working, slowly, using far more memory than
 * it should — which is why [Opened.mapped] is reported rather than inferred, and why the fallback
 * says what happened instead of quietly papering over it.
 */
object SkyCatalogSource {

    /** Where the bundled core-tier catalogue lives, in both applications. */
    const val ASSET = "sky/stars.skycat"

    /** A catalogue that opened, and how. */
    data class Opened(
        val reader: StarCatalogReader,
        /**
         * True when the file was memory-mapped, which is what the format is designed for.
         *
         * False means it was read onto the heap instead — the catalogue works, and it cost
         * hundreds of megabytes that it should not have. See [note].
         */
        val mapped: Boolean,
        /** Null when everything is as it should be; otherwise what went wrong, in words. */
        val note: String?,
    )

    /** Why a catalogue could not be opened at all, distinguished from opening it the slow way. */
    sealed interface Result {
        data class Ready(val opened: Opened) : Result
        data class Unusable(val reason: String) : Result
    }

    /**
     * Open the bundled catalogue.
     *
     * ⚠️ Blocking: it touches the filesystem, and on the fallback path it reads twenty-five
     * megabytes. The caller runs it off the main thread.
     */
    fun open(context: Context, asset: String = ASSET): Result {
        val mapped = tryMap(context, asset)
        if (mapped != null) {
            return when (val outcome = StarCatalogReader.open(mapped)) {
                is StarCatalogReader.Outcome.Ready -> Result.Ready(Opened(outcome.reader, true, null))
                is StarCatalogReader.Outcome.Unusable -> Result.Unusable(outcome.reason)
            }
        }

        // ⚠️ The fallback is not a silent equivalent. It is here because a map failure is a build
        // configuration mistake rather than a broken file, and a sky that draws while costing too
        // much memory beats one that refuses to draw at all — but it says so, because otherwise the
        // mistake would never be noticed.
        val bytes = runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
            ?: return Result.Unusable(
                "the star catalogue is not in this build — $asset could not be opened at all",
            )
        return when (val outcome = StarCatalogReader.open(ByteBuffer.wrap(bytes))) {
            is StarCatalogReader.Outcome.Ready -> Result.Ready(
                Opened(
                    outcome.reader,
                    mapped = false,
                    note = "the catalogue had to be read onto the heap rather than mapped, which " +
                        "costs ${bytes.size / 1_048_576} MB. The build is missing " +
                        "`androidResources { noCompress += \"skycat\" }`.",
                ),
            )
            is StarCatalogReader.Outcome.Unusable -> Result.Unusable(outcome.reason)
        }
    }

    /**
     * Map the asset if it was stored uncompressed, or null.
     *
     * ⚠️ `openFd` is the test as well as the mechanism: it throws for a compressed asset, because
     * there is no file region to hand back — the bytes only exist once inflated. So a null here
     * means "this build compressed it", which is precisely what the caller needs to report.
     */
    private fun tryMap(context: Context, asset: String): ByteBuffer? = runCatching {
        context.assets.openFd(asset).use { fd ->
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
            }
        }
    }.getOrNull()
}
