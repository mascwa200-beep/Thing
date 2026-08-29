package dev.mascwa.pulse.data.sky

import android.content.Context
import dev.mascwa.pulse.sky.SkyCatalogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The three million packed stars, opened once and kept.
 *
 * ⚠️ **Opening is not reading.** [SkyCatalogSource] memory-maps the asset, so this costs a file
 * descriptor and a page table entry rather than twenty-five megabytes of heap — which is the whole
 * reason the format exists. What is read is decided per view by
 * [dev.mascwa.pulse.core.telemetry.SkyFieldPlan].
 *
 * ⚠️ **A failure is reported, not swallowed.** The two ways this goes wrong are both build mistakes
 * that produce no crash: the asset is absent (the map falls back to the bright catalogue alone and
 * looks thin), or it was stored compressed and had to be read onto the heap (the map works and costs
 * far more memory than it should). Neither would ever be noticed without [note].
 *
 * Mirrors [StarCatalog]: in-memory once, a Mutex so two simultaneous first-draws do not both open it.
 */
class DeepStarCatalog(private val context: Context) {

    private val mutex = Mutex()
    private var opened: SkyCatalogSource.Opened? = null
    private var failure: String? = null
    private var attempted = false

    /**
     * The catalogue, or null if it could not be opened at all.
     *
     * ⚠️ Blocking work, moved off the caller's thread here rather than at each call site: opening
     * touches the filesystem, and on the fallback path it reads the whole file.
     */
    suspend fun opened(): SkyCatalogSource.Opened? = mutex.withLock {
        opened?.let { return it }
        if (attempted) return null
        attempted = true
        when (val result = withContext(Dispatchers.IO) { SkyCatalogSource.open(context) }) {
            is SkyCatalogSource.Result.Ready -> result.opened.also { opened = it }
            is SkyCatalogSource.Result.Unusable -> {
                failure = result.reason
                null
            }
        }
    }

    /**
     * What went wrong, in words, or null when everything is as it should be.
     *
     * ⚠️ Only meaningful after [opened] has been called — before that there is nothing to report,
     * which is different from there being nothing wrong.
     */
    suspend fun note(): String? = mutex.withLock { failure ?: opened?.note }
}
