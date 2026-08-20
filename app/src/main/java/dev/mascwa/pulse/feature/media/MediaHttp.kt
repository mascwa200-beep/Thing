package dev.mascwa.pulse.feature.media

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * The identity the app's media players present, shared by the radio and the live-video feed.
 *
 * A browser User-Agent rather than the app's own descriptive one (`PulseApp/1.0 …`, which every
 * API request carries). Media CDNs are a different world from JSON endpoints: StreamTheWorld,
 * Triton and iHeart refuse the default player UA outright, so the stream simply never opens. That
 * was found the hard way on the radio and is the reason this string exists at all.
 *
 * Kept in one place because both players want exactly the same value, and a UA that drifts between
 * two callers is a class of bug that only shows up as one of them mysteriously failing on the
 * pickier hosts.
 */
object MediaHttp {

    const val BROWSER_UA: String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** Connect/read timeout for a media stream. Generous — a live edge can take a moment to open. */
    const val TIMEOUT_MS: Int = 20_000

    /**
     * The server answered, and said no.
     *
     * ⚠️ **Deliberately OUTSIDE the transient IO band, which ends at 2002.** Checked against the
     * shipped media3 jar: `BAD_HTTP_STATUS` is 2004 and `FILE_NOT_FOUND` is 2005, so a refusal used
     * to fall straight past "retry this" into "give up forever" in both players. The band is right;
     * what was missing is that this class of error has its own recovery — a NEW address, since the
     * one in hand will be refused however many times it is asked for.
     *
     * `INVALID_HTTP_CONTENT_TYPE` (2003) belongs here too, and that is not obvious: it is what you
     * get when a refusal comes back as an HTML error page where media was expected.
     */
    fun isRefusal(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

    /**
     * What to put on screen for a playback failure.
     *
     * ⚠️ The status code, not the enum name. `ERROR_CODE_IO_BAD_HTTP_STATUS` is what the owner saw
     * on a failed video, and it hides the one fact that separates three completely different
     * situations: 403 means the address was refused, 404 means it is gone, 429 means we are being
     * rate-limited. The real code sits on the cause chain the whole time.
     *
     * Shared by both players rather than copied into each: a message that drifts between two
     * surfaces describing the same failure is a small bug that wastes real diagnostic time.
     */
    fun describe(error: PlaybackException): String {
        var cause: Throwable? = error.cause
        var guard = 0
        while (cause != null && guard++ < 8) {
            val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            if (code != null) {
                return when (code) {
                    401, 403 -> "$code — the source refused that address"
                    404 -> "404 — that address is gone"
                    429 -> "429 — the source is rate-limiting us"
                    in 500..599 -> "$code — the source is having trouble"
                    else -> "HTTP $code"
                }
            }
            cause = cause.cause
        }
        return error.errorCodeName
    }
}
