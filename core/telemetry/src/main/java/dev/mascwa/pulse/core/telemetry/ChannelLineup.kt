package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.LiveChannels.LiveChannel

/**
 * A cable television lineup: every channel has a NUMBER, and you change channel by number.
 *
 * The live-TV panel used to be a horizontally scrolling rail of names. With 41 curated channels
 * that is already a long scroll, and with the opt-in community catalogue switched on it is ~620 —
 * a rail you have to read your way along to find anything. A cable box solved this decades ago and
 * the solution is not a better list: it is that **the channel has a number, the number does not
 * move, and you reach it without looking at a list at all** — press channel-up, or key the digits.
 *
 * So this is the lineup, and the things a remote control does to it. It is deliberately pure: the
 * numbering, the tuning and the keypad's accumulate-then-commit behaviour are all decidable from
 * their inputs, which is what lets CI hold them rather than a device.
 *
 * ## What a number means
 *
 * Numbers come from [LiveChannels.CURATED]'s own declaration order, which is already authored in
 * genre and region sections, one [Band] each, on round decade boundaries. Two consequences worth
 * being deliberate about:
 *
 * ⚠️ **A number must not move.** That is the entire premise — 7 is 7 tomorrow, which is why anyone
 * can learn a lineup. So the numbering is NOT taken from [LiveChannels.offer], whose order is
 * verification-first and then alphabetical: a channel that fails to play once would renumber every
 * channel after it, and the viewer's memory of the lineup would be silently wrong. Declaration
 * order is fixed in source and changes only when somebody edits the list.
 *
 * ⚠️ **Community numbers are a directory, not a lineup, and the difference is stated rather than
 * papered over.** The catalogue is fetched, so its contents change under us; the best available is
 * a deterministic order (alphabetical) from [Band.COMMUNITY]'s base. Given the same catalogue you
 * get the same numbers; given a changed catalogue you do not. Curated numbers never move.
 */
object ChannelLineup {

    /**
     * The number ranges, one per section of [LiveChannels.CURATED].
     *
     * Sized from the real list rather than guessed at — measured at the time of writing:
     * 10 global networks, 4 state services, 2 Europe, 6 Africa, 3 Middle East, 7 Asia-Pacific,
     * 6 business, 3 Americas. Every band has room to grow and [overflows] fails the build if one
     * ever runs into the next.
     *
     * ⚠️ Starts at 2, not 1 or 0. Channel 1 is conventionally not a channel, and starting at 0
     * would make the keypad's leading digit ambiguous.
     */
    enum class Band(val label: String, val first: Int, val last: Int) {
        NETWORKS("World news", 2, 19),
        SERVICES("International services", 20, 29),
        EUROPE("Europe", 30, 39),
        AFRICA("Africa", 40, 49),
        MIDDLE_EAST("Middle East", 50, 59),
        ASIA_PACIFIC("Asia-Pacific", 60, 69),
        BUSINESS("Business", 70, 79),
        AMERICAS("Americas", 80, 99),
        COMMUNITY("Community", 100, 9_999),
    }

    /**
     * The channel that opens each band, by id, in [LiveChannels.CURATED]'s declaration order.
     *
     * ⚠️ **This is a coupling to a list in another file, and it is enforced rather than trusted.**
     * Eight ids against a 41-entry list is far less to maintain than a `band` field on every
     * channel, and a defaulted field would have let a newly-added channel land in whatever band
     * preceded it without anybody noticing. `ChannelLineupTest` asserts every anchor resolves to a
     * real channel and that they appear in this order within the list, so inserting a section
     * without recording it here fails the build instead of silently mis-numbering the lineup.
     */
    val ANCHORS: List<Pair<Band, String>> = listOf(
        Band.NETWORKS to "bbc-news",
        Band.SERVICES to "cgtn",
        Band.EUROPE to "dw-es",
        Band.AFRICA to "africanews",
        Band.MIDDLE_EAST to "al-arabiya-en",
        Band.ASIA_PACIFIC to "arirang",
        Band.BUSINESS to "bloomberg-tv",
        Band.AMERICAS to "cbs-news",
    )

    /** One position in the lineup. */
    data class Slot(val number: Int, val channel: LiveChannel, val band: Band)

    /**
     * The lineup: curated channels on their fixed numbers, then the community directory from 100.
     *
     * ⚠️ Unplayable channels are dropped ([LiveChannels.playable]) but the numbering is computed
     * BEFORE the drop, so removing a dead channel leaves a gap rather than pulling every later
     * channel down one. A gap is what a real lineup does, and it is the only way "7 is 7" survives
     * a stream going off the air.
     */
    fun lineup(
        curated: List<LiveChannel> = LiveChannels.CURATED,
        community: List<LiveChannel> = emptyList(),
    ): List<Slot> {
        val anchorAt = ANCHORS.associate { (band, id) -> id to band }
        val out = mutableListOf<Slot>()
        var band = ANCHORS.first().first
        var next = band.first
        for (channel in curated) {
            anchorAt[channel.id]?.let { found ->
                band = found
                next = found.first
            }
            val number = next++
            if (LiveChannels.playable(channel, allowCommunity = false)) {
                out += Slot(number, channel, band)
            }
        }
        var communityNext = Band.COMMUNITY.first
        for (channel in community.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))) {
            val number = communityNext++
            if (LiveChannels.playable(channel, allowCommunity = true)) {
                out += Slot(number, channel, Band.COMMUNITY)
            }
        }
        return out
    }

    /** Every band that has at least one channel, in lineup order, for a guide that shows sections. */
    fun bands(lineup: List<Slot>): List<Pair<Band, List<Slot>>> =
        lineup.groupBy { it.band }.toList().sortedBy { it.first.first }

    /** The channel on this number, or null — a gap in a lineup is a real thing, not an error. */
    fun at(lineup: List<Slot>, number: Int): Slot? = lineup.firstOrNull { it.number == number }

    /**
     * The next channel up, wrapping past the top back to the bottom.
     *
     * Wrapping is not a detail: a remote whose channel-up stops dead at the top of the lineup feels
     * broken, and the viewer's only recourse is to hold channel-down forty times.
     */
    fun next(lineup: List<Slot>, from: Int): Slot? {
        if (lineup.isEmpty()) return null
        return lineup.filter { it.number > from }.minByOrNull { it.number }
            ?: lineup.minByOrNull { it.number }
    }

    /** The next channel down, wrapping past the bottom back to the top. */
    fun previous(lineup: List<Slot>, from: Int): Slot? {
        if (lineup.isEmpty()) return null
        return lineup.filter { it.number < from }.maxByOrNull { it.number }
            ?: lineup.maxByOrNull { it.number }
    }

    // ── the keypad ──────────────────────────────────────────────────────────────────────────────

    /**
     * How long the box waits for another digit before tuning to what it has.
     *
     * Two seconds is the familiar figure. Long enough to key a second digit deliberately, short
     * enough that a single-digit channel does not feel like it hung.
     */
    const val ENTRY_TIMEOUT_MS = 2_000L

    /** Digits keyed so far, and when the entry began. */
    data class Entry(val digits: String = "", val startedMs: Long = 0L) {
        val empty: Boolean get() = digits.isEmpty()
    }

    /** What keying a digit produced. */
    sealed interface Tune {
        /** Still accumulating — more digits could still reach a channel. */
        data class Typing(val entry: Entry) : Tune
        /** Resolved to a real channel. */
        data class Tuned(val slot: Slot) : Tune
        /** Resolved, and there is nothing on that number. */
        data class NoChannel(val number: Int) : Tune
    }

    /**
     * Key one digit and say what the box should do about it.
     *
     * ⚠️ **The rule that makes this feel like a cable box rather than a text field is (3) below: it
     * commits the moment no further digit could reach anything.** Key `9` against a lineup whose
     * highest is 87 and it tunes at once, because no 9x exists; key `1` and it waits, because 12
     * and 15 do. Without that, every single-digit channel would sit through the full timeout and
     * the keypad would feel broken rather than instant.
     *
     * Commits when any of these is true, in this order:
     *  1. the entry is as long as the longest number in the lineup — nothing more can be keyed;
     *  2. the timeout has elapsed since the first digit;
     *  3. no number in the lineup has these digits as a strict prefix.
     */
    fun key(entry: Entry, digit: Int, nowMs: Long, lineup: List<Slot>): Tune {
        if (digit !in 0..9) return Tune.Typing(entry)
        val fresh = entry.empty || nowMs - entry.startedMs >= ENTRY_TIMEOUT_MS
        val next = Entry(
            digits = (if (fresh) "" else entry.digits) + digit,
            startedMs = if (fresh) nowMs else entry.startedMs,
        )
        val widest = lineup.maxOfOrNull { it.number.toString().length } ?: 0
        return if (next.digits.length >= widest || !reachable(next, widest, lineup)) commit(next, lineup)
        else Tune.Typing(next)
    }

    /**
     * Could keying more digits onto this entry still land on a real channel?
     *
     * ⚠️ **Every remaining width, not just one more digit — and leading zeros are why.** The first
     * version asked whether any channel's number *string* had these digits as a prefix, which is
     * wrong twice over. Keying `0` matched nothing, so the box answered "no channel 0" and `0`→`2`
     * could never reach channel 2 — even though a box writes that channel as **02** and keying the
     * padded form is the ordinary way to enter it. And with a three-digit lineup it would commit a
     * `9` that could still have become `900`. Found by running the real 41-channel lineup through
     * the keypad rather than by reading the rule, which is the only way this kind shows up.
     *
     * Working in values rather than strings handles both: `0` widens to 00–09, which contains real
     * channels, so it waits; `9` widens to 90–99 and then 900–999, neither of which does, so it
     * commits at once.
     */
    private fun reachable(entry: Entry, widest: Int, lineup: List<Slot>): Boolean {
        var lo = entry.digits.toLongOrNull() ?: return false
        var hi = lo
        for (unused in entry.digits.length until widest) {
            lo *= 10
            hi = hi * 10 + 9
            if (lineup.any { it.number >= lo && it.number <= hi }) return true
        }
        return false
    }

    /**
     * Resolve an entry that has stopped growing — the timeout firing, or the viewer pressing OK.
     *
     * Separate from [key] because the caller owns the clock: a pending entry has to resolve without
     * another keypress, and only the UI knows when that moment arrives.
     */
    fun commit(entry: Entry, lineup: List<Slot>): Tune {
        val n = entry.digits.toIntOrNull() ?: return Tune.Typing(entry)
        return at(lineup, n)?.let { Tune.Tuned(it) } ?: Tune.NoChannel(n)
    }

    /** Has a pending entry waited long enough that it should tune itself? */
    fun expired(entry: Entry, nowMs: Long): Boolean =
        !entry.empty && nowMs - entry.startedMs >= ENTRY_TIMEOUT_MS

    /** Bands whose channels would run past their own range — a build failure, never a runtime one. */
    fun overflows(lineup: List<Slot>): List<Band> =
        lineup.filter { it.number > it.band.last }.map { it.band }.distinct()

    /** "07" — a channel number as a cable box shows it, two digits until the lineup needs three. */
    fun display(number: Int): String = if (number < 10) "0$number" else number.toString()
}
