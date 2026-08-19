package dev.mascwa.pulse.data.media

import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.core.telemetry.MediaResolution
import dev.mascwa.pulse.data.python.PythonRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lists videos without resolving any of them — the browse half of the Theater.
 *
 * ⚠️ **A listing is CHEAP and a resolve is EXPENSIVE, and keeping them apart is the design.** One
 * search is one network round trip inside the embedded interpreter and returns flat entries — id,
 * title, duration, thumbnail, page URL — with NO stream address. Resolving a stream (the expensive,
 * bot-check-exposed step) happens later, for exactly the one item that gets tapped, through
 * [MediaExtractor.resolve], whose cache is built for that shape. Pushing a whole shelf through
 * resolve() would be N interpreter round trips for N items nobody may ever tap.
 *
 * Entries map to [MediaItem]s with blank stream/audio URLs — `isResolved == false` — which is the
 * model's own two-phase contract: not playable until a second resolve of [MediaItem.pageUrl].
 *
 * ⚠️ Search queries are viewing history. They are never logged on this side either — the Python
 * side's _Silent logger covers stderr, and this class writes nothing anywhere.
 */
class MediaBrowser(private val python: PythonRuntime) {

    /** What a listing attempt produced. Mirrors [MediaResolution]'s honest-refusal shape. */
    sealed interface Browse {
        data class Page(val items: List<MediaItem>) : Browse
        data class Refused(val reason: MediaResolution.Reason, val detail: String = "") : Browse
    }

    private val mutex = Mutex()
    private val memo = LinkedHashMap<String, Memo>()

    private data class Memo(val atMs: Long, val page: Browse.Page)

    /**
     * Search for videos. [limit] is clamped Python-side; pass what the shelf wants to show.
     *
     * Successful pages are memoised for [MEMO_MS] — shelf queries are fixed strings asked on every
     * screen open, and a listing does not go stale the way a signed stream URL does. Refusals are
     * never memoised: a transient failure should not stick to a shelf for ten minutes.
     */
    suspend fun search(query: String, limit: Int = 25, nowMs: Long = System.currentTimeMillis()): Browse {
        val q = query.trim()
        if (q.isEmpty()) return Browse.Refused(MediaResolution.Reason.UNSUPPORTED, "No query.")
        val key = "$limit:$q"
        mutex.withLock { memo[key]?.takeIf { nowMs - it.atMs < MEMO_MS } }?.let { return it.page }

        val raw = python.callString(MODULE, "search", q, limit.toString())
            ?: return Browse.Refused(
                MediaResolution.Reason.UNAVAILABLE,
                python.unavailableReason ?: python.lastError.orEmpty(),
            )
        val wire = runCatching { json.decodeFromString(WireList.serializer(), raw) }.getOrElse {
            return Browse.Refused(
                MediaResolution.Reason.FAILED,
                "The listing returned something unreadable.",
            )
        }
        if (wire.kind != null) return Browse.Refused(reasonOf(wire.kind), wire.error.orEmpty())

        val items = wire.items.mapNotNull { e ->
            if (e.page.isBlank() || e.title.isBlank()) return@mapNotNull null
            MediaItem(
                id = e.id,
                title = e.title,
                durationS = e.duration.takeIf { it.isFinite() && it > 0 } ?: 0.0,
                streamUrl = "",
                audioUrl = "",
                uploader = e.uploader,
                thumbnailUrl = e.thumbnail,
                pageUrl = e.page,
                isLive = e.live,
            )
        }
        if (items.isEmpty()) {
            return Browse.Refused(MediaResolution.Reason.NO_STREAM, "Nothing listable came back.")
        }
        val page = Browse.Page(items)
        mutex.withLock {
            memo[key] = Memo(nowMs, page)
            while (memo.size > MAX_MEMOS) memo.remove(memo.keys.first())
        }
        return page
    }

    /** Forget every memoised page. */
    suspend fun clear() {
        mutex.withLock { memo.clear() }
    }

    /**
     * The wire shape of `lcars_extract.search`. Every field defaulted so a success page and a
     * refusal decode through one serializer — the [MediaExtractor.Wire] convention.
     */
    @Serializable
    private data class WireEntry(
        val id: String = "",
        val title: String = "",
        val duration: Double = 0.0,
        val uploader: String = "",
        val thumbnail: String = "",
        val page: String = "",
        val live: Boolean = false,
        val views: Long = 0,
    )

    @Serializable
    private data class WireList(
        val items: List<WireEntry> = emptyList(),
        /** Present only on a refusal; its presence is what makes this a refusal. */
        val kind: String? = null,
        val error: String? = null,
    )

    private companion object {
        const val MODULE = "lcars_extract"

        /**
         * How long a listing stays good. Shelf queries are fixed strings re-asked on every screen
         * open; ten minutes keeps a session's reopens free while picking up genuinely new uploads.
         */
        const val MEMO_MS = 10 * 60 * 1000L

        /** A handful of shelves plus a few typed searches — small on purpose. */
        const val MAX_MEMOS = 12

        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun reasonOf(kind: String): MediaResolution.Reason = when (kind.uppercase()) {
            "UNSUPPORTED" -> MediaResolution.Reason.UNSUPPORTED
            "BLOCKED" -> MediaResolution.Reason.BLOCKED
            "NO_STREAM" -> MediaResolution.Reason.NO_STREAM
            "UNAVAILABLE" -> MediaResolution.Reason.UNAVAILABLE
            else -> MediaResolution.Reason.FAILED
        }
    }
}
