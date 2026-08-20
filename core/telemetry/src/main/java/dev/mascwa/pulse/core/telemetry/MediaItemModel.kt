package dev.mascwa.pulse.core.telemetry

/**
 * What an on-demand video or track is, once something has resolved it to something playable.
 *
 * Pure and platform-free so the extractor, the player and the skip policy all agree on one shape,
 * and so the parts worth reasoning about — is this still playable, how long is it, what should the
 * skip database be asked about — are testable without a device.
 *
 * ⚠️ **A resolved stream URL EXPIRES**, usually within a few hours, and that is the whole reason
 * [expiresAtMs] exists as a first-class field rather than a detail inside the extractor. A cached
 * item that looks perfectly valid and whose URL died an hour ago fails at playback with a network
 * error that says nothing useful, and the fix — re-extract — is not one the error suggests. Asking
 * [isFresh] first turns that into a re-resolve.
 */
data class MediaItem(
    /**
     * The source's own identifier, which is also the key the skip database is indexed by.
     *
     * Kept separate from [pageUrl] because they are not interchangeable: a page URL carries playlist
     * position, timestamps and tracking parameters that would make two requests for the same video
     * look like two different videos.
     */
    val id: String,
    val title: String,
    /** Seconds, or 0 when the source did not say. Never negative. */
    val durationS: Double = 0.0,
    /** Where the media actually streams from. Blank means "known about but not resolved". */
    val streamUrl: String = "",
    /** Audio-only address when the source offers one separately, for listening without the video. */
    val audioUrl: String = "",
    /**
     * The headers [streamUrl] must be fetched with.
     *
     * ⚠️ **Carrying these is not politeness, it is the difference between playing and a 403.** A
     * signed media address is minted for the client that asked for it, and a later request with a
     * different User-Agent — or missing the Accept and Sec-Fetch values the extractor sent — is
     * refused. The extractor reports exactly what it used; before this existed the app discarded it
     * and playback failed with a bad HTTP status that named no status.
     */
    val streamHeaders: Map<String, String> = emptyMap(),
    /**
     * The headers [audioUrl] must be fetched with.
     *
     * ⚠️ Separate from [streamHeaders] because they genuinely differ per track. One shared header
     * set is the obvious shortcut and it half-works, which is the worst kind of wrong.
     */
    val audioHeaders: Map<String, String> = emptyMap(),
    /**
     * True when [streamUrl] carries video ONLY and [audioUrl] is its other half, so the player has
     * to merge them.
     *
     * ⚠️ Not the same question as "are both set". A muxed stream can sit beside a separate
     * audio-only rendition — which is what LISTEN plays — and merging those would play the audio
     * twice. The extractor decides this from whether it made an adaptive selection; nothing
     * downstream should try to infer it.
     */
    val isAdaptive: Boolean = false,
    val uploader: String = "",
    val thumbnailUrl: String = "",
    /** The human-facing page, for handing off to a browser. */
    val pageUrl: String = "",
    /** Epoch ms after which [streamUrl] should be assumed dead. 0 = unknown, treated as expired. */
    val expiresAtMs: Long = 0,
    /** True when the source says this is a live stream, which has no meaningful duration or end. */
    val isLive: Boolean = false,
    /**
     * What the extractor said on its way to producing this, with every address removed.
     *
     * ⚠️ **Carried on a SUCCESSFUL resolve, which is the point.** The most valuable thing the
     * extractor has to say — "no JavaScript runtime, so some formats are missing" — arrives on a
     * resolve that otherwise looks like it worked, and only becomes interesting later, when the
     * address it handed over is refused. Keeping the notes on the item means the failure can show
     * the reason instead of a bare status code.
     *
     * Empty is the normal case. Never contains a media address — see the extractor's redaction.
     */
    val notes: List<String> = emptyList(),
) {
    val isResolved: Boolean get() = streamUrl.isNotBlank() || audioUrl.isNotBlank()

    /**
     * Whether the resolved address can still be relied on at [nowMs].
     *
     * ⚠️ An unknown expiry (0) counts as **stale**, not fresh. The optimistic reading is tempting
     * and wrong: an extractor that failed to report an expiry is exactly the case where the URL is
     * most likely to be a short-lived signed one, and re-resolving costs a second where guessing
     * wrong costs a failed playback the user has to diagnose.
     */
    fun isFresh(nowMs: Long, marginMs: Long = FRESHNESS_MARGIN_MS): Boolean =
        isResolved && expiresAtMs > 0 && nowMs + marginMs < expiresAtMs

    companion object {
        /**
         * How far ahead of the stated expiry to stop trusting a URL.
         *
         * A stream started thirty seconds before its address expires will die part-way through, and
         * the user sees a video stop rather than a video that never started — much harder to explain.
         * Two minutes is comfortably longer than any resolve takes and far shorter than the usual
         * multi-hour lifetime.
         */
        const val FRESHNESS_MARGIN_MS = 120_000L
    }
}

/**
 * Why an item could not be played, when it could not.
 *
 * ⚠️ Modelled as an outcome rather than an exception because extraction fails **routinely** — a
 * private video, a region block, an age gate, a site change — and each of those wants a different
 * sentence on screen. A thrown exception collapses them into "something went wrong", which is the
 * failure this app has now corrected in the reader, the safety feed and the interrogator.
 */
sealed interface MediaResolution {
    data class Ready(val item: MediaItem) : MediaResolution

    /**
     * Named so the surface can say what to do about it.
     *
     * [notes] is whatever the extractor said on the way down, addresses removed — the difference
     * between "could not resolve that right now" and a sentence naming the actual obstacle.
     */
    data class Refused(
        val reason: Reason,
        val detail: String = "",
        val notes: List<String> = emptyList(),
    ) : MediaResolution

    enum class Reason {
        /** The address was not one this app knows how to resolve. */
        UNSUPPORTED,

        /** The source says no: private, removed, region-locked, age-gated. */
        BLOCKED,

        /** The extractor ran and produced nothing playable. */
        NO_STREAM,

        /** The network or the extractor itself failed. Retrying may work. */
        FAILED,

        /** Python, or the extractor inside it, is not available in this build. */
        UNAVAILABLE,
    }

    companion object {
        fun say(reason: Reason): String = when (reason) {
            Reason.UNSUPPORTED -> "That link is not one I can play."
            Reason.BLOCKED -> "The source refused it — it may be private, removed, or blocked here."
            Reason.NO_STREAM -> "Nothing playable came back for that."
            Reason.FAILED -> "Could not resolve that right now."
            Reason.UNAVAILABLE -> "On-demand playback is not available in this build."
        }
    }
}
