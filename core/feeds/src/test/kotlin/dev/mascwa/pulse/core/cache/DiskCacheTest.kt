package dev.mascwa.pulse.core.cache

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The bound on the feed cache's size.
 *
 * ⚠️ **This exists because until now nothing bounded it, and the thing that appeared to was an
 * accident.** `PulseApplication.onTrimMemory` wiped the whole directory whenever the app was
 * backgrounded — a memory signal deleting a disk store — and removing that (which is right, it
 * cannot relieve RAM pressure and it destroys the offline fallback every screen depends on) would
 * have left the directory growing without limit on the phone least able to afford it. Most keys are
 * fixed and simply overwrite, but `off_search_<query>`, `off_product_<barcode>`,
 * `weather_<lat>_<lon>` and the routing and places keys mint a new file every time.
 *
 * These run on a real temporary directory rather than a fake filesystem: the rule is expressed in
 * `File.length()` and `File.lastModified()`, and a stub of those would be pinning my model of the
 * filesystem rather than the filesystem.
 */
class DiskCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache(): DiskCache = DiskCache(tmp.root, Json)

    /** The directory [DiskCache] resolves under the root it was handed. */
    private fun cacheDir(): File = File(tmp.root, "pulse_cache")

    /**
     * A payload whose encoded file lands close to [bytes].
     *
     * The envelope adds roughly forty characters of framing around the value, which is noise beside
     * the sizes here — every assertion below has far more headroom than that.
     */
    private fun payload(bytes: Int): String = "a".repeat(bytes)

    private suspend fun DiskCache.put(key: String, value: String) =
        write(key, value, String.serializer())

    private suspend fun DiskCache.get(key: String): String? =
        readAny(key, String.serializer())?.value

    @Test
    fun `a cache over the ceiling is cut back below it`() = runBlocking {
        val c = cache()
        // 32 * ~300 kB = ~9.6 MB, comfortably past the 8 MB ceiling. The 32nd write is the one that
        // reaches PRUNE_EVERY and so the one that prunes.
        repeat(DiskCache.PRUNE_EVERY) { c.put("k$it", payload(300_000)) }

        val after = c.sizeBytes()
        assertTrue(
            "a cache pruned to $after bytes is still over the ${DiskCache.MAX_BYTES} ceiling",
            after <= DiskCache.MAX_BYTES,
        )
        // And down to the target, not merely under the ceiling — see the hysteresis test below.
        assertTrue(
            "pruning must cut back to the target, not stop at the ceiling (left $after)",
            after <= DiskCache.TARGET_BYTES,
        )
    }

    @Test
    fun `an entry the prune deleted simply reads back as absent`() = runBlocking {
        val c = cache()
        repeat(DiskCache.PRUNE_EVERY) { c.put("k$it", payload(300_000)) }

        // Something must have gone — the writes totalled ~9.6 MB and the target is 6 MB.
        val survivors = (0 until DiskCache.PRUNE_EVERY).count { c.get("k$it") != null }
        assertTrue("nothing was evicted at all ($survivors of ${DiskCache.PRUNE_EVERY} survive)",
            survivors < DiskCache.PRUNE_EVERY)
        // Whatever went, went cleanly: a missing entry is a null and the caller refetches. Nothing
        // here is a system of record, which is what makes an approximate bound the right bound.
        assertTrue("some entries must survive a prune", survivors > 0)
    }

    @Test
    fun `the least recently written go first`() = runBlocking {
        val c = cache()
        repeat(DiskCache.PRUNE_EVERY - 1) { c.put("old$it", payload(300_000)) }

        // Age every file written so far. Explicit rather than relying on write order: File.lastModified
        // has millisecond resolution and several writes can land inside one, which would leave the
        // ordering under test decided by whatever order the filesystem happened to list the directory.
        val aged = cacheDir().listFiles().orEmpty().count { it.setLastModified(1_000_000L) }
        assertEquals(
            "the fixture aged no files, so this would pass on write order alone and prove nothing",
            DiskCache.PRUNE_EVERY - 1, aged,
        )

        // The 32nd write both triggers the prune and is unambiguously the newest thing in the directory.
        c.put("newest", payload(300_000))

        assertNotNull("the newest entry must survive a prune", c.get("newest"))
        val survivors = (0 until DiskCache.PRUNE_EVERY - 1).count { c.get("old$it") != null }
        assertTrue(
            "the older entries must be the ones evicted (all $survivors survived)",
            survivors < DiskCache.PRUNE_EVERY - 1,
        )
    }

    @Test
    fun `a cache between the target and the ceiling is left alone`() = runBlocking {
        val c = cache()
        // 32 * ~220 kB = ~7.0 MB: past the 6 MB target, short of the 8 MB ceiling. The gap between
        // the two is the whole point of having two numbers — pruning down to exactly the ceiling
        // would put the cache one write over it again immediately, so every later prune would list
        // and stat the whole directory to delete a single file.
        repeat(DiskCache.PRUNE_EVERY) { c.put("k$it", payload(220_000)) }

        val size = c.sizeBytes()
        assertTrue("the fixture must land inside the hysteresis band, not below it (was $size)",
            size > DiskCache.TARGET_BYTES)
        assertTrue("the fixture must land inside the hysteresis band, not above it (was $size)",
            size <= DiskCache.MAX_BYTES)
        val survivors = (0 until DiskCache.PRUNE_EVERY).count { c.get("k$it") != null }
        assertEquals(
            "nothing may be evicted while the cache is under the ceiling",
            DiskCache.PRUNE_EVERY, survivors,
        )
    }

    @Test
    fun `the size is checked every so often, not on every write`() = runBlocking {
        val c = cache()
        // One short of the threshold, and already past the ceiling.
        repeat(DiskCache.PRUNE_EVERY - 1) { c.put("k$it", payload(300_000)) }

        val size = c.sizeBytes()
        assertTrue("the fixture must exceed the ceiling for this to mean anything (was $size)",
            size > DiskCache.MAX_BYTES)
        assertEquals(
            "pruning before the threshold would pay a full directory listing on every write, and " +
                "most writes overwrite an entry that already exists so the size does not move",
            DiskCache.PRUNE_EVERY - 1,
            (0 until DiskCache.PRUNE_EVERY - 1).count { c.get("k$it") != null },
        )
    }

    @Test
    fun `an overwritten key keeps one file, so fixed keys never accumulate`() = runBlocking {
        val c = cache()
        repeat(50) { c.put("steady", payload(1_000)) }

        assertEquals("a repeated key must reuse its file", 1, cacheDir().listFiles()!!.size)
        assertNotNull(c.get("steady"))
    }

    @Test
    fun `a small cache is untouched however many times it is written`() = runBlocking {
        val c = cache()
        repeat(DiskCache.PRUNE_EVERY * 2) { c.put("k$it", payload(1_000)) }

        assertEquals(
            "nothing may be evicted from a cache nowhere near the ceiling",
            DiskCache.PRUNE_EVERY * 2,
            (0 until DiskCache.PRUNE_EVERY * 2).count { c.get("k$it") != null },
        )
    }

    @Test
    fun `reading is unaffected by the bound`() = runBlocking {
        val c = cache()
        c.put("k", "hello")
        assertEquals("hello", c.get("k"))
        assertNotNull(c.read("k", maxAgeMs = 60_000L, String.serializer()))
        // ⚠️ Deliberately not asserting the stale side with `maxAgeMs = 0`: the comparison is
        // `<= maxAgeMs`, so an entry written and read inside one millisecond is fresh by that rule
        // and the assertion would fail on a fast machine and pass on a slow one.
        assertNull("a key never written must read as absent", c.get("never written"))
    }
}
