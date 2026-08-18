package dev.mascwa.pulse.core.telemetry

/**
 * Live television news channels the app can actually play, and how much to trust each one.
 *
 * The whole subject is governed by one measured fact: **a playlist that answers HTTP 200 is not a
 * playlist that plays.** Every entry below was walked master -> variant -> a real video segment,
 * and the size of that segment is the evidence. Stopping at the master proves nothing: it is the
 * same "200 that isn't success" shape the feed audit found in Overpass.
 *
 * ⚠️ **A failure is more often the endpoint than the broadcaster, and that cost this catalogue two
 * channels for its whole first life.** Al Jazeera English and NHK World-Japan shipped as
 * [Verification.UNVERIFIED] from launch because the addresses they were given answered with a proxy
 * 502. Both play perfectly — Al Jazeera on the same host its *Arabic* sibling uses, NHK on NHK's own
 * domain rather than a CDN alias. France 24 English was recorded here as "master 200 -> variant 400,
 * looks fine, plays nothing"; that was a wrong path, and mirroring the shape of the Arabic sibling's
 * URL produced a 6.4 MB segment on the first try. TRT World failed a TLS handshake on one of its own
 * hosts and plays on the other. **Before believing a broadcaster is unreachable, look for a sibling
 * feed of the same broadcaster and copy its URL shape.**
 *
 * ⚠️ **Unverified still does not mean broken.** Press TV and News Central fail here in ways that are
 * attributable to this machine's outbound proxy — a TLS name mismatch and a connection reset — which
 * says nothing about a phone on an ordinary network. Removing them would be a claim the evidence
 * does not support; presenting them as working would be the opposite one.
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
         *
         * ⚠️ **"Own" is about who publishes the stream, not who serves the bytes.** DW is on Akamai
         * and CNA on CloudFront; both are the broadcaster's own origin, published for the
         * broadcaster's own player, and a general-purpose CDN in front of it changes nothing. What
         * is excluded is a *distributor's* multi-tenant playout platform — Amagi, Samsung TV Plus,
         * Pluto, Tubi, Xumo, Rakuten, a raw GitHub file — where a third party has licensed the
         * channel and re-packaged it.
         *
         * ⚠️ And the host alone does not settle it: Reuters TV is served from a CloudFront
         * distribution whose *path* reads `amg00453-reuters-samsunggb`, and both published NBC News
         * NOW addresses carry an `ads.xumo_channelId` parameter. Read the whole URL.
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

    /**
     * Who pays for the newsroom, which is context a viewer of a *news* channel is owed.
     *
     * ⚠️ The line between the two funded categories is drawn at one place and one place only:
     * **whether editorial independence from the funder is set out in law or in a charter.** The BBC
     * has a Royal Charter, DW a federal act, NHK the Broadcast Act, France 24 a statutory remit —
     * those are [PUBLIC]. A broadcaster funded and directed by a government with no such instrument
     * is [STATE]. Both are state money; only one comes with a guarantee attached, and collapsing
     * them would tell the viewer either too little or something untrue.
     *
     * The label is deliberately a statement about *funding* rather than about output. This module
     * has no business grading anybody's journalism, and a reader told who pays can draw their own
     * conclusion.
     */
    enum class Funding {
        /** Advertising or subscription funded. Says nothing on the row. */
        COMMERCIAL,

        /** Publicly funded, with editorial independence set out in law or charter. */
        PUBLIC,

        /** Government funded and government directed, with no such instrument. Stated on the row. */
        STATE,
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
        /**
         * Who funds the newsroom.
         *
         * Defaulted so a community listing — where this is simply not knowable — does not have to
         * assert anything, and so adding the field broke no existing construction.
         */
        val funding: Funding = Funding.COMMERCIAL,
    )

    /**
     * The channels shipped with the app: 41 news broadcasters, worldwide, almost all in English.
     *
     * Every one is the broadcaster's own endpoint under the rule in [Provenance.OFFICIAL], and every
     * one at [Verification.SEGMENT] was walked down to a real video segment. **How the list was
     * arrived at, because the numbers are the argument:** the iptv-org index yields 168 English-feed
     * news streams; 98 of them reach a segment; applying broadcaster-own strictly leaves about 30,
     * and widening the search from the news playlist to the full stream index — where the BBC,
     * Euronews, CBS News, WION, Bloomberg and SABC all publish on their own origins — brings it to
     * these 41 distinct broadcasters.
     *
     * ⚠️ **That is the ceiling of this source under this rule, not a stopping point chosen for
     * convenience.** Going further would mean either admitting distributor platforms, or padding
     * with sibling feeds of broadcasters already here (three more CityNews cities, three more CBS
     * local newsrooms, a second Sky News Australia channel, a second RT service). Neither is worth
     * doing quietly. Breadth beyond this is the opt-in catalogue's job, which carries ~620 entries.
     *
     * ⚠️ `language` is the language of *this feed*, taken from the index's feed record rather than
     * the channel's brand — a distinction that matters, because the brand-level record lists English
     * for DW's Arabic service and for Al Jazeera Arabic. `region` is where the broadcaster is based.
     *
     * ⚠️ The [Funding] calls are judgements against the criterion in that enum, and the arguable
     * ones are worth naming rather than burying: **Al Jazeera** is Qatari-government funded with no
     * statutory independence instrument, so it is filed [Funding.STATE] alongside CGTN and RT even
     * though its practice differs from theirs; **TRT** has one in Turkish law and is filed
     * [Funding.PUBLIC] even though its independence is widely contested; **VOA** has the 1976 VOA
     * Charter in US statute and is filed [Funding.PUBLIC] though that firewall has been under
     * sustained pressure; **CNA** is owned by Mediacorp, in turn owned by the Singapore government's
     * investment company, but is a commercial company and is filed [Funding.COMMERCIAL]. Each is one
     * line to change.
     */
    val CURATED: List<LiveChannel> = listOf(
        // ---- Global English-language news networks ------------------------------------------
        LiveChannel(
            id = "bbc-news",
            name = "BBC News",
            url = "https://vs-hls-push-ww-live.akamaized.net/x=4/i=urn:bbc:pips:service:" +
                "bbc_news_channel_hd/mobile_wifi_main_hd_abr_v2.m3u8",
            language = "en",
            region = "United Kingdom",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "dw-en",
            name = "DW English",
            url = "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8",
            language = "en",
            region = "Germany",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "france24-en",
            name = "France 24 English",
            url = "https://live.france24.com/hls/live/2037218-b/F24_EN_HI_HLS/master_5000.m3u8",
            language = "en",
            region = "France",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "euronews-en",
            name = "Euronews English",
            url = "https://cdn-euronews.akamaized.net/live/eds/euronews-en/25002/index.m3u8",
            language = "en",
            region = "France",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "aljazeera-en",
            name = "Al Jazeera English",
            url = "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8",
            language = "en",
            region = "Qatar",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.STATE,
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
        LiveChannel(
            id = "nhk-world",
            name = "NHK World-Japan",
            url = "https://masterpl.hls.nhkworld.jp/hls/w/live/smarttv.m3u8",
            language = "en",
            region = "Japan",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "trt-world",
            name = "TRT World",
            url = "https://tv-trtworld.medya.trt.com.tr/master.m3u8",
            language = "en",
            region = "Türkiye",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "un-web-tv",
            name = "UN Web TV",
            url = "https://cdnapi.kaltura.com/p/2503451/sp/250345100/playManifest/entryId/" +
                "1_gb6tjmle/protocol/https/format/applehttp/a.m3u8",
            language = "en",
            region = "United Nations",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "voa-africa",
            name = "VOA Africa",
            url = "https://voa-ingest.akamaized.net/hls/live/2033874/tvmc06/playlist.m3u8",
            language = "en",
            region = "United States",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),

        // ---- State-funded international services --------------------------------------------
        // Kept deliberately, and labelled. A viewer is better served by CGTN's own account of a
        // story next to the BBC's than by a list that quietly decides which governments they may
        // hear from.
        LiveChannel(
            id = "cgtn",
            name = "CGTN",
            url = "https://english-livebkali.cgtn.com/live/encgtn.m3u8",
            language = "en",
            region = "China",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.STATE,
        ),
        LiveChannel(
            id = "rt-news",
            name = "RT",
            url = "https://rt-glb.rttv.com/live/rtnews/playlist.m3u8",
            language = "en",
            region = "Russia",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.STATE,
        ),
        LiveChannel(
            id = "telesur-en",
            name = "teleSUR English",
            url = "https://mblenmain01.telesur.ultrabase.net/mblivev3/480p/playlist.m3u8",
            language = "en",
            region = "Venezuela",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.STATE,
        ),
        // Its own domain answered with a TLS certificate that does not name it — from here. That is
        // this machine's outbound proxy intercepting, not a broadcaster-level failure.
        LiveChannel(
            id = "presstv",
            name = "Press TV",
            url = "https://live.presstv.ir/hls/presstv_5_482/index.m3u8",
            language = "en",
            region = "Iran",
            provenance = Provenance.OFFICIAL,
            verification = Verification.UNVERIFIED,
            funding = Funding.STATE,
        ),

        // ---- Europe --------------------------------------------------------------------------
        LiveChannel(
            id = "dw-es",
            name = "DW Español",
            url = "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8",
            language = "es",
            region = "Germany",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "gb-news",
            name = "GB News",
            url = "https://live-gbnews.simplestreamcdn.com/live5/gbnews/bitrate1.isml/manifest.m3u8",
            language = "en",
            region = "United Kingdom",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),

        // ---- Africa --------------------------------------------------------------------------
        LiveChannel(
            id = "africanews",
            name = "Africanews",
            url = "https://cdn-euronews.akamaized.net/live/eds/africanews-en/25049/index.m3u8",
            language = "en",
            region = "France",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "africa24-en",
            name = "Africa 24 English",
            url = "https://edge20.vedge.infomaniak.com/livecast/ik:africa24english/manifest.m3u8",
            language = "en",
            region = "France",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "sabc-news",
            name = "SABC News",
            url = "https://sabconetanw.cdn.mangomolo.com/news/smil:news.stream.smil/master.m3u8",
            language = "en",
            region = "South Africa",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "ln24-sa",
            name = "LN24 South Africa",
            url = "https://cdnstack.internetmultimediaonline.org/ln24/ln24.stream/playlist.m3u8",
            language = "en",
            region = "South Africa",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "nw-info-en",
            name = "New World Info (English)",
            url = "https://hls.newworldtv.com/nw-info-2/video/live.m3u8",
            language = "en",
            region = "Togo",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        // Its own domain, on a non-standard port, reset the connection from here.
        LiveChannel(
            id = "news-central",
            name = "News Central",
            url = "https://wf.newscentral.ng:8443/hls/stream.m3u8",
            language = "en",
            region = "Nigeria",
            provenance = Provenance.OFFICIAL,
            verification = Verification.UNVERIFIED,
        ),

        // ---- Middle East ---------------------------------------------------------------------
        LiveChannel(
            id = "al-arabiya-en",
            name = "Al Arabiya English",
            url = "https://live.alarabiya.net/alarabiapublish/english/playlist_dvr.m3u8",
            language = "en",
            region = "Saudi Arabia",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "i24news-en",
            name = "i24NEWS English",
            url = "https://i24newsenglish-cdn.encoders.immergo.tv/master.m3u8",
            language = "en",
            region = "Israel",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "ktv2",
            name = "KTV 2",
            url = "https://kwtktv2ta.cdn.mangomolo.com/ktv2/smil:ktv2.stream.smil/chunklist.m3u8",
            language = "en",
            region = "Kuwait",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.STATE,
        ),

        // ---- Asia-Pacific --------------------------------------------------------------------
        LiveChannel(
            id = "arirang",
            name = "Arirang TV",
            url = "https://amdlive-ch01-ctnd-com.akamaized.net/arirang_1ch/smil:arirang_1ch.smil/" +
                "playlist.m3u8",
            language = "en",
            region = "South Korea",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "abc-news-au",
            name = "ABC News (Australia)",
            url = "https://abc-news-dmd-streams-1.akamaized.net/out/v1/" +
                "701126012d044971b3fa89406a440133/index.m3u8",
            language = "en",
            region = "Australia",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "sky-news-au",
            name = "Sky News Extra (Australia)",
            url = "https://skynewsau-live.akamaized.net/hls/live/2002689/skynewsau-extra1/master.m3u8",
            language = "en",
            region = "Australia",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "wion",
            name = "WION",
            url = "https://d7x8z4yuq42qn.cloudfront.net/index_7.m3u8",
            language = "en",
            region = "India",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "ndtv-24x7",
            name = "NDTV 24x7",
            url = "https://ndtv24x7elemarchana.akamaized.net/hls/live/2003678/ndtv24x7/master.m3u8",
            language = "en",
            region = "India",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "india-today",
            name = "India Today",
            url = "https://indiatodaylive.akamaized.net/hls/live/2014320/indiatoday/" +
                "indiatodaylive/playlist.m3u8",
            language = "en",
            region = "India",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "toi-global",
            name = "Times of India Global",
            url = "https://live.sli.ke/live/npnhm84gz9/master.m3u8",
            language = "en",
            region = "India",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),

        // ---- Business and markets ------------------------------------------------------------
        LiveChannel(
            id = "bloomberg-tv",
            name = "Bloomberg TV",
            url = "https://bloomberg.com/media-manifest/streams/eu.m3u8",
            language = "en",
            region = "United States",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "yahoo-finance",
            name = "Yahoo Finance",
            url = "https://d1ewctnvcwvvvu.cloudfront.net/playlist.m3u8",
            language = "en",
            region = "United States",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "ausbiz",
            name = "ausbiz TV",
            url = "https://d9quh89lh7dtw.cloudfront.net/public-output/index.m3u8",
            language = "en",
            region = "Australia",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "cnbc-tv18",
            name = "CNBC TV18",
            url = "https://n18syndication.akamaized.net/bpk-tv/CNBC_TV18_NW18_MOB/output01/index.m3u8",
            language = "en",
            region = "India",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "ndtv-profit",
            name = "NDTV Profit",
            url = "https://ndtvprofit.akamaized.net/hls/live/2107404/ndtvprofit/master_1.m3u8",
            language = "en",
            region = "India",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "business-today",
            name = "Business Today",
            url = "https://feeds.intoday.in/bttv/itgd.m3u8",
            language = "en",
            region = "India",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),

        // ---- Americas ------------------------------------------------------------------------
        LiveChannel(
            id = "cbs-news",
            name = "CBS News 24/7",
            url = "https://cbsn-us.cbsnstream.cbsnews.com/out/v1/" +
                "55a8648e8f134e82a470f83d562deeca/master.m3u8",
            language = "en",
            region = "United States",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
        ),
        LiveChannel(
            id = "cbc-news",
            name = "CBC News",
            url = "https://d2ny9lo79ujali.cloudfront.net/CBC_News_International.m3u8",
            language = "en",
            region = "Canada",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
            funding = Funding.PUBLIC,
        ),
        LiveChannel(
            id = "citynews-toronto",
            name = "CityNews Toronto",
            url = "https://citynewsregional.akamaized.net/hls/live/1024052/Regional_Live_7/master.m3u8",
            language = "en",
            region = "Canada",
            provenance = Provenance.OFFICIAL,
            verification = Verification.SEGMENT,
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
     * Says where the broadcaster is, who pays for it when that is worth knowing, where the stream
     * comes from, and — when it matters — that nobody has confirmed it plays. A confirmed
     * commercial official channel gets none of the extras, because there is nothing to add.
     *
     * ⚠️ [Funding.COMMERCIAL] renders nothing on purpose. It is the unremarkable case, and a badge
     * on every row is a badge nobody reads.
     */
    fun describe(channel: LiveChannel): String {
        val funding = when (channel.funding) {
            Funding.COMMERCIAL -> null
            Funding.PUBLIC -> "public broadcaster"
            Funding.STATE -> "state-funded"
        }
        val origin = when (channel.provenance) {
            Provenance.OFFICIAL -> "official feed"
            Provenance.COMMUNITY -> "community listing"
        }
        val caveat = when (channel.verification) {
            Verification.SEGMENT -> null
            Verification.UNVERIFIED -> "not verified — may not play"
            Verification.FAILED -> "known not to play"
        }
        return listOfNotNull(channel.region, funding, origin, caveat).joinToString(" · ")
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
