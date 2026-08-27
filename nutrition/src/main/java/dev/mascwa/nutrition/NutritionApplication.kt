package dev.mascwa.nutrition

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import dev.mascwa.nutrition.data.NutritionContainer
import dev.mascwa.pulse.crash.Breadcrumbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The process, and the two things that have to happen before anything else does.
 *
 * ⚠️ **This class exists because a crash handler installed from an activity is installed too late.**
 * Until now the manifest named no application class, so the earliest code this app ran was
 * `MainActivity.onCreate` — and the failures worth catching most are the ones that happen before a
 * first frame is ever drawn: a database that will not open, a store whose file is corrupt, a
 * dependency that throws while it is being constructed. Every one of those killed the app with
 * nothing recorded and nothing to read afterwards.
 *
 * ⚠️ **It also owns the container, and that is not a style choice.** Two `NutritionContainer`s in one
 * process means two of every store, and several of them hold a DataStore over a fixed file — which
 * throws outright on a second instance. The activity used to build its own; now it reads this one,
 * so there is exactly one.
 */
class NutritionApplication : Application(), ImageLoaderFactory {

    /**
     * Built eagerly here, which costs nothing: every member of the container is `by lazy`, so
     * constructing it allocates one object and opens no file, no database and no socket.
     */
    val container: NutritionContainer by lazy { NutritionContainer(this) }

    /**
     * ⚠️ The application's own scope, not a coroutine tied to a screen. Sending a report that was
     * recorded last launch has nothing to do with whether anybody is looking at the app, and a
     * launch dispatched from an activity would be cancelled the moment it was backgrounded — which
     * is exactly when an upload gets the time to finish.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // ⚠️ FIRST, before anything that could itself fail. A handler installed after the thing it
        // would have caught is worth nothing, and this is the only ordering that cannot be got wrong
        // by something added below it later.
        container.crashReporter.install()
        Breadcrumbs.drop("app", "process started")

        // Anything recorded before this launch goes now — never at fault time, when the JVM is
        // unstable and the process is about to be killed. A no-op without a token, and it says so.
        scope.launch { container.crashUploader.uploadPending() }
    }

    /**
     * How much of a cheap phone's heap this app is allowed to spend on pictures.
     *
     * ⚠️ **Coil's default is 20% of app memory, and this app has exactly one image on one screen.**
     * Measured out of the shipped coil-base 2.7.0 bytecode rather than recalled:
     * `coil.util.-Utils.defaultMemoryCacheSizePercent` returns **0.15 when
     * `ActivityManager.isLowRamDevice` and 0.20 otherwise**. On a 4 GB phone with a standard heap
     * that is roughly forty megabytes held back — for a `LazyRow` of 96 dp progress-photograph
     * thumbnails, which is the only `AsyncImage` in the whole application.
     *
     * A 96 dp thumbnail at 3× is 288×288 in ARGB_8888, which is 331 kB. Six per cent of the same
     * heap is about eleven megabytes, so roughly thirty-five of them — comfortably more than a row
     * can show, and a quarter of what was reserved. The number matches the LCARS application, which
     * arrived at it for the same reason with far more image surfaces than this.
     *
     * ⚠️ **No disk cache at all, and that costs nothing.** Coil's disk cache exists to avoid
     * re-fetching over the network; every image here is a local file this app wrote itself, so the
     * cache would be a second copy of a photograph already on the disk, in a directory Android may
     * clear at any moment. This app makes no image request of any kind.
     *
     * ⚠️ Built lazily by Coil on the first image, not during `onCreate`, so it costs nothing at
     * startup — which is the other half of what this class is for.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(THUMBNAIL_HEAP_SHARE).build() }
        .diskCache(null)
        .build()
        .also { loader = it }

    /**
     * The loader Coil actually built, or null if it never asked for one.
     *
     * ⚠️ **Held here so [onTrimMemory] can clear a cache that EXISTS without creating one that does
     * not.** `Coil.imageLoader(context)` builds on demand, so reaching for it under memory pressure
     * would allocate a fresh memory cache in response to being told memory is short — precisely
     * backwards. Somebody who has never opened the photographs screen has no loader and should be
     * left with none.
     */
    @Volatile
    private var loader: ImageLoader? = null

    /**
     * Give the pictures back when the phone needs the room.
     *
     * ⚠️ **The whole point of this app is that it runs on a cheap phone, and nothing here released
     * anything.** `Application` implements `ComponentCallbacks2` already — confirmed against the
     * platform class rather than recalled — so the callback was arriving and being ignored, and the
     * thumbnail cache stayed held whatever else the system was trying to do. It is a small cache by
     * design (six per cent of the heap, ~11 MB on a 4 GB phone), which is the reason to hand it back
     * rather than a reason not to: a decoded bitmap is the largest single thing this process holds.
     *
     * ⚠️ There is deliberately nothing else to drop. The food log keeps at most four months resident
     * behind its own cap, and the barcode database is read by SQLite through its own page cache,
     * which Android already trims. Clearing either from here would be re-implementing a bound that
     * already exists.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runCatching { loader?.memoryCache?.clear() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { loader?.memoryCache?.clear() }
    }

    private companion object {
        /** See [newImageLoader] — measured against Coil's own 0.20 default and the one call site. */
        const val THUMBNAIL_HEAP_SHARE = 0.06
    }
}
