package dev.mascwa.pulse.core.telemetry

/**
 * Whether the Sun actually rises and sets where you are today.
 *
 * Above the Arctic Circle and below the Antarctic one it does neither for weeks at a time, and the
 * sunrise feed says so in an unusual way: it answers `status: OK` with a rise and set of
 * **1 January 1970**, and a `day_length` of 0. Two different sentinels, measured live at 78.22 N:
 *
 * | Response | `day_length` | Means |
 * |---|---|---|
 * | `1970-01-01T00:00:01+00:00` (epoch **1000 ms**) | 0 | midnight sun — the Sun does not set |
 * | `1970-01-01T00:00:00+00:00` (epoch **0 ms**)    | 0 | polar night — the Sun does not rise |
 *
 * ⚠️ **`day_length` is 0 in both cases, so it cannot tell them apart.** Only the one-second
 * difference in the sentinel does. That single second is also why the app's original guard —
 * `epochMs <= 0` — caught the polar-night case and let the midnight-sun case straight through, so
 * the home card printed a plausible "Sunrise 01:00 · Sunset 01:00" on a day the Sun never set.
 *
 * The conservative shape here matters more than the classification. A sentinel this app does not
 * recognise resolves to [Kind.UNKNOWN] and the caller says nothing, rather than formatting a date in
 * 1970 as a time of day. Silence is a fine outcome; a fabricated clock time is not.
 *
 * Pure and CI-tested; the caller passes the parsed epochs straight from the feed.
 */
object SolarDay {

    /** What the day does, as far as the feed is willing to say. */
    enum class Kind {
        /** Ordinary day: the times are real and can be printed. */
        NORMAL,

        /** The Sun is up for the whole day and never sets. */
        MIDNIGHT_SUN,

        /** The Sun stays down for the whole day and never rises. */
        POLAR_NIGHT,

        /** Nothing trustworthy to say — absent, contradictory, or a sentinel we do not know. */
        UNKNOWN,
    }

    /** The sentinel the feed returns when the Sun does not set. Measured, not documented. */
    const val MIDNIGHT_SUN_SENTINEL_MS = 1_000L

    /** The sentinel the feed returns when the Sun does not rise. */
    const val POLAR_NIGHT_SENTINEL_MS = 0L

    /**
     * Anything on 1 January 1970 is a sentinel rather than a time.
     *
     * A real sunrise is never in 1970, so the window is a safe test and does not depend on the exact
     * magic value — which matters, because the values are undocumented and could change.
     */
    const val SENTINEL_WINDOW_MS = 86_400_000L

    /** Whether [epochMs] is one of the feed's 1970 placeholders rather than a real instant. */
    fun isSentinel(epochMs: Long?): Boolean = epochMs != null && epochMs < SENTINEL_WINDOW_MS

    /**
     * What kind of day this is.
     *
     * The rules, and why each one:
     *
     * 1. **Both times real → NORMAL.** The overwhelmingly common case, unchanged.
     * 2. **Both times sentinels → look at which sentinel**, and only name a day whose sentinel is
     *    recognised. An unrecognised one is [Kind.UNKNOWN], so a change at the far end degrades to
     *    silence rather than to a wrong claim.
     * 3. **A stated non-zero `day_length` contradicts a sentinel**, so that combination is
     *    [Kind.UNKNOWN] too. The feed reports 0 for both polar cases; anything else means we have
     *    misread it. An absent `day_length` is not evidence either way and is ignored.
     * 4. **One sentinel and one real time** is a state this feed has never been observed to produce,
     *    and there is no honest reading of it, so it is [Kind.UNKNOWN].
     */
    fun classify(sunriseEpochMs: Long?, sunsetEpochMs: Long?, dayLengthSec: Long? = null): Kind {
        if (sunriseEpochMs == null && sunsetEpochMs == null) return Kind.UNKNOWN
        val riseSentinel = isSentinel(sunriseEpochMs)
        val setSentinel = isSentinel(sunsetEpochMs)

        // Neither is a placeholder: an ordinary day, whichever of the two the feed supplied.
        if (!riseSentinel && !setSentinel) return Kind.NORMAL

        // ⚠️ No explicit guard for "exactly one is a placeholder". It would read as load-bearing and
        // is not: the `when` below requires BOTH times to carry the same recognised sentinel, so a
        // half-sentinel day already falls through to UNKNOWN. Adding the guard and then removing it
        // again changed no result on any input, which is how this was established.

        // A day on which the Sun does not cross the horizon has no length.
        if (dayLengthSec != null && dayLengthSec != 0L) return Kind.UNKNOWN

        return when {
            sunriseEpochMs == MIDNIGHT_SUN_SENTINEL_MS && sunsetEpochMs == MIDNIGHT_SUN_SENTINEL_MS ->
                Kind.MIDNIGHT_SUN
            sunriseEpochMs == POLAR_NIGHT_SENTINEL_MS && sunsetEpochMs == POLAR_NIGHT_SENTINEL_MS ->
                Kind.POLAR_NIGHT
            else -> Kind.UNKNOWN
        }
    }

    /**
     * The line to show instead of a clock time, or null when there is nothing to add.
     *
     * Null for [Kind.NORMAL] because the caller prints the real times, and null for [Kind.UNKNOWN]
     * because the whole point is to say nothing rather than guess.
     */
    fun describe(kind: Kind): String? = when (kind) {
        Kind.MIDNIGHT_SUN -> "The Sun does not set today"
        Kind.POLAR_NIGHT -> "The Sun does not rise today"
        Kind.NORMAL, Kind.UNKNOWN -> null
    }
}
