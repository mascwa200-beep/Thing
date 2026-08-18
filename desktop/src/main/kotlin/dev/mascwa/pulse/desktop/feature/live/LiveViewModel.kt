package dev.mascwa.pulse.desktop.feature.live

import dev.mascwa.pulse.desktop.live.LivePlayer
import dev.mascwa.pulse.desktop.live.LiveWindow
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.LiveChannel
import kotlinx.coroutines.flow.StateFlow

/**
 * The LIVE screen's half of the player.
 *
 * Thin by design: [LivePlayer] holds the state and every thread rule. What lives here is only the
 * question of *where* the picture appears — in the page, or in a window of its own.
 *
 * JavaFX allows several views over one player, so popping out does not move the stream: it adds a
 * second view of the same one. That is why there is no "dock it back" — nothing was undocked.
 */
class LiveViewModel(private val player: LivePlayer) {

    val state: StateFlow<LivePlayer.State> = player.state

    /** Give the player a Swing component to draw into. The screen returns it on dispose. */
    fun createPanel() = player.createPanel()

    fun releasePanel(panel: javafx.embed.swing.JFXPanel) = player.releasePanel(panel)

    /** Play in the page. */
    fun watch(channel: LiveChannel) = player.play(channel)

    /** Show whatever is playing in a window as well — for a second monitor. */
    fun popOut() {
        val channel = state.value.channel ?: return
        LiveWindow.open(player, channel)
    }

    fun stop() {
        LiveWindow.close(player)
        player.stop()
    }

    /**
     * Leaving the screen.
     *
     * ⚠️ Stops playback **unless it has been popped out**, which is the one case where the user has
     * said they want it to outlive the page. Otherwise a live stream would keep running with nothing
     * on screen to show for it — the same rule the phone applies when its surface goes away.
     */
    fun onLeave() {
        if (!LiveWindow.isOpen) player.stop()
    }
}
