package dev.mascwa.pulse.desktop.live

import dev.mascwa.pulse.core.telemetry.LiveChannels.LiveChannel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Live television in a window of its own.
 *
 * Deliberately the first of the two hosts to exist, and not only because it is simpler. A window you
 * can put on a second monitor and leave running is what live news is actually for, and it sidesteps
 * Compose Desktop's Swing interop entirely — a `SwingPanel` inside a Compose layout is unexercised
 * ground in this module, whereas a `JFrame` holding a `JFXPanel` is the plainest thing either
 * toolkit does.
 *
 * One window at a time. A second is not useful — there is one player behind it — and two windows
 * over one stream would only raise the question of which one closing should stop it.
 */
object LiveWindow {

    private var frame: JFrame? = null

    /** Whether a detached window is currently up, for a host that wants to say so. */
    val isOpen: Boolean get() = frame != null

    /**
     * Show [channel] in the detached window, opening it if it is not already up.
     *
     * Built on the Swing event thread, which is where every `JFrame` belongs — `createPanel` then
     * hops to the JavaFX thread on its own.
     */
    fun open(player: LivePlayer, channel: LiveChannel) {
        onSwing {
            val existing = frame
            if (existing != null) {
                existing.title = windowTitle(channel)
                existing.toFront()
                player.play(channel)
                return@onSwing
            }
            val panel = player.createPanel()
            val f = JFrame(windowTitle(channel))
            f.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
            f.background = Color.BLACK
            f.contentPane.background = Color.BLACK
            f.contentPane.layout = BorderLayout()
            f.contentPane.add(panel, BorderLayout.CENTER)
            // 16:9 at a size that fits beside the main window rather than covering it.
            f.minimumSize = Dimension(480, 270)
            f.size = Dimension(854, 480)
            f.setLocationByPlatform(true)
            f.addWindowListener(object : WindowAdapter() {
                // Closing the window stops the stream. Anything else leaves a live decoder and a
                // socket running with nothing on screen to show for it.
                override fun windowClosing(e: WindowEvent?) {
                    player.stop()
                    player.releasePanel(panel)
                    frame = null
                    f.dispose()
                }
            })
            frame = f
            f.isVisible = true
            player.play(channel)
        }
    }

    /** Close the window if it is open, stopping playback with it. */
    fun close(player: LivePlayer) {
        onSwing {
            val f = frame ?: return@onSwing
            player.stop()
            frame = null
            f.dispose()
        }
    }

    private fun windowTitle(channel: LiveChannel): String = "LCARS · ${channel.name}"

    private fun onSwing(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
}
