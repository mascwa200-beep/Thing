package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemModelTest {

    private val now = 1_700_000_000_000L

    private fun item(expires: Long, url: String = "https://example.invalid/s.m3u8") =
        MediaItem(id = "abc", title = "A video", streamUrl = url, expiresAtMs = expires)

    @Test
    fun anAddressWithPlentyOfTimeLeftIsFresh() {
        assertTrue(item(now + 3_600_000).isFresh(now))
    }

    @Test
    fun anExpiredAddressIsNotFresh() {
        assertFalse(item(now - 1).isFresh(now))
    }

    /**
     * ⚠️ **Unknown expiry counts as STALE, and the optimistic reading is the trap.** An extractor
     * that reported no expiry is precisely the case where the URL is most likely to be a short-lived
     * signed one. Re-resolving costs a second; guessing wrong costs a playback failure the user has
     * to work out for themselves.
     */
    @Test
    fun anUnknownExpiryIsTreatedAsStale() {
        assertFalse(item(expires = 0).isFresh(now))
    }

    /**
     * ⚠️ The margin exists so a stream cannot die PART-WAY THROUGH. An address valid for another
     * thirty seconds passes a naive `now < expiresAtMs` check and then stops mid-video, which is far
     * harder to explain than a video that never started.
     */
    @Test
    fun anAddressAboutToExpireIsAlreadyStale() {
        val nearlyGone = item(now + 30_000)
        assertTrue("the naive check would have allowed it", now < nearlyGone.expiresAtMs)
        assertFalse(nearlyGone.isFresh(now))
        // Just past the margin it is usable again, so the rule is a margin and not a blanket refusal.
        assertTrue(item(now + MediaItem.FRESHNESS_MARGIN_MS + 1_000).isFresh(now))
    }

    /** An item nothing has resolved yet is never fresh, whatever its stated expiry. */
    @Test
    fun anUnresolvedItemIsNeverFresh() {
        val known = MediaItem(id = "abc", title = "A video", expiresAtMs = now + 3_600_000)
        assertFalse(known.isResolved)
        assertFalse(known.isFresh(now))
    }

    /** Audio-only counts as resolved — listening without the picture is a real way to play it. */
    @Test
    fun anAudioOnlyAddressCountsAsResolved() {
        val audio = MediaItem(
            id = "abc", title = "A video",
            audioUrl = "https://example.invalid/a.m4a", expiresAtMs = now + 3_600_000,
        )
        assertTrue(audio.isResolved)
        assertTrue(audio.isFresh(now))
    }

    /**
     * Every refusal says something different.
     *
     * ⚠️ The whole reason [MediaResolution] is an outcome rather than a thrown exception is that
     * extraction fails routinely and for reasons the user can act on differently — private, blocked,
     * a site change, Python missing. One sentence for all of them is the "something went wrong" this
     * app has already corrected in the reader, the safety feed and the interrogator.
     */
    @Test
    fun everyRefusalReasonSaysSomethingDistinct() {
        val said = MediaResolution.Reason.entries.map { MediaResolution.say(it) }
        assertEquals(said.toSet().size, said.size)
        said.forEach { assertTrue(it, it.isNotBlank()) }
    }
}
