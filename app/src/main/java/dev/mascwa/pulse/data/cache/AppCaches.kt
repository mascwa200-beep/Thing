package dev.mascwa.pulse.data.cache

import android.content.Context
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.util.cameraCaptureDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything this app keeps in its cache directory, and the one place that can say how big it is.
 *
 * ⚠️ **Settings reported one of six caches and called it "Cached data".** `refreshCacheSize` read
 * `DiskCache.sizeBytes()` — the small JSON feed store, now bounded at eight megabytes — while the
 * cache directory beside it also holds Coil's image cache (48 MB), OkHttp's response cache (16 MB),
 * downloaded APKs (the LCARS one alone is over three hundred megabytes), camera captures and pack
 * staging. On a phone with no room left that number is the one the user goes looking for, and it was
 * understating the truth by an order of magnitude while a button underneath offered to clear "the
 * cache".
 *
 * ⚠️ **It spans two directories, and that is not an accident to tidy away.** The feed store is
 * rooted at `filesDir`, deliberately: `cacheDir` is reclaimable by the platform without asking, and
 * an offline fallback that the OS may delete at any moment is not a fallback. So the honest total is
 * the cache directory PLUS the feed store, which is also exactly the set [clear] can rebuild —
 * models, photographs and harvested media live in `filesDir` too and are none of this feature's
 * business.
 *
 * ⚠️ **That choice has a cost, and this file used to state only the benefit.** Android's own
 * Settings ▸ Apps ▸ Storage ▸ Clear cache empties `cacheDir` and cannot reach `filesDir` — so
 * putting the feed store there takes it out of reach of the one control every user already knows,
 * which is precisely why THIS class had to exist. The trade is worth making here, where the store
 * holds the offline copy of news, weather, markets and every other screen; it is not obviously
 * worth making everywhere.
 *
 * ⚠️ **So the standalone nutrition app roots the same `DiskCache` at `cacheDir`, and that is right
 * rather than an oversight to converge.** What it caches is Open Food Facts lookups sitting on top
 * of a 4.4-million-row bundled database, so losing them costs a refetch of a handful of products
 * rather than the world; it ships no cache screen; and leaving them in `cacheDir` means the
 * platform's button works. Do not "fix" one app to match the other — the correct root depends on
 * what the cache is for and whether the app offers a control of its own.
 *
 * ⚠️ Reporting and clearing are deliberately asymmetric, and the asymmetry is the interesting part:
 *
 * - **[bytes] reads the filesystem.** Safe from anywhere and needs no instances, so the figure is
 *   the truth about the disk rather than the sum of whatever caches happen to have been constructed.
 *   In particular it does NOT force the image loader into existence, which would mean building a
 *   cache in order to measure it.
 * - **[clear] goes through each owner's own API.** Coil's `DiskCache` and OkHttp's `Cache` each hold
 *   a `DiskLruCache` open with a journal; deleting files underneath one leaves the journal
 *   describing entries that are gone, and that surfaces later as reads which miss or throw with
 *   nothing to connect them to the button that was pressed. Only the directories nobody holds open
 *   are swept by hand.
 *
 * ⚠️ One consequence of that asymmetry, stated rather than left to be discovered: pressing clear in
 * a process that has never drawn a picture builds the image loader in order to empty a cache that
 * was never allocated, and Coil's disk cache creates its directory and journal on the way. A few
 * hundred bytes, on a directory that would exist the moment anything showed a thumbnail — so it is
 * left alone rather than guarded, because the guard would need a second spelling of a path
 * [dev.mascwa.pulse.di.AppContainer] owns, and a drifting path is the worse risk.
 *
 * ⚠️ So [clear] frees less than [bytes] reports, and the caller must say what was actually freed
 * rather than imply the directory is now empty. `packs/` is left alone — it holds one download in
 * progress at a time and the repository deletes it in a `finally`, so it is self-clearing, and it is
 * the one thing here whose removal mid-flight would abort a download the user started, possibly over
 * a metered connection. Anything the platform put in the cache directory on its own account
 * (WebView, the media extractor) is counted and not touched, for the same reason.
 */
class AppCaches(
    private val appContext: Context,
    private val feeds: DiskCache,
    /** Lazy on purpose — see the note above. [bytes] never calls this; [clear] does. */
    private val images: () -> coil.ImageLoader,
    private val http: () -> HttpClient,
) {

    /**
     * What every rebuildable cache is holding right now, in bytes.
     *
     * The feed store reports itself rather than being walked — it knows its own directory, and a
     * second spelling of that path here is how the two quietly stop describing the same thing.
     */
    suspend fun bytes(): Long = withContext(Dispatchers.IO) {
        runCatching { sizeOf(appContext.cacheDir) }.getOrDefault(0L) +
            runCatching { feeds.sizeBytes() }.getOrDefault(0L)
    }

    /**
     * Free what can be freed, and report how much that was.
     *
     * The number is measured either side rather than accumulated from the parts: the parts disagree
     * about what they own (Coil's `size` is its own accounting, not the directory's), and a figure
     * shown to somebody who is short of space should come from the disk.
     */
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        val before = bytes()
        runCatching { feeds.clear() }
        runCatching { images().diskCache?.clear() }
        runCatching { http().evictCache() }
        sweep(cameraCaptureDir(appContext))
        sweep(File(appContext.cacheDir, "apk"))
        (before - bytes()).coerceAtLeast(0L)
    }

    private fun sweep(dir: File) {
        runCatching { dir.listFiles()?.forEach { f -> if (f.isFile) runCatching { f.delete() } } }
    }

    private fun sizeOf(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
