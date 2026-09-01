package dev.mascwa.pulse.core.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * A simple, robust JSON-on-disk cache with per-entry TTL. Used to give every
 * screen instant content offline and to fall back to the last good payload
 * when the network is down. No Room / annotation processors required.
 *
 * ⚠️ Takes a directory rather than an Android `Context`, and that one change is what let this whole
 * module become shared. The `Context` was only ever read for `filesDir`, so it was the single Android
 * dependency standing between sixteen repositories and both applications being able to use them. Each
 * application resolves its own root — `filesDir` on the phone, the OS app-data directory on the desktop —
 * and hands it in.
 */
class DiskCache(
    root: File,
    private val json: Json,
) {
    /**
     * ⚠️ **Lazy, so constructing a cache touches no disk.** As an initialiser the `mkdirs()` ran on
     * whoever built the object, and on the phone that is the dependency graph being forced from an
     * activity's `onStart` — a filesystem call on the frame thread for a cache nothing has asked to
     * read yet. Every real use of this goes through `fileFor`, which is only ever reached inside a
     * `withContext(Dispatchers.IO)` below, so deferring it moves the syscall to a thread that is
     * already doing file work. `by lazy` is synchronized, which costs an uncontended monitor next to
     * a file read.
     */
    private val dir: File by lazy { File(root, "pulse_cache").apply { mkdirs() } }
    private val mutex = Mutex()

    @Serializable
    private data class Envelope(val savedAtMs: Long, val payload: String)

    /**
     * How many writes may pass before the cache checks its own size.
     *
     * ⚠️ Not every write, because pruning lists and stats every file and most writes overwrite an
     * entry that already exists — the size does not move. Not never, either: several keys grow
     * without bound (one entry per food search, per scanned barcode, per weather coordinate at
     * ~110 m, per route), so something has to notice. Thirty-two is far below what it takes to
     * overshoot [MAX_BYTES] by anything that matters and far above per-write cost.
     */
    private var writesSincePrune = 0

    suspend fun <T> write(key: String, value: T, serializer: KSerializer<T>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val payload = json.encodeToString(serializer, value)
                val env = Envelope(System.currentTimeMillis(), payload)
                fileFor(key).writeText(json.encodeToString(Envelope.serializer(), env))
                if (++writesSincePrune >= PRUNE_EVERY) {
                    writesSincePrune = 0
                    pruneLocked()
                }
            }
        }

    /**
     * Keep the cache under [MAX_BYTES] by dropping the least recently written entries.
     *
     * ⚠️ **This is here because something has to bound it, and until now the only thing that did
     * was an unrelated accident.** `PulseApplication.onTrimMemory` wiped the whole cache whenever
     * the app was backgrounded — a memory signal deleting a disk store, which cannot relieve the
     * pressure it was reacting to and destroys the offline fallback every screen depends on. Taking
     * that out without putting a real bound in its place would have traded one defect for another
     * on the phone least able to afford it: most keys are fixed and simply overwrite, but
     * `off_search_<query>`, `off_product_<barcode>`, `weather_<lat>_<lon>` and the routing and
     * places keys mint a new file every time, so the directory genuinely grows without limit.
     *
     * ⚠️ Ordered by `lastModified` rather than the envelope's own `savedAtMs`, which would mean
     * opening and parsing every file to decide what to delete — the expensive way to do the cheap
     * thing. They agree: the file is written exactly when the envelope is stamped.
     *
     * ⚠️ Deleting an entry is always safe. `readAny` answers null and the caller refetches; nothing
     * here is a system of record. That is what makes an approximate bound the right kind of bound.
     */
    private fun pruneLocked() {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= TARGET_BYTES) return
            val size = f.length()
            if (f.delete()) total -= size
        }
    }

    /** Reads only if the entry is fresher than [maxAgeMs]; otherwise null. */
    suspend fun <T> read(key: String, maxAgeMs: Long, serializer: KSerializer<T>): Cached<T>? =
        readAny(key, serializer)?.takeIf {
            System.currentTimeMillis() - it.savedAtMs <= maxAgeMs
        }

    /** Reads regardless of age (used as offline fallback). */
    suspend fun <T> readAny(key: String, serializer: KSerializer<T>): Cached<T>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = fileFor(key)
                if (!f.exists()) return@withContext null
                try {
                    val env = json.decodeFromString(Envelope.serializer(), f.readText())
                    val value = json.decodeFromString(serializer, env.payload)
                    Cached(value, env.savedAtMs)
                } catch (_: Exception) {
                    f.delete()
                    null
                }
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { dir.listFiles()?.forEach { it.delete() } }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun fileFor(key: String): File {
        val md = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = md.joinToString("") { "%02x".format(it) }.take(40)
        return File(dir, "$name.json")
    }

    data class Cached<T>(val value: T, val savedAtMs: Long)

    companion object {
        /** See [writesSincePrune]. */
        internal const val PRUNE_EVERY = 32

        /**
         * The size above which [pruneLocked] starts deleting, and the size it stops at.
         *
         * ⚠️ **Two numbers rather than one, and the gap is the point.** Pruning down to exactly the
         * ceiling would leave the cache one write over it again immediately, so the next prune —
         * and every prune after that — would list and stat every file to delete a single entry.
         * Cutting back to [TARGET_BYTES] buys 2 MB of headroom, which at the size of a typical
         * envelope is hundreds of writes before the cache is anywhere near the ceiling again.
         *
         * ⚠️ 8 MB because this store holds JSON, not media. The largest single payload measured is
         * a weather forecast at roughly 90 kB; most are a few kB. So the ceiling is generous for
         * every fixed key the app has — the whole point is the handful of keys that mint a new file
         * per query, and those are small and numerous, which is exactly what a size cap handles well
         * and a count cap would not.
         */
        internal const val MAX_BYTES = 8L * 1024 * 1024
        internal const val TARGET_BYTES = 6L * 1024 * 1024
    }
}
