package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.core.telemetry.LiveChannels.Provenance
import dev.mascwa.pulse.core.telemetry.LiveChannels.Verification
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
    ) = LiveChannel(id, name, url, language, "Nowhere", provenance, verification)

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

    @Test fun theThreeChannelsWalkedToASegmentAreMarkedAsSuch() {
        // These are the ones a real video segment was retrieved from; the rest are honest unknowns.
        val confirmed = LiveChannels.CURATED.filter { it.verification == Verification.SEGMENT }.map { it.id }
        assertEquals(listOf("dw-en", "dw-es", "cna"), confirmed)
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
