package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.core.telemetry.LiveChannels.Provenance
import dev.mascwa.pulse.core.telemetry.LiveChannels.Verification

/**
 * The community channel catalogue, read from an extended M3U playlist.
 *
 * ⚠️ **Opt-in, and the reason is not squeamishness.** This list is maintained by volunteers and its
 * entries are of mixed origin — some are unauthorised restreams of channels that are not free to
 * watch. The curated default is broadcasters' own public endpoints; this is a different kind of thing
 * and the app says so rather than merging the two silently.
 *
 * **Why the playlist rather than the JSON API**, measured rather than assumed:
 *
 * ```
 * api/channels.json + api/streams.json   13.8 MB   41,078 channels, needs joining and filtering
 * iptv/categories/news.m3u                215 KB      943 entries, already only news
 * ```
 *
 * Sixty-four times smaller, already the right subject, and it carries the name, the country and the
 * catalogue's own warnings in one line per channel. On a phone that difference is the whole
 * decision.
 *
 * Everything here is a filter with a stated reason. Of the 943 entries in the real file, 621 survive.
 */
object M3uCatalog {

    /** News, and only news. The catalogue publishes a playlist per category. */
    const val NEWS_URL: String = "https://iptv-org.github.io/iptv/categories/news.m3u"

    /**
     * The catalogue changes slowly and costs a fifth of a megabyte, so it is fetched rarely.
     *
     * A week, not a day: nobody is waiting on a new channel appearing, and the cost of being a few
     * days stale is one dead entry among hundreds — which the UI already treats as the normal case,
     * because none of these are verified.
     */
    const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000

    /** The catalogue's own markers for entries not worth offering. */
    private const val GEO_BLOCKED = "[Geo-blocked]"
    private const val NOT_24_7 = "[Not 24/7]"

    private val TVG_ID = Regex("""tvg-id="([^"]*)"""")
    private val COUNTRY = Regex("""\.([A-Za-z]{2})(?:@|$)""")
    /** A trailing resolution tag the catalogue appends to nearly every name: "BBC News (1080p)". */
    private val RESOLUTION = Regex("""\s*\((\d{3,4}[pi])\)\s*$""")
    private val BRACKET_TAG = Regex("""\s*\[[^\]]*\]""")
    private val SLUG_STRIP = Regex("""[^a-z0-9]+""")

    /**
     * Turn a playlist into channels the app can offer.
     *
     * Each entry is an `#EXTINF` line followed by its address. Anything that fails a rule is dropped
     * rather than carried with a caveat — the whole list is already unverified, so a second tier of
     * doubt inside it would mean nothing to a reader.
     *
     * @param cap the most to return. The real file yields hundreds; a caller showing them in a list
     *   wants a bound, and it is applied **after** ordering so "the first [cap]" means the first
     *   alphabetically rather than wherever they happened to sit in the file. [HARD_LIMIT] is the
     *   separate ceiling on how much is read at all.
     */
    fun parse(text: String, cap: Int = 1_000): List<LiveChannel> {
        val out = mutableListOf<LiveChannel>()
        val seenIds = HashSet<String>()
        val seenUrls = HashSet<String>()
        // ⚠️ The real playlist is served with CRLF endings, and a carriage return left on the end of
        // a URL breaks every single stream. What guarantees it does not is `lineSequence`, which
        // splits on \r\n, \n and \r alike — NOT the `trim` after it, which only handles stray spaces
        // around a line. Worth stating because the obvious reading is the wrong way round: a
        // perturbation test that removed the trim found nothing, because there was nothing there to
        // find.
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var i = 0
        while (i < lines.size && out.size < HARD_LIMIT) {
            val meta = lines[i]
            if (!meta.startsWith("#EXTINF")) {
                i++
                continue
            }
            val url = lines.getOrNull(i + 1)
            i += 2
            if (url == null || url.startsWith("#")) continue

            // https only. The phone blocks cleartext by default for good reasons, and a stream that
            // needs an exception is not worth widening the app's egress policy for.
            if (!url.startsWith("https://")) continue
            if (!LiveChannels.isHls(url)) continue
            // The catalogue's own warnings. Geo-blocked means it will not play from here, and an
            // entry that is off air most of the day is a tap that usually fails.
            if (meta.contains(GEO_BLOCKED) || meta.contains(NOT_24_7)) continue

            val rawName = meta.substringAfter(',', "").trim()
            val name = cleanName(rawName)
            if (name.isBlank()) continue

            val tvgId = TVG_ID.find(meta)?.groupValues?.get(1).orEmpty()
            val id = slug(tvgId.ifBlank { name })
            if (id.isBlank() || !seenIds.add(id)) continue
            if (!seenUrls.add(url)) continue

            out += LiveChannel(
                id = "community-$id",
                name = name,
                url = url,
                // The playlist carries no language. Left blank rather than guessed from the country,
                // which would be wrong wherever a broadcaster serves a diaspora — and a wrong
                // language tag would steer [LiveChannels.forBreaking] to the wrong channel.
                language = "",
                region = COUNTRY.find(tvgId)?.groupValues?.get(1)?.uppercase().orEmpty()
                    .ifBlank { "Unknown" },
                provenance = Provenance.COMMUNITY,
                // Nobody has walked any of these down to a video segment, and saying otherwise
                // would be the exact overconfidence the curated list's verification field exists
                // to prevent.
                verification = Verification.UNVERIFIED,
            )
        }
        return out.sortedBy { it.name.lowercase() }.take(cap.coerceAtLeast(0))
    }

    /**
     * How many entries are read at all, before ordering.
     *
     * Separate from `cap` so trimming for a list stays a display decision while this stays a bound on
     * a remote file we do not control. Far above the ~620 the real playlist yields.
     */
    const val HARD_LIMIT: Int = 5_000

    /** "BBC News (1080p) [Not 24/7]" -> "BBC News". */
    internal fun cleanName(raw: String): String =
        raw.replace(BRACKET_TAG, "").replace(RESOLUTION, "").trim()

    /** A stable, comparable id. Lower-cased, non-alphanumerics collapsed to a single dash. */
    internal fun slug(raw: String): String =
        raw.lowercase().replace(SLUG_STRIP, "-").trim('-')
}
