package dev.mascwa.pulse.core.telemetry

/**
 * Which parts of a video to skip, and where to land.
 *
 * The community database returns time ranges somebody has marked as sponsor read, self-promotion,
 * intro, and so on. Turning that into a playback decision is where the judgement lives, and all of
 * it is here so CI gates it and it runs locally — the player just asks "am I in one, and where do I
 * jump to".
 *
 * ⚠️ **Every threshold below was measured against a real 731-segment corpus** (431 videos, pulled
 * from the hash-prefix endpoint in three requests), not chosen by intuition. Two of the measurements
 * contradicted what the design would otherwise have been, and one of those would have shipped a
 * feature that looked completely broken. The numbers are recorded at each constant so the next
 * person can disagree with evidence rather than taste.
 */
object SponsorSegments {

    /**
     * What a segment is.
     *
     * UNKNOWN is a real member, not a defensive afterthought: the database gains categories over
     * time, and a client that mapped an unrecognised one onto something familiar would skip content
     * the user never opted out of.
     */
    enum class Category {
        SPONSOR, SELFPROMO, INTERACTION, INTRO, OUTRO, PREVIEW, MUSIC_OFFTOPIC, FILLER,
        EXCLUSIVE_ACCESS, POI_HIGHLIGHT, UNKNOWN,
    }

    /**
     * What the submitter meant should HAPPEN, which is not the same as what the segment is.
     *
     * ⚠️ **Only [SKIP] may ever be skipped, and the others are the reason this enum exists.** MUTE
     * means lower the volume and keep playing; POI is a zero-length bookmark; CHAPTER is a title
     * marker; FULL labels the entire video rather than a range inside it. Treating any of them as a
     * skip would cut real content — a FULL segment in particular would jump straight to the end.
     *
     * All 731 segments in the measured corpus were SKIP, because the endpoint returns skips unless
     * asked otherwise. That makes this look like dead defensiveness and it is not: the request is one
     * parameter away from returning the others, and the failure would be silent.
     */
    enum class Action { SKIP, MUTE, POI, CHAPTER, FULL, UNKNOWN }

    data class Segment(
        val uuid: String,
        val category: Category,
        val action: Action,
        val startS: Double,
        val endS: Double,
        val votes: Int,
        val locked: Boolean = false,
        val description: String = "",
    ) {
        val lengthS: Double get() = endS - startS
    }

    /** What the user has opted into. */
    data class Policy(
        val categories: Set<Category> = DEFAULT_CATEGORIES,
        val minVotes: Int = MIN_VOTES,
        val minLengthS: Double = MIN_LENGTH_S,
    )

    /**
     * On by default: the parts nearly everyone means when they say "skip the sponsor".
     *
     * FILLER and MUSIC_OFFTOPIC are deliberately OFF — filler is tangents and jokes, which many
     * people watch for, and music_offtopic cuts non-music sections out of music videos, which is
     * only wanted if that is specifically what you are doing. Skipping something the user enjoys is
     * a worse failure than not skipping an advert.
     */
    val DEFAULT_CATEGORIES: Set<Category> = setOf(
        Category.SPONSOR, Category.SELFPROMO, Category.INTERACTION,
        Category.INTRO, Category.OUTRO, Category.PREVIEW,
    )

    /**
     * The vote floor — reject only what has been actively voted DOWN.
     *
     * ⚠️ **This is the measurement that would have shipped a broken feature.** The obvious floor is
     * "more upvotes than downvotes", i.e. `> 0`, and against the real corpus that keeps **56 of 731
     * segments and leaves 384 of 431 videos with nothing at all** — nine videos in ten would look
     * like the database had never heard of them. The reason is simple once seen: **670 of 731
     * segments sit at exactly zero**, because almost nobody votes on a segment that is simply
     * correct. Zero means "nobody objected", not "nobody endorsed". At `>= 0` the corpus keeps 99.3%
     * and only two videos come back empty.
     */
    const val MIN_VOTES = 0

    /**
     * Skips shorter than this are not worth making.
     *
     * A seek flushes the buffer and costs perhaps a second of stall, so skipping a fraction of a
     * second makes the video worse, not better. Measured: a one-second floor discards **5 of 731
     * segments (0.7%)** where the median segment is 19 seconds, so this costs essentially nothing.
     * Three seconds would discard 4.2%, which starts to be real content.
     */
    const val MIN_LENGTH_S = 1.0

    // ---- parsing ---------------------------------------------------------------------------------

    /** The wire's category string, or [Category.UNKNOWN] for anything this build does not know. */
    fun categoryOf(raw: String): Category = when (raw.trim().lowercase()) {
        "sponsor" -> Category.SPONSOR
        "selfpromo" -> Category.SELFPROMO
        "interaction" -> Category.INTERACTION
        "intro" -> Category.INTRO
        "outro" -> Category.OUTRO
        "preview" -> Category.PREVIEW
        "music_offtopic" -> Category.MUSIC_OFFTOPIC
        "filler" -> Category.FILLER
        "exclusive_access" -> Category.EXCLUSIVE_ACCESS
        "poi_highlight" -> Category.POI_HIGHLIGHT
        else -> Category.UNKNOWN
    }

    /** The wire's actionType string, or [Action.UNKNOWN]. */
    fun actionOf(raw: String): Action = when (raw.trim().lowercase()) {
        "skip" -> Action.SKIP
        "mute" -> Action.MUTE
        "poi" -> Action.POI
        "chapter" -> Action.CHAPTER
        "full" -> Action.FULL
        else -> Action.UNKNOWN
    }

    // ---- the policy ------------------------------------------------------------------------------

    /**
     * Whether this segment may be skipped.
     *
     * ⚠️ A **locked** segment bypasses the vote floor and nothing else. Locked means a moderator has
     * confirmed it, which is a stronger signal than any vote count — but it says nothing about
     * whether the user wanted that category skipped, or whether the range is long enough to be worth
     * a seek. Letting `locked` override the category would skip content the user explicitly turned
     * off, which is the one thing this must never do.
     */
    fun accept(s: Segment, policy: Policy = Policy()): Boolean {
        if (s.action != Action.SKIP) return false
        if (s.category == Category.UNKNOWN) return false
        if (s.category !in policy.categories) return false
        if (s.lengthS < policy.minLengthS) return false
        if (!s.locked && s.votes < policy.minVotes) return false
        // A range that does not move forward cannot be skipped out of, and a negative one would send
        // the player backwards. None appeared in the measured corpus; both are one bad row away.
        return s.endS > s.startS
    }

    /**
     * Overlapping accepted segments joined into single skips.
     *
     * ⚠️ **Overlaps are not hypothetical: 26 adjacent pairs in 731 segments overlapped, 5 of them
     * fully contained inside another.** Skipping them separately means landing inside the next one
     * and immediately seeking again — two stalls where one would do, and on a contained pair the
     * second seek goes BACKWARDS into content already skipped past.
     *
     * The merged block keeps the category of the segment that starts first, since that is the one
     * the viewer would have hit; the label is the only thing the category is used for downstream.
     */
    fun merge(segments: List<Segment>): List<Segment> {
        if (segments.size < 2) return segments
        val sorted = segments.sortedWith(compareBy({ it.startS }, { it.endS }))
        val out = ArrayList<Segment>(sorted.size)
        var cur = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.startS <= cur.endS) {
                // Touching counts as overlapping. Two segments that meet exactly at a boundary would
                // otherwise produce a skip that lands precisely on the start of the next one — legal,
                // but a second seek for no gain.
                if (next.endS > cur.endS) cur = cur.copy(endS = next.endS)
            } else {
                out.add(cur)
                cur = next
            }
        }
        out.add(cur)
        return out
    }

    /** Everything worth skipping in this video, in order, already merged. */
    fun usable(segments: List<Segment>, policy: Policy = Policy()): List<Segment> =
        merge(segments.filter { accept(it, policy) })

    // ---- playback --------------------------------------------------------------------------------

    /**
     * The segment the playhead is inside, or null.
     *
     * Start is inclusive and end is exclusive, so a position sitting exactly on a boundary belongs
     * to the segment beginning there and not the one ending there — which is what stops a skip that
     * lands on a boundary from immediately matching the segment it just left.
     */
    fun segmentAt(positionS: Double, segments: List<Segment>): Segment? =
        segments.firstOrNull { positionS >= it.startS && positionS < it.endS }

    /**
     * Where to seek to, or null to keep playing.
     *
     * ⚠️ **Never backwards, and that is the property that stops an infinite loop.** A target at or
     * before the current position would seek back into the segment, which matches again on the next
     * tick, which seeks back again, forever.
     *
     * ⚠️ **The exclusive end in [segmentAt] is the ONLY thing upholding it, deliberately.** A second
     * `takeIf { it > positionS }` guard sat here and could not fire: containment already requires
     * `positionS < endS`, so the returned end is always ahead. Its comment claimed it protected
     * against unsorted or unmerged input, and that was simply untrue — every containing segment ends
     * after the position however the list is ordered. Two mechanisms where one is unreachable is the
     * "computed and never used" defect this repo keeps correcting, and worse here: it made the real
     * guard untestable, because removing the exclusive end broke nothing while the dead check
     * silently covered for it.
     */
    fun skipTo(positionS: Double, segments: List<Segment>): Double? =
        segmentAt(positionS, segments)?.endS

    /**
     * How much of the video these skips remove, in seconds.
     *
     * Over merged segments only — summing raw ones double-counts every overlap, and the whole point
     * of telling somebody "this saves you four minutes" is that the figure is true.
     */
    fun totalSkippedS(merged: List<Segment>): Double = merged.sumOf { it.lengthS }

    /** What to say when a skip happens. */
    fun label(category: Category): String = when (category) {
        Category.SPONSOR -> "sponsor"
        Category.SELFPROMO -> "self-promotion"
        Category.INTERACTION -> "subscribe reminder"
        Category.INTRO -> "intro"
        Category.OUTRO -> "outro"
        Category.PREVIEW -> "recap"
        Category.MUSIC_OFFTOPIC -> "non-music section"
        Category.FILLER -> "filler"
        Category.EXCLUSIVE_ACCESS -> "exclusive-access notice"
        Category.POI_HIGHLIGHT -> "highlight"
        Category.UNKNOWN -> "segment"
    }
}
