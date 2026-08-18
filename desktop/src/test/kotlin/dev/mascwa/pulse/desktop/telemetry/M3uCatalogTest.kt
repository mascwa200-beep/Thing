// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/M3uCatalogTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import dev.mascwa.pulse.desktop.telemetry.LiveChannels.Provenance
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are lines taken verbatim from the real playlist, including its CRLF endings — every rule
 * below was written after running the shipped parser over the actual 943-entry file, where 621
 * survive.
 */
class M3uCatalogTest {

    /**
     * ⚠️ Note the **trailing** "\r\n". Without it the final URL is the one line in the file with no
     * carriage return after it — so a fixture that ends on a URL tests the one case that cannot
     * fail. That is exactly how the CRLF assertion below was passing for the wrong reason.
     */
    private fun playlist(vararg entries: String) =
        (listOf("#EXTM3U x-tvg-url=\"https://example.test/guide.xml.gz\"") + entries.toList())
            .joinToString("\r\n") + "\r\n"

    private val bbc =
        "#EXTINF:-1 tvg-id=\"BBCNews.uk@SD\" group-title=\"News\",BBC News (1080p)\r\n" +
            "https://example.test/bbc/index.m3u8"

    @Test fun aChannelIsReadWithItsNameCountryAndProvenance() {
        val out = M3uCatalog.parse(playlist(bbc))
        assertEquals(1, out.size)
        val c = out.first()
        assertEquals("BBC News", c.name)
        assertEquals("UK", c.region)
        assertEquals(Provenance.COMMUNITY, c.provenance)
        // Nobody has walked any of these to a video segment. Claiming otherwise would be exactly the
        // overconfidence the verification field exists to prevent.
        assertEquals(Verification.UNVERIFIED, c.verification)
        // The playlist carries no language, and guessing one from the country would misdirect
        // forBreaking wherever a broadcaster serves a diaspora.
        assertEquals("", c.language)
    }

    /**
     * ⚠️ The real file is served with CRLF endings. A carriage return left on the end of a URL breaks
     * every single stream, and nothing downstream would say why.
     *
     * What actually guarantees this is `lineSequence`, which splits on \r\n as well as \n — not the
     * `trim` that follows it. Established by perturbation: removing the trim broke nothing, so the
     * comment that used to credit it was wrong.
     */
    @Test fun carriageReturnsDoNotEndUpInsideUrls() {
        val out = M3uCatalog.parse(playlist(bbc))
        assertEquals("https://example.test/bbc/index.m3u8", out.first().url)
        assertTrue(out.first().url.none { it == '\r' || it == '\n' })
    }

    @Test fun theCataloguesOwnWarningsAreHonoured() {
        val text = playlist(
            "#EXTINF:-1 tvg-id=\"A.es@SD\" group-title=\"News\",Alpha (1080p) [Geo-blocked]\r\n" +
                "https://example.test/a/index.m3u8",
            "#EXTINF:-1 tvg-id=\"B.es@SD\" group-title=\"News\",Beta (720p) [Not 24/7]\r\n" +
                "https://example.test/b/index.m3u8",
            bbc,
        )
        // Geo-blocked will not play from here at all, and an entry off air most of the day is a tap
        // that usually fails. Both are the catalogue telling us, and both are believed.
        assertEquals(listOf("BBC News"), M3uCatalog.parse(text).map { it.name })
    }

    @Test fun onlyHttpsHlsSurvives() {
        val text = playlist(
            "#EXTINF:-1 tvg-id=\"C.us@SD\",Cleartext (1080p)\r\nhttp://example.test/c/index.m3u8",
            "#EXTINF:-1 tvg-id=\"D.us@SD\",Not A Playlist (1080p)\r\nhttps://example.test/d/stream.mp4",
            bbc,
        )
        assertEquals(listOf("BBC News"), M3uCatalog.parse(text).map { it.name })
    }

    @Test fun duplicatesAreDroppedByIdAndByAddress() {
        val text = playlist(
            bbc,
            // Same channel id, different address — one entry per channel.
            "#EXTINF:-1 tvg-id=\"BBCNews.uk@SD\",BBC News (720p)\r\nhttps://example.test/bbc2/index.m3u8",
            // Different id, same address — the same stream twice under two names.
            "#EXTINF:-1 tvg-id=\"BBCWorld.uk@SD\",BBC World (1080p)\r\nhttps://example.test/bbc/index.m3u8",
        )
        assertEquals(1, M3uCatalog.parse(text).size)
    }

    @Test fun namesLoseTheirResolutionAndTagsButNothingElse() {
        assertEquals("BBC News", M3uCatalog.cleanName("BBC News (1080p)"))
        assertEquals("France 24", M3uCatalog.cleanName("France 24 (720p) [Not 24/7]"))
        // A parenthesis that is not a resolution is part of the name and stays.
        assertEquals("NHK World (Japan)", M3uCatalog.cleanName("NHK World (Japan)"))
        assertEquals("Al Jazeera", M3uCatalog.cleanName("Al Jazeera"))
    }

    @Test fun theListIsOrderedByNameAndTheCapAppliesAfterThat() {
        val text = playlist(
            "#EXTINF:-1 tvg-id=\"Z.us@SD\",Zulu (1080p)\r\nhttps://example.test/z/index.m3u8",
            "#EXTINF:-1 tvg-id=\"A.us@SD\",Alpha (1080p)\r\nhttps://example.test/a/index.m3u8",
            "#EXTINF:-1 tvg-id=\"M.us@SD\",Mike (1080p)\r\nhttps://example.test/m/index.m3u8",
        )
        assertEquals(listOf("Alpha", "Mike", "Zulu"), M3uCatalog.parse(text).map { it.name })
        // ⚠️ The cap trims the ORDERED list, not the file order. My first version capped while
        // parsing, so "the first two" meant whichever two came first in the file — which the KDoc
        // already claimed otherwise.
        assertEquals(listOf("Alpha", "Mike"), M3uCatalog.parse(text, cap = 2).map { it.name })
    }

    @Test fun aCommunityChannelIsStillGatedOnTheOptIn() {
        val out = M3uCatalog.parse(playlist(bbc))
        // The whole reason the catalogue is a switch: offer() must not surface any of it by default.
        assertTrue(LiveChannels.offer(out, allowCommunity = false).isEmpty())
        assertEquals(1, LiveChannels.offer(out, allowCommunity = true).size)
    }

    @Test fun rubbishIsNotAChannel() {
        assertTrue(M3uCatalog.parse("").isEmpty())
        assertTrue(M3uCatalog.parse("#EXTM3U").isEmpty())
        // An EXTINF with no address after it, and an address with no EXTINF before it.
        assertTrue(M3uCatalog.parse("#EXTINF:-1,Orphan").isEmpty())
        assertTrue(M3uCatalog.parse("https://example.test/loose/index.m3u8").isEmpty())
        // A comment where the address should be is not an address.
        assertTrue(M3uCatalog.parse("#EXTINF:-1,Alpha\r\n#EXTINF:-1,Beta").isEmpty())
    }
}
