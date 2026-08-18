// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/LiveChannelsTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import dev.mascwa.pulse.desktop.telemetry.LiveChannels.Funding
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.Provenance
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveChannelsTest {

    private fun channel(
        id: String,
        name: String = id,
        url: String = "https://example.test/$id/index.m3u8",
        language: String = "en",
        provenance: Provenance = Provenance.OFFICIAL,
        verification: Verification = Verification.SEGMENT,
        funding: Funding = Funding.COMMERCIAL,
    ) = LiveChannel(id, name, url, language, "Nowhere", provenance, verification, funding)

    @Test fun everyShippedChannelIsAnHlsPlaylistFromItsBroadcaster() {
        assertTrue("the list must not be empty", LiveChannels.CURATED.isNotEmpty())
        for (c in LiveChannels.CURATED) {
            assertTrue("${c.id} must be HLS — it is the only thing either player opens", LiveChannels.isHls(c.url))
            assertTrue("${c.id} must be https", c.url.startsWith("https://"))
            assertEquals("shipped channels are broadcasters' own feeds", Provenance.OFFICIAL, c.provenance)
            assertTrue("${c.id} must not ship known broken", c.verification != Verification.FAILED)
        }
        assertEquals("ids must be unique", LiveChannels.CURATED.size, LiveChannels.CURATED.map { it.id }.toSet().size)
    }

    @Test fun onlyTheNamedExceptionsShipWithoutHavingBeenWalkedToASegment() {
        // ⚠️ This pins the EXCEPTIONS, not the rule. The earlier version listed the confirmed
        // channels instead, which meant every single addition to the catalogue broke it for no
        // reason — and told you nothing, because "we added a channel and it works" is the ordinary
        // case. What is worth catching is the opposite: an entry shipped WITHOUT the evidence.
        //
        // Both below failed here in a way attributable to this machine's outbound proxy — a TLS
        // certificate that does not name the host it was served from, and a connection reset. Adding
        // a third means writing down why here, which is the point.
        val unverified = LiveChannels.CURATED
            .filter { it.verification != Verification.SEGMENT }
            .map { it.id }
            .toSet()
        assertEquals(setOf("presstv", "news-central"), unverified)
        assertTrue(
            "the catalogue is meant to be overwhelmingly verified, not overwhelmingly hopeful",
            unverified.size * 10 < LiveChannels.CURATED.size,
        )
    }

    @Test fun theCatalogueIsActuallyWorldwide() {
        // "Worldwide" is the whole point of the list, so it is worth CI holding rather than a claim
        // in a comment that nobody rechecks when entries are pruned.
        val regions = LiveChannels.CURATED.map { it.region }.toSet()
        assertTrue("expected many regions, got $regions", regions.size >= 15)
        for (r in listOf("United Kingdom", "Germany", "Qatar", "Japan", "India", "South Africa",
                         "Australia", "Canada", "United States", "China")) {
            assertTrue("$r must be represented", r in regions)
        }
        assertTrue(
            "the list is English-language news; a stray untagged entry is a bug",
            LiveChannels.CURATED.count { it.language == "en" } * 2 > LiveChannels.CURATED.size,
        )
        for (c in LiveChannels.CURATED) {
            assertTrue("${c.id} must state a language", c.language.isNotBlank())
            assertTrue("${c.id} must state a region", c.region.isNotBlank())
        }
    }

    @Test fun noShippedChannelComesFromADistributorPlatform() {
        // ⚠️ The owner's binding rule for this catalogue: a broadcaster's own endpoint, never a
        // third party's re-packaging of it. Encoded here because it is invisible on inspection —
        // every one of these is a perfectly good stream, so nothing else would catch a regression.
        //
        // The PATH matters as much as the host, and that is not a hypothetical: Reuters TV is served
        // from an ordinary-looking CloudFront distribution whose path reads
        // "amg00453-reuters-samsunggb", and both published NBC News NOW addresses carry an
        // "ads.xumo_channelId" parameter. Host-only screening admitted both.
        val markers = listOf(
            "amagi.tv", "samsung", "wurl", "tubi.video", "pluto.tv", "xumo", "rakuten",
            "githubusercontent.com", "github.io", "dai.google.com", "odysee", "distro.tv",
            "jmp2.uk", "uplynk.com", "zeasn",
        )
        for (c in LiveChannels.CURATED) {
            val url = c.url.lowercase()
            for (m in markers) {
                assertFalse("${c.id} is a $m re-packaging, not the broadcaster's own feed", m in url)
            }
        }
    }

    @Test fun aKnownBrokenChannelIsNeverOfferedHowevrPermissiveTheSettings() {
        // France 24 and NASA are the real instances: master 200, variant 400/404. There is no
        // setting under which offering one of those makes sense.
        val dead = channel("dead", verification = Verification.FAILED)
        assertFalse(LiveChannels.playable(dead, allowCommunity = true))
        assertFalse(LiveChannels.playable(dead, allowCommunity = false))
        assertTrue(LiveChannels.offer(listOf(dead), allowCommunity = true).isEmpty())
    }

    @Test fun communityChannelsAppearOnlyWhenAskedFor() {
        val list = listOf(
            channel("official"),
            channel("community", provenance = Provenance.COMMUNITY),
        )
        assertEquals(listOf("official"), LiveChannels.offer(list, allowCommunity = false).map { it.id })
        assertEquals(
            setOf("official", "community"),
            LiveChannels.offer(list, allowCommunity = true).map { it.id }.toSet(),
        )
    }

    @Test fun somethingThatIsNotAPlaylistIsNotAChannel() {
        assertFalse(LiveChannels.isHls("https://example.test/stream.mp4"))
        assertFalse(LiveChannels.isHls("https://example.test/watch?v=abc"))
        assertTrue(LiveChannels.isHls("https://example.test/index.m3u8"))
        // Query strings and fragments must not defeat the check — plenty of real streams carry them.
        assertTrue(LiveChannels.isHls("https://example.test/index.m3u8?token=xyz"))
        assertTrue(LiveChannels.isHls("https://example.test/INDEX.M3U8"))
        assertFalse(LiveChannels.playable(channel("x", url = "https://example.test/x.mp4"), true))
    }

    @Test fun confirmedChannelsSortAheadOfUnknownOnes() {
        val list = listOf(
            channel("zeta", name = "Zeta", verification = Verification.SEGMENT),
            channel("alpha", name = "Alpha", verification = Verification.UNVERIFIED),
            channel("beta", name = "Beta", verification = Verification.SEGMENT),
        )
        assertEquals(listOf("Beta", "Zeta", "Alpha"), LiveChannels.offer(list, false).map { it.name })
    }

    @Test fun theDescriptionWarnsExactlyWhenThereIsSomethingToWarnAbout() {
        assertFalse(LiveChannels.describe(channel("a")).contains("not verified"))
        assertTrue(
            LiveChannels.describe(channel("b", verification = Verification.UNVERIFIED))
                .contains("not verified"),
        )
        assertTrue(
            LiveChannels.describe(channel("c", provenance = Provenance.COMMUNITY))
                .contains("community"),
        )
    }

    @Test fun whoPaysForTheNewsroomIsStatedWhenItIsWorthStating() {
        assertEquals(
            "Nowhere · state-funded · official feed",
            LiveChannels.describe(channel("s", funding = Funding.STATE)),
        )
        assertEquals(
            "Nowhere · public broadcaster · official feed",
            LiveChannels.describe(channel("p", funding = Funding.PUBLIC)),
        )
        // ⚠️ Commercial says nothing, deliberately: it is the unremarkable case, and a badge on
        // every row is a badge nobody reads. This also keeps the caption unchanged for the entries
        // that predate the field.
        assertEquals("Nowhere · official feed", LiveChannels.describe(channel("c")))
    }

    @Test fun everyStateFundedChannelInTheCatalogueSaysSo() {
        val stated = LiveChannels.CURATED
            .filter { LiveChannels.describe(it).contains("state-funded") }
            .map { it.id }
            .toSet()
        val declared = LiveChannels.CURATED
            .filter { it.funding == Funding.STATE }
            .map { it.id }
            .toSet()
        assertEquals(declared, stated)
        // The specific ones, so removing the label from a government broadcaster is a build failure
        // rather than an edit nobody notices.
        assertTrue("cgtn" in stated)
        assertTrue("rt-news" in stated)
        assertTrue("presstv" in stated)
        assertTrue("aljazeera-en" in stated)
        assertFalse("a commercial broadcaster must not be labelled", "gb-news" in stated)
        assertFalse("a chartered public broadcaster is not the same thing", "bbc-news" in stated)
    }

    @Test fun aBreakingPopUpPrefersYourLanguageThenSomethingKnownToWork() {
        val list = listOf(
            channel("en-unverified", language = "en", verification = Verification.UNVERIFIED),
            channel("es-ok", language = "es", verification = Verification.SEGMENT),
            channel("en-ok", language = "en", verification = Verification.SEGMENT),
        )
        assertEquals("en-ok", LiveChannels.forBreaking(list, language = "en-GB")?.id)
        assertEquals("es-ok", LiveChannels.forBreaking(list, language = "es")?.id)
        // No match in the viewer's language falls back to something confirmed rather than to the
        // unverified one that happens to share the language.
        //
        // ⚠️ "en-ok", not "es-ok": the tie breaks on offer()'s ordering, which is confirmed-first
        // and then ALPHABETICAL, not declaration order. My first version of this assertion assumed
        // the latter, which is also what the function's own KDoc used to claim.
        assertEquals("en-ok", LiveChannels.forBreaking(list, language = "fr")?.id)
    }

    @Test fun theCostOfWatchingIsStatedOnlyWhenItIsKnown() {
        // 920 kbps * 60s / 8 bits / 1e6 = 6.9 MB per minute.
        assertEquals("about 6.9 MB a minute", LiveChannels.dataRateNote(920_000))
        // 400 kbps -> exactly 3.0; the tenth is kept so it doesn't read as a rounded-off integer.
        assertEquals("about 3.0 MB a minute", LiveChannels.dataRateNote(400_000))
        // 2.5 Mbps -> 18.75, and past ten a tenth of a megabyte is noise.
        assertEquals("about 19 MB a minute", LiveChannels.dataRateNote(2_500_000))
        // Format.NO_VALUE is -1: the player has not settled yet, so there is nothing to say. A
        // fabricated figure under a play button is worse than no figure.
        assertNull(LiveChannels.dataRateNote(-1))
        assertNull(LiveChannels.dataRateNote(0))
    }

    @Test fun aBreakingPopUpOpensNothingRatherThanSomethingDead() {
        // ⚠️ The rule that matters most here. A takeover fires at the moment something is happening;
        // spending that on a stream known not to play is worse than showing no video at all.
        assertNull(LiveChannels.forBreaking(emptyList()))
        assertNull(LiveChannels.forBreaking(listOf(channel("dead", verification = Verification.FAILED))))
        assertNull(
            "community channels must not sneak in through the breaking path",
            LiveChannels.forBreaking(
                listOf(channel("c", provenance = Provenance.COMMUNITY)),
                allowCommunity = false,
            ),
        )
        // But an unverified channel is better than nothing when it is all there is.
        assertNotNull(LiveChannels.forBreaking(listOf(channel("u", verification = Verification.UNVERIFIED))))
    }
}
