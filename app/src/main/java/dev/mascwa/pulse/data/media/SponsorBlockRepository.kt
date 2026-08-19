package dev.mascwa.pulse.data.media

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.SponsorSegments
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * What the community says is worth skipping in a video.
 *
 * ⚠️ **THE SERVER IS NEVER TOLD WHICH VIDEO YOU ARE WATCHING.** The obvious endpoint takes a video
 * id; this one takes the first four hex characters of its SHA-256 and returns every video sharing
 * that prefix, which the client then filters. Measured on a real prefix: **156 videos came back**,
 * so a request says only "one of these 156", and which one never leaves the device. That is the
 * whole reason the hash endpoint exists and there is no reason to use the other one.
 *
 * ⚠️ **EVERY category is requested and the user's choice is applied LOCALLY**, which is not merely
 * tidier — it is required. Filtering is server-side and a video with nothing in the requested
 * categories is **omitted from the response entirely**: the same prefix returned 65 videos for
 * `sponsor` alone and 156 for the full set, and a video whose only segment was an intro was simply
 * absent from the first. Requesting the user's categories would therefore make the cache
 * policy-specific, so toggling one category off and on again would cost two fetches, and a video
 * would appear to have no data at all rather than no data *of that kind*.
 *
 * The decisions — which segments are usable, how overlaps merge, where a skip lands — all live in
 * the CI-tested [SponsorSegments]. This layer fetches, caches and maps.
 *
 * ⚠️ Verified only as far as it can be from here: the endpoint's shape, its privacy behaviour and
 * the category semantics above were all measured against the live service, but nothing on this
 * device has ever played a video. Playback is owner-verify.
 */
class SponsorBlockRepository(private val http: HttpClient) {

    @Serializable
    private data class PrefixRow(
        val videoID: String = "",
        val segments: List<WireSegment> = emptyList(),
    )

    @Serializable
    private data class WireSegment(
        @SerialName("UUID") val uuid: String = "",
        val category: String = "",
        val actionType: String = "",
        /** [start, end] in seconds. Always two elements in practice; treated defensively anyway. */
        val segment: List<Double> = emptyList(),
        val votes: Int = 0,
        /** 1 when a moderator has confirmed it. Integer on the wire, not a boolean. */
        val locked: Int = 0,
        val description: String = "",
    )

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, Cached>()

    private data class Cached(val atMs: Long, val byVideo: Map<String, List<SponsorSegments.Segment>>)

    /**
     * Everything the database holds for [videoId], unfiltered.
     *
     * The caller applies its own [SponsorSegments.Policy] — usually via
     * [SponsorSegments.usable] — so the same fetch serves any category preference.
     *
     * Returns empty for an unknown video, a network failure, or a malformed response. That is not a
     * silent swallow: nothing to skip and could-not-ask are the same outcome for playback, which
     * simply plays the whole video, and there is no user-facing decision either would change.
     */
    suspend fun segments(videoId: String, nowMs: Long = System.currentTimeMillis()): List<SponsorSegments.Segment> {
        if (videoId.isBlank()) return emptyList()
        val prefix = hashPrefix(videoId)
        val hit = mutex.withLock {
            cache[prefix]?.takeIf { nowMs - it.atMs < CACHE_MS }
        }
        if (hit != null) return hit.byVideo[videoId].orEmpty()

        val rows = runCatching {
            http.getJson(
                "https://sponsor.ajay.app/api/skipSegments/$prefix?categories=$ALL_CATEGORIES",
                kotlinx.serialization.builtins.ListSerializer(PrefixRow.serializer()),
            )
        }.getOrElse { return emptyList() }

        val byVideo = rows.filter { it.videoID.isNotBlank() }.associate { row ->
            row.videoID to row.segments.mapNotNull { it.toCore() }
        }
        mutex.withLock {
            cache[prefix] = Cached(nowMs, byVideo)
            // ⚠️ Bounded, because one prefix response is ~150 videos of parsed segments and a long
            // listening session would otherwise accumulate every prefix ever asked for. Oldest out.
            while (cache.size > MAX_PREFIXES) {
                cache.remove(cache.keys.first())
            }
        }
        return byVideo[videoId].orEmpty()
    }

    private fun WireSegment.toCore(): SponsorSegments.Segment? {
        if (segment.size < 2) return null
        return SponsorSegments.Segment(
            uuid = uuid,
            category = SponsorSegments.categoryOf(category),
            action = SponsorSegments.actionOf(actionType),
            startS = segment[0],
            endS = segment[1],
            votes = votes,
            locked = locked != 0,
            description = description,
        )
    }

    /**
     * The first [PREFIX_CHARS] hex characters of SHA-256(videoId), lowercase.
     *
     * ⚠️ Hashed over the raw id's UTF-8 bytes, which is what the service hashes — a normalisation or
     * a different case would produce a prefix that simply never matches, and the failure would look
     * exactly like "this video has no segments". Confirmed against the service by hashing a real id
     * and finding the server's own `hash` field identical.
     */
    private fun hashPrefix(videoId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(videoId.toByteArray(Charsets.UTF_8))
        return digest.take((PREFIX_CHARS + 1) / 2)
            .joinToString("") { "%02x".format(it) }
            .take(PREFIX_CHARS)
    }

    companion object {
        /**
         * How much of the hash to send.
         *
         * Four is the service's documented minimum and, measured, puts about 150 videos behind each
         * request — a real anonymity set for a response of a few tens of kilobytes. Fewer characters
         * would hide the video better and cost far more bandwidth; more would narrow the set toward
         * naming it.
         */
        const val PREFIX_CHARS = 4

        /** One prefix answers ~150 videos, so this covers a long session of related viewing. */
        const val MAX_PREFIXES = 8

        /**
         * Segments change when somebody submits or votes, which is slow. An hour keeps a session's
         * repeat views free while picking up the day's edits.
         */
        const val CACHE_MS = 60 * 60 * 1000L

        /**
         * ⚠️ EVERY category, always — see the class note. Percent-encoded because it is a JSON array
         * in a query parameter, which is the service's own interface.
         */
        private const val ALL_CATEGORIES =
            "%5B%22sponsor%22,%22selfpromo%22,%22interaction%22,%22intro%22,%22outro%22," +
                "%22preview%22,%22music_offtopic%22,%22filler%22,%22exclusive_access%22%5D"
    }
}
