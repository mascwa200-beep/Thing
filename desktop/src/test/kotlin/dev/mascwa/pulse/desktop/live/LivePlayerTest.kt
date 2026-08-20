package dev.mascwa.pulse.desktop.live

import dev.mascwa.pulse.core.telemetry.LiveChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What can honestly be tested without a screen.
 *
 * ⚠️ Deliberately narrow. Every other path in [LivePlayer] starts the JavaFX toolkit, and this suite
 * runs on a headless CI runner — a test that reached the toolkit would at best be meaningless and at
 * worst hang the build. Playback itself is owner-verify on Windows, and saying so is better than a
 * test that only appears to cover it.
 *
 * What IS worth pinning is the ordering: the not-a-stream guard sits **before** the toolkit starts,
 * so a bad address costs nothing at all rather than spinning up a media stack to reject it.
 */
class LivePlayerTest {

    private fun channel(url: String) = LiveChannels.LiveChannel(
        id = "probe", name = "Probe", url = url, language = "en", region = "Nowhere",
        provenance = LiveChannels.Provenance.OFFICIAL,
        verification = LiveChannels.Verification.SEGMENT,
    )

    @Test
    fun somethingThatIsNotAStreamIsRejectedBeforeAnythingIsStarted() {
        val player = LivePlayer()
        assertEquals(LivePlayer.Status.IDLE, player.state.value.status)

        player.play(channel("https://example.test/not-a-playlist.mp4"))

        val state = player.state.value
        assertEquals(LivePlayer.Status.ERROR, state.status)
        assertEquals("not a playable stream", state.detail)
        // Reaching this line at all is the assertion: had the guard sat after the toolkit call, this
        // test would have started JavaFX on a machine with no display.
    }

    @Test
    fun noWindowIsOpenUntilOneIsAskedFor() {
        assertFalse(LiveWindow.isOpen)
    }
}
