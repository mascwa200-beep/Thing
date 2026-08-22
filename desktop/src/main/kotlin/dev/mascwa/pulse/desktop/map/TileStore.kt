package dev.mascwa.pulse.desktop.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.WebMercator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Map tiles: fetch, decode, remember, and stop asking for the ones nobody is looking at any more.
 *
 * ⚠️ **Its own [HttpClient], with its own bounded cache, deliberately.** A single 4K view is around
 * 250 tiles; sharing the feeds' 16 MB cache would evict the news, the weather and the market quotes
 * on every pan. Nothing would break — those screens would simply refetch everything, every time,
 * forever — which is the kind of waste that never announces itself.
 *
 * ⚠️ **The decoded cache is bounded by COUNT, not by bytes, and the count is the thing to be careful
 * with.** A decoded 256-pixel tile is 256 KB of pixels regardless of how few kilobytes it arrived as,
 * so [MEMORY_TILES] is about 64 MB and raising it to something that sounds generous is how a map
 * quietly becomes the largest thing in the process.
 */
class TileStore(
    private val scope: CoroutineScope,
    cacheDir: File,
) {

    private val http = HttpClient.create(
        json = Json { ignoreUnknownKeys = true },
        cacheDir = File(cacheDir, "tiles").apply { runCatching { mkdirs() } },
        cacheBytes = DISK_CACHE_BYTES,
    )

    /**
     * Decoded tiles, newest use last — a plain access-ordered map is the whole LRU.
     *
     * Touched only from the scope's dispatcher (a single thread in practice) plus [get] on the
     * composition, so it is synchronised rather than assumed safe.
     */
    private val memory = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) =
            size > MEMORY_TILES
    }

    /** Bumped whenever a tile lands, so a canvas watching this redraws with what has arrived. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** In flight, so the same tile is never asked for twice while the first request is outstanding. */
    private val pending = HashSet<String>()

    /**
     * ⚠️ Four at a time.
     *
     * Not politeness for its own sake: OpenTopoMap and the OSM Foundation both ask that clients not
     * hammer them, and this container has already had one host durably refuse it after an unbounded
     * burst. A pan that touches 250 tiles would issue 250 simultaneous requests without this.
     */
    private val gate = Semaphore(4)

    /** A tile that failed, so a dead source is not retried on every single frame. */
    private val failed = HashSet<String>()

    private var job: Job? = null

    /**
     * The tile if it is already decoded, else null and a request goes out.
     *
     * Called from the draw pass, so it never blocks and never decodes: a miss draws nothing this
     * frame and [revision] moves when the tile is ready.
     */
    fun get(template: String, t: WebMercator.Placed): ImageBitmap? {
        val key = "$template|${t.key}"
        synchronized(memory) { memory[key] }?.let { return it }
        request(template, t, key)
        return null
    }

    private fun request(template: String, t: WebMercator.Placed, key: String) {
        synchronized(pending) {
            if (key in failed || !pending.add(key)) return
        }
        scope.launch {
            val bitmap = runCatching {
                gate.withPermit {
                    val bytes = http.getBytes(WebMercator.url(template, t), maxBytes = MAX_TILE_BYTES)
                    // An error page is a perfectly valid 200 on some tile hosts, and an empty body is
                    // not a picture. Decoding either throws, which is caught below — but checking
                    // first keeps a whole screen of them out of the exception path.
                    if (bytes.size < 64) null
                    else ByteArrayInputStream(bytes).use { loadImageBitmap(it) }
                }
            }.getOrNull()

            synchronized(pending) { pending.remove(key) }
            if (bitmap == null) {
                synchronized(pending) { failed.add(key) }
                return@launch
            }
            synchronized(memory) { memory[key] = bitmap }
            _revision.value = _revision.value + 1
        }
    }

    /**
     * Forget every failure, so a source that was unreachable can be tried again.
     *
     * ⚠️ Deliberately manual. A map that retried a dead host on every frame would issue thousands of
     * doomed requests a minute; a map that never retried would stay blank until the app restarted.
     * A refresh button is the thing that resolves that, and it is a person deciding rather than a timer.
     */
    fun retryFailures() {
        synchronized(pending) { failed.clear() }
        _revision.value = _revision.value + 1
    }

    fun clear() {
        job?.cancel()
        synchronized(memory) { memory.clear() }
        synchronized(pending) { failed.clear() }
        _revision.value = _revision.value + 1
    }

    companion object {
        /** About 64 MB of pixels — see the class note before raising it. */
        const val MEMORY_TILES = 256

        /**
         * ⚠️ Bounded, and modest on purpose. The owner asked that this program not eat disk it does
         * not need; 96 MB is a couple of cities' worth of tiles and nothing at all beside a game.
         */
        const val DISK_CACHE_BYTES = 96L * 1024 * 1024

        /** A tile is tens of kilobytes. Past this it is an error page or a mistake. */
        const val MAX_TILE_BYTES = 2L * 1024 * 1024
    }
}
