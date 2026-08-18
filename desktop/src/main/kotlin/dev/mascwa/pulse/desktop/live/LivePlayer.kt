package dev.mascwa.pulse.desktop.live

import dev.mascwa.pulse.desktop.telemetry.LiveChannels
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.LiveChannel
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live television on the desktop, played by JavaFX.
 *
 * ⚠️ **Everything that touches a [MediaPlayer] runs on the JavaFX application thread**, exactly as the
 * Android side confines ExoPlayer to the main thread. That is the single discipline this file exists
 * to hold; the public API is callable from anywhere and hops for you.
 *
 * JavaFX is reached through [JFXPanel] rather than a JavaFX `Stage` for two reasons. It embeds in
 * Swing, which is what both hosts here need — a detached `JFrame` and, later, a Compose `SwingPanel`.
 * And launching JavaFX any other way from a non-JavaFX `main` runs into the "JavaFX runtime
 * components are missing" check, which is a real trap when the jars sit on the classpath rather than
 * the module path, as they do here.
 *
 * **One player, several views.** JavaFX permits any number of [MediaView]s over one [MediaPlayer], so
 * the detached window and an embedded panel can show the same stream without either having to hand
 * the other a surface. Each host asks for a panel and gives it back when it is done.
 *
 * Hoisted above the composition in `Main.kt` and [dispose]d on close — the same rule as the settings
 * and study stores, and for a sharper reason: `exitApplication()` calls `System.exit(0)`, and a live
 * player left running owns a native decoder and a socket.
 */
class LivePlayer {

    enum class Status { IDLE, CONNECTING, PLAYING, ERROR }

    data class State(
        val channel: LiveChannel? = null,
        val status: Status = Status.IDLE,
        /** JavaFX's own error text on [Status.ERROR], so a dead stream is diagnosable. */
        val detail: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val toolkitStarted = AtomicBoolean(false)

    /** Touched only on the FX thread. */
    private var player: MediaPlayer? = null

    /** Every live view, so a channel change reaches all of them. Touched only on the FX thread. */
    private val views = mutableListOf<MediaView>()

    // ---- surfaces ---------------------------------------------------------------------------------

    /**
     * A Swing component showing whatever is playing.
     *
     * The scene is built on the FX thread after the panel exists, which is what starts the toolkit —
     * constructing a [JFXPanel] is itself a documented way to initialise JavaFX, and [ensureToolkit]
     * covers the case where nothing has yet.
     */
    fun createPanel(): JFXPanel {
        ensureToolkit()
        val panel = JFXPanel()
        Platform.runLater {
            runCatching {
                val view = MediaView(player)
                view.isPreserveRatio = true
                val root = StackPane(view)
                root.style = "-fx-background-color: black;"
                // The video fills whatever the host gives it, rather than staying at its native size
                // in the corner of a resized window.
                view.fitWidthProperty().bind(root.widthProperty())
                view.fitHeightProperty().bind(root.heightProperty())
                panel.scene = Scene(root, Color.BLACK)
                views += view
            }
        }
        return panel
    }

    /** Give a panel's view back. Safe to call for a panel that never finished building. */
    fun releasePanel(panel: JFXPanel) {
        runOnFx {
            runCatching {
                val scene = panel.scene ?: return@runCatching
                val root = scene.root as? StackPane ?: return@runCatching
                root.children.filterIsInstance<MediaView>().forEach { v ->
                    v.mediaPlayer = null
                    views.remove(v)
                }
                panel.scene = null
            }
        }
    }

    // ---- control ----------------------------------------------------------------------------------

    /** Tap a channel: play it, or stop if it is the one already playing. */
    fun toggle(channel: LiveChannel) {
        val s = _state.value
        val same = s.channel?.id == channel.id
        if (same && (s.status == Status.PLAYING || s.status == Status.CONNECTING)) stop()
        else play(channel)
    }

    fun play(channel: LiveChannel) {
        if (!LiveChannels.isHls(channel.url)) {
            _state.value = State(channel, Status.ERROR, "not a playable stream")
            return
        }
        ensureToolkit()
        _state.value = State(channel, Status.CONNECTING)
        runOnFx { open(channel) }
    }

    fun stop() {
        _state.value = State()
        runOnFx { release() }
    }

    /** Let go of the native decoder and the socket before the process dies. */
    fun dispose() {
        _state.value = State()
        runOnFx { release() }
    }

    // ---- the FX thread ----------------------------------------------------------------------------

    /** Build the player for [channel]. FX thread only. */
    private fun open(channel: LiveChannel) {
        // A newer channel may have superseded this one between the tap and this running — the same
        // guard the Android controller carries, and for the same reason.
        if (_state.value.channel?.id != channel.id) return
        release()
        runCatching {
            val media = Media(channel.url)
            val mp = MediaPlayer(media)
            mp.setOnError {
                if (_state.value.channel?.id != channel.id) return@setOnError
                fail(channel, mp.error?.message ?: media.error?.message)
            }
            mp.setOnReady {
                if (_state.value.channel?.id != channel.id) return@setOnReady
                _state.value = State(channel, Status.PLAYING)
            }
            // A live stream has no end to loop back to, and JavaFX's default is to stop at it.
            mp.setOnEndOfMedia { fail(channel, "the stream ended") }
            player = mp
            views.forEach { it.mediaPlayer = mp }
            mp.play()
        }.onFailure { fail(channel, it.message) }
    }

    /**
     * A failed channel: keep the error on screen, but let go of the player.
     *
     * Nothing retries a JavaFX media failure — [MediaPlayer] is not re-preparable the way ExoPlayer
     * is, so recovering means building a new one, which is what tapping the channel again does.
     */
    private fun fail(channel: LiveChannel, reason: String?) {
        release()
        _state.value = State(channel, Status.ERROR, reason)
    }

    /** FX thread only. */
    private fun release() {
        views.forEach { runCatching { it.mediaPlayer = null } }
        runCatching { player?.stop() }
        runCatching { player?.dispose() }
        player = null
    }

    private fun runOnFx(block: () -> Unit) {
        ensureToolkit()
        if (Platform.isFxApplicationThread()) block() else runCatching { Platform.runLater(block) }
    }

    /**
     * Start the JavaFX toolkit once.
     *
     * ⚠️ `Platform.startup` throws if the toolkit is already running — which it will be as soon as any
     * [JFXPanel] has been constructed — so the throw is the ordinary case rather than a failure, and
     * `setImplicitExit(false)` runs either way. Without it, closing the last JavaFX surface shuts the
     * toolkit down for good and the next channel would never open.
     */
    private fun ensureToolkit() {
        if (!toolkitStarted.compareAndSet(false, true)) return
        runCatching { Platform.startup {} }
        runCatching { Platform.setImplicitExit(false) }
    }
}
