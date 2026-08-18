package dev.mascwa.pulse.feature.media

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
}
