package dev.mascwa.pulse.data.media

import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.core.telemetry.MediaResolution
import dev.mascwa.pulse.data.python.PythonRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns a page address into something playable, by asking the bundled extractor.
 *
 * ⚠️ **THE CLASSIFICATION IS PYTHON'S AND IS NOT REDONE HERE.** yt-dlp's exception types are the only
 * thing that reliably separates "this is private" from "I do not support that site" from "the network
 * failed", and those types do not survive the JNI boundary — by the time a message string reaches
 * Kotlin the type that distinguished them is gone. So `lcars_extract` names the reason and this layer
 * maps the name onto [MediaResolution.Reason] and nothing else. An unrecognised name becomes
 * [MediaResolution.Reason.FAILED] rather than throwing: a future extractor that grows a reason we
 * have not heard of should degrade to "could not resolve that", not crash.
 *
 * ⚠️ **Resolved addresses are cached, and the cache is keyed on freshness rather than on time.**
 * [MediaItem.isFresh] is the whole point of [MediaItem.expiresAtMs]: a signed stream URL dies within
 * hours and a cached item that *looks* valid fails at playback with a network error that suggests
 * nothing. An entry that is no longer fresh is re-resolved, and an item that never stated an expiry
 * is never cached at all, because [MediaItem.isFresh] treats an unknown expiry as already stale.
 *
 * ⚠️ Extraction is against some sites' terms of service. This is a private, sideloaded, single-user
 * application and its owner has authorised it; that is said plainly rather than papered over.
 *
 * ⚠️ Nothing on this build machine has ever resolved an address — Python runs on the phone, not in
 * CI. The JSON contract, the reason mapping and the caching are testable; whether a given site
 * extracts is owner-verify.
 */
class MediaExtractor(private val python: PythonRuntime) {

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, MediaItem>()

    /**
     * Resolve [url] to a playable item.
     *
     * Returns a cached item when one is still fresh, so pressing play again on something already
     * resolved costs nothing. Suspends: extraction is a network round trip inside an interpreter and
     * takes seconds, not milliseconds.
     */
    suspend fun resolve(url: String, nowMs: Long = System.currentTimeMillis()): MediaResolution {
        val key = url.trim()
        if (key.isEmpty()) {
            return MediaResolution.Refused(MediaResolution.Reason.UNSUPPORTED, "No address.")
        }
        mutex.withLock { cache[key] }
            ?.takeIf { it.isFresh(nowMs) }
            ?.let { return MediaResolution.Ready(it) }

        val raw = python.callString(MODULE, "resolve", key)
            ?: return MediaResolution.Refused(
                MediaResolution.Reason.UNAVAILABLE,
                python.unavailableReason ?: python.lastError.orEmpty(),
            )

        val wire = runCatching { json.decodeFromString(Wire.serializer(), raw) }.getOrElse {
            // The extractor is contracted to return JSON on every path, so this is a bug rather than
            // a routine failure — reported as one instead of being folded into "blocked".
            return MediaResolution.Refused(
                MediaResolution.Reason.FAILED,
                "The extractor returned something unreadable.",
            )
        }
        if (wire.kind != null) {
            return MediaResolution.Refused(reasonOf(wire.kind), wire.error.orEmpty(), wire.notes)
        }

        val item = MediaItem(
            id = wire.id,
            title = wire.title,
            // Negative or absurd durations are treated as "not stated", which is what the model's own
            // contract says the field means. A live stream has no meaningful duration either.
            durationS = wire.duration.takeIf { it.isFinite() && it > 0 } ?: 0.0,
            streamUrl = wire.stream,
            audioUrl = wire.audio,
            streamHeaders = wire.stream_headers,
            audioHeaders = wire.audio_headers,
            isAdaptive = wire.adaptive,
            uploader = wire.uploader,
            thumbnailUrl = wire.thumbnail,
            pageUrl = wire.page.ifBlank { key },
            expiresAtMs = wire.expires,
            isLive = wire.live,
            notes = wire.notes,
        )
        if (!item.isResolved) {
            return MediaResolution.Refused(
                MediaResolution.Reason.NO_STREAM,
                "Nothing playable in the result.",
                wire.notes,
            )
        }
        // ⚠️ Only cached when the address states when it dies. Caching an unknown-expiry item would
        // store something `isFresh` can never approve, so it would occupy a slot and never be used.
        if (item.expiresAtMs > 0) {
            mutex.withLock {
                cache[key] = item
                while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
            }
        }
        return MediaResolution.Ready(item)
    }

    /**
     * The source's own id for [url], without resolving a stream.
     *
     * ⚠️ Worth a separate call because the skip database is keyed on it and a lookup should not need
     * a full extraction — that is a network round trip which can fail for reasons unrelated to
     * knowing which video this is. Blank when the address is not one we can key.
     */
    suspend fun videoId(url: String): String =
        python.callString(MODULE, "video_id", url.trim())?.trim().orEmpty()

    /**
     * Forget the cached address for one page, so the next [resolve] genuinely re-resolves.
     *
     * ⚠️ For the case the freshness check cannot catch: an address the source refuses *before* its
     * stated expiry. [MediaItem.isFresh] believes what the extractor said about lifetime, and a
     * signed URL can be invalidated early — so when playback comes back with a bad HTTP status, the
     * entry has to be thrown out explicitly or the retry would be handed the same dead address it
     * just failed on.
     */
    suspend fun evict(url: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        mutex.withLock { cache.remove(key) }
    }

    /** Forget every cached address, e.g. when the user wipes state. */
    suspend fun clear() {
        mutex.withLock { cache.clear() }
    }

    /**
     * The wire shape of `lcars_extract.resolve`.
     *
     * Every field is defaulted so a success and a refusal decode through the same type — the two
     * share no keys, and branching on [kind] afterwards is simpler than two serializers and a
     * discriminator the Python side would have to carry.
     */
    @Serializable
    private data class Wire(
        val id: String = "",
        val title: String = "",
        val duration: Double = 0.0,
        val stream: String = "",
        val audio: String = "",
        /**
         * The headers each address must be fetched with, as the extractor reported them.
         *
         * ⚠️ Snake case because these are the Python side's own key names, and the whole point of
         * this type is to be the literal wire shape rather than a translated one. Defaulted like
         * every other field, so an older extractor that does not send them still decodes — it just
         * yields a `MediaItem` with no headers, which is exactly the behaviour that was there before.
         */
        val stream_headers: Map<String, String> = emptyMap(),
        val audio_headers: Map<String, String> = emptyMap(),
        /** True when [stream] is video-only and [audio] is its other half. See `MediaItem.isAdaptive`. */
        val adaptive: Boolean = false,
    /**
     * Whatever yt-dlp said on the way, addresses already removed on the Python side.
     *
     * ⚠️ Present on SUCCESS as well as failure. "No JavaScript runtime, formats may be missing"
     * arrives on a resolve that otherwise looks fine, and is the sentence that explains a refusal
     * several seconds later.
     */
    val notes: List<String> = emptyList(),
        val uploader: String = "",
        val thumbnail: String = "",
        val page: String = "",
        val expires: Long = 0,
        val live: Boolean = false,
        val extractor: String = "",
        /** Present only on a refusal. Its presence is what makes this a refusal. */
        val kind: String? = null,
        val error: String? = null,
    )

    private companion object {
        const val MODULE = "lcars_extract"

        /**
         * How many resolved addresses to remember.
         *
         * Small on purpose: entries expire within hours anyway, and the value of the cache is
         * re-pressing play on the thing in front of you, not a viewing history.
         */
        const val MAX_CACHED = 16

        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        fun reasonOf(kind: String): MediaResolution.Reason = when (kind.uppercase()) {
            "UNSUPPORTED" -> MediaResolution.Reason.UNSUPPORTED
            "BLOCKED" -> MediaResolution.Reason.BLOCKED
            "NO_STREAM" -> MediaResolution.Reason.NO_STREAM
            "UNAVAILABLE" -> MediaResolution.Reason.UNAVAILABLE
            // Including the literal "FAILED", and anything a future extractor invents.
            else -> MediaResolution.Reason.FAILED
        }
    }
}
