package dev.mascwa.pulse.core.telemetry

/**
 * What a stream costs to listen to or watch, in the unit a mobile allowance is sold in.
 *
 * One definition, because two screens now ask the same question — the live-TV panel, from the
 * bitrate the player reports it is receiving, and the radio, from the bitrate the directory
 * publishes. A second copy of this arithmetic is the mistake this repository has corrected four
 * times with palettes and ranking.
 *
 * Megabytes are the decimal kind (10⁶). A phone plan is sold in those.
 */
object DataRate {

    /**
     * The band a real audio or video stream's declared rate falls in, in kilobits per second.
     *
     * ⚠️ Both bounds come from measuring the real directory, not from taste. Of 360 stations
     * sampled, the extremes were **24 kbps** (a genuine Opus stream), **1441 kbps** (Radio Paradise
     * in FLAC — lossless stereo really is about that) and **320000**, on a station whose own name
     * reads "320K AAC". The last is somebody typing bits where the field wants kilobits, and it is
     * the reason this check exists: the figure is user-submitted, so it has to be believed only
     * within the range a stream can actually occupy.
     *
     * The upper bound clears lossless with room to spare rather than clipping at 320, which would
     * have thrown away a correct number to catch a wrong one.
     */
    const val PLAUSIBLE_MIN_KBPS: Int = 8
    const val PLAUSIBLE_MAX_KBPS: Int = 2_000

    /**
     * A published kilobit rate, in bits per second — or null if it cannot be believed.
     *
     * Null rather than clamped: a clamped number is still a number on screen, and a made-up figure
     * under a play button is worse than no figure.
     */
    fun fromKilobits(kbps: Int): Int? =
        if (kbps in PLAUSIBLE_MIN_KBPS..PLAUSIBLE_MAX_KBPS) kbps * 1_000 else null

    /** "about 1.0 MB a minute", or null when there is nothing worth stating. */
    fun describe(bitsPerSecond: Int): String? {
        if (bitsPerSecond <= 0) return null
        val mbPerMinute = bitsPerSecond * 60.0 / 8.0 / 1_000_000.0
        val figure = if (mbPerMinute < 10) {
            // A tenth still matters down here: 0.2 and 0.9 are very different on a small allowance.
            val tenths = Math.round(mbPerMinute * 10).toInt()
            "${tenths / 10}.${tenths % 10}"
        } else {
            // Past ten, a tenth of a megabyte is noise.
            Math.round(mbPerMinute).toString()
        }
        return "about $figure MB a minute"
    }

    /** The same, from a published kilobit rate. Null when the rate is absent or implausible. */
    fun describeKilobits(kbps: Int): String? = fromKilobits(kbps)?.let { describe(it) }

    /**
     * The short form for a dense row: "AAC · 128k".
     *
     * The codec matters alongside the rate because they are not comparable without each other — 64
     * kbps of AAC and 64 kbps of MP3 do not sound the same. Either half may be missing, and the
     * result is whatever is actually known rather than a placeholder standing in for it.
     */
    fun quality(codec: String?, kbps: Int): String? {
        val c = codec?.trim()?.takeIf { it.isNotBlank() && !it.equals("UNKNOWN", ignoreCase = true) }
        val rate = fromKilobits(kbps)?.let { "${kbps}k" }
        return listOfNotNull(c?.uppercase(), rate).takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
