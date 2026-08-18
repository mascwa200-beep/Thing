package dev.mascwa.pulse.core.telemetry

/**
 * Live television news channels the app can actually play, and how much to trust each one.
 *
 * The whole subject is governed by one measured fact: **a playlist that answers HTTP 200 is not a
 * playlist that plays.** Probing candidate streams down to a real video segment rather than stopping
 * at the master:
 *
 * ```
 * DW English    master 200 -> variant 200 -> 691 KB MPEG-TS   (5 variants, lowest 480x270)
 * DW Spanish    master 200 -> variant 200 -> 653 KB MPEG-TS
 * CNA           master 200 -> variant 200 -> 276 KB MPEG-TS
 * France 24     master 200 -> variant 400                     (looks fine, plays nothing)
 * NASA TV       master 200 -> variant 404                     (likewise)
 * ```
 *
 * The same "200 that isn't success" shape the feed audit found in Overpass. So a channel carries
 * whether it has been verified *to a segment*, and a channel that has not is offered as exactly
 * that rather than quietly dropped or silently presented as working.
 *
 * ⚠️ **Unverified does not mean broken.** Al Jazeera English and NHK World both failed from the
 * machine this catalogue was written on — with a 502 from its outbound proxy, which says nothing
 * about whether they play on a phone on an ordinary network. Removing them would have been a claim
 * the evidence does not support.
 *
 * Pure and CI-tested. Everything about *playing* a stream is platform work; this is only the model
 * and the judgements, so the phone and the desktop cannot disagree about them.
 */
object LiveChannels {

    /** Where a channel's address came from, which is the same question as how far to trust it. */
    enum class Provenance {
        /**
         * The broadcaster's own public endpoint.
         *
         * These are the streams a broadcaster publishes for its own web player. Using one from a
         * different app is outside what they had in mind, even though nothing technically prevents
         * it — worth the owner knowing rather than discovering.
         */
        OFFICIAL,

        /**
         * A community-maintained catalogue entry.
         *
         * Wide coverage and self-updating, and its contents are of mixed origin: some entries are
         * unauthorised restreams of channels that are not free to watch. That is why community
         * channels are opt-in rather than merged into the default list.
         */
        COMMUNITY,
    }

    /** How far a channel has actually been shown to work. */
    enum class Verification {
        /** Played end to end: master, variant, and a real video segment retrieved. */
        SEGMENT,

        /** Not confirmed from here. May well work; the evidence is simply absent. */
        UNVERIFIED,

        /** Confirmed broken: the master answered but the stream underneath it did not. */
        FAILED,
    }

    data class LiveChannel(
        val id: String,
        val name: String,
        val url: String,
        /** BCP-47-ish language tag, for a viewer choosing something they can follow. */
        val language: String,
        /** Where the broadcaster is based — not where it can be watched. */
        val region: String,
        val provenance: Provenance,
        val verification: Verification,
    )

    /**
     * The channels shipped with the app.
     *
     * Deliberately short. Every entry is a broadcaster's own endpoint, and the three at
     * [Verification.SEGMENT] were each walked down to a real video segment on the day this was
     * written. Breadth is the opt-in catalogue's job, not this list's.
     */
    val CURATED: List<LiveChannel> = listOf(
        LiveChannel(
            id = "dw-en",
            name = "DW English",
            url = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
            language = "en",
            region = "Germany",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "dw-es",
            name = "DW Español",
            url = "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8",
            language = "es",
            region = "Germany",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "cna",
            name = "CNA",
            url = "https://d2e1asnsl7br7b.cloudfront.net/7782e205e72f43aeb4a48ec97f66ebbe/index.m3u8",
            language = "en",
            region = "Singapore",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        // Below: reachable in principle, not confirmed from the machine this list was built on.
        // Both failed with a proxy-level 502 rather than anything the broadcaster returned.
        LiveChannel(
            id = "aljazeera-en",
            name = "Al Jazeera English",
            url = "https://live-hls-web-aje.getaj.net/AJE/index.m3u8",
            language = "en",
            region = "Qatar",
            provenance = Provenance.OFFICIAL,
            verification = Verification.UNVERIFIED,
        ),
        LiveChannel(
            id = "nhk-world",
            name = "NHK World-Japan",
            url = "https://nhkwlive-xjp.akamaized.net/hls/live/2003459/nhkwlive-xjp-en/index.m3u8",
            language = "en",
            region = "Japan",
            provenance = Provenance.OFFICIAL,
            verification = Verification.UNVERIFIED,
        ),
    )

    /** Whether an address is an HLS playlist, which is the only thing either player can open. */
    fun isHls(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    /**
     * Whether a channel may be offered at all.
     *
     * A confirmed-broken channel is never offered, whatever the settings say — there is no reading
     * of "show me more channels" that means "show me ones known not to work". Community channels
     * appear only when the viewer has opted in.
     */
    fun playable(channel: LiveChannel, allowCommunity: Boolean): Boolean = when {
        !isHls(channel.url) -> false
        channel.verification == Verification.FAILED -> false
        channel.provenance == Provenance.COMMUNITY && !allowCommunity -> false
        else -> true
    }

    /** The channels to show, in a stable order: confirmed first, then the rest by name. */
    fun offer(
        channels: List<LiveChannel> = CURATED,
        allowCommunity: Boolean = false,
    ): List<LiveChannel> = channels
        .filter { playable(it, allowCommunity) }
        .sortedWith(compareBy({ it.verification != Verification.SEGMENT }, { it.name }))

    /**
     * The honest one-line description under a channel's name.
     *
     * Says where the stream comes from and, when it matters, that nobody has confirmed it plays.
     * A confirmed official channel gets no caveat, because there is nothing to warn about.
     */
    fun describe(channel: LiveChannel): String {
        val origin = when (channel.provenance) {
            Provenance.OFFICIAL -> "${channel.region} · official feed"
            Provenance.COMMUNITY -> "${channel.region} · community listing"
        }
        val caveat = when (channel.verification) {
            Verification.SEGMENT -> null
            Verification.UNVERIFIED -> "not verified — may not play"
            Verification.FAILED -> "known not to play"
        }
        return listOfNotNull(origin, caveat).joinToString(" · ")
    }

    /**
     * What watching costs, from the bitrate the player reports it is *actually* receiving.
     *
     * Derived rather than declared: a channel's catalogue entry would go stale, and the master
     * playlist's advertised bandwidth is the variant's ceiling rather than what was chosen. Asking
     * the player after it has settled is the only figure that is measured.
     *
     * ⚠️ Delegates to [DataRate] rather than doing the arithmetic here. The radio asks the same
     * question of the station directory's published rate, and two screens quoting a different cost
     * for the same stream would mean one of them is wrong — the duplicated-definition mistake this
     * repository has corrected several times already.
     */
    fun dataRateNote(bitsPerSecond: Int): String? = DataRate.describe(bitsPerSecond)

    /**
     * Which channel a breaking-news pop-up should open, or null if there is nothing worth opening.
     *
     * Prefers a channel in the viewer's own language that is confirmed to play, then any confirmed
     * channel, then whatever is left. Ties fall to [offer]'s order, which is confirmed-first and
     * then alphabetical — not the order the list was declared in.
     *
     * ⚠️ Returns null rather than a guess when nothing qualifies: a takeover that opens a dead
     * stream is worse than one that opens no video at all, because it spends the viewer's attention
     * at exactly the moment something is happening.
     */
    fun forBreaking(
        channels: List<LiveChannel> = CURATED,
        language: String? = null,
        allowCommunity: Boolean = false,
    ): LiveChannel? {
        val usable = offer(channels, allowCommunity)
        if (usable.isEmpty()) return null
        val lang = language?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() }
        return usable.firstOrNull {
            lang != null &&
                it.language.substringBefore('-').lowercase() == lang &&
                it.verification == Verification.SEGMENT
        }
            ?: usable.firstOrNull { it.verification == Verification.SEGMENT }
            ?: usable.first()
    }
}
