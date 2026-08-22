package dev.mascwa.pulse.desktop.radio

import dev.mascwa.pulse.data.radio.RadioStation
import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Internet radio, through JavaFX.
 *
 * The same shape as [dev.mascwa.pulse.desktop.live.LivePlayer] and for the same reasons — one player,
 * everything touching it on the FX thread, the toolkit started once — but **audio only**, so there is
 * no [javafx.scene.media.MediaView] and nothing to attach to a window. That is the difference that
 * matters: a station keeps playing while you read the library or the news, because nothing about it
 * is tied to a surface that has to stay on screen.
 *
 * ⚠️ **What JavaFX can and cannot play, and why the screen says so.** JavaFX Media supports MP3, AAC
 * in MPEG-4, WAV and a short list besides — it does NOT decode Ogg Vorbis or Opus, and a great many
 * community stations broadcast in exactly those. A station that will not open therefore reports its
 * codec rather than a bare failure, because "your machine cannot decode Opus" and "that station is
 * down" are different facts and look identical without it.
 *
 * ⚠️ The phone plays the same stations through ExoPlayer, which decodes far more. Saying that here
 * beats leaving somebody to conclude the app is broken on Windows.
 */
class RadioPlayer {

    enum class Status { IDLE, CONNECTING, PLAYING, ERROR }

    data class State(
        val station: RadioStation? = null,
        val status: Status = Status.IDLE,
        /** JavaFX's own error text, so a dead stream is diagnosable rather than merely dead. */
        val detail: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val toolkitStarted = AtomicBoolean(false)

    /** Touched only on the FX thread. */
    private var player: MediaPlayer? = null

    /**
     * ⚠️ Volume is held here rather than only on the player, because the player is destroyed and
     * rebuilt on every tune. Keeping it on the player alone would silently reset the volume to full
     * each time somebody changed station.
     */
    @Volatile
    private var volume: Double = 0.8

    val currentVolume: Double get() = volume

    /** Tap a station: play it, or stop if it is the one already playing. */
    fun toggle(station: RadioStation) {
        val s = _state.value
        val same = s.station?.sameStation(station) == true
        if (same && (s.status == Status.PLAYING || s.status == Status.CONNECTING)) stop()
        else play(station)
    }

    fun play(station: RadioStation) {
        if (station.streamUrl.isBlank()) {
            _state.value = State(station, Status.ERROR, "that station has no stream address")
            return
        }
        ensureToolkit()
        _state.value = State(station, Status.CONNECTING)
        runOnFx { open(station) }
    }

    fun stop() {
        _state.value = State()
        runOnFx { release() }
    }

    fun setVolume(value: Double) {
        volume = value.coerceIn(0.0, 1.0)
        runOnFx { runCatching { player?.volume = volume } }
    }

    /** Let go of the native decoder and the socket before the process dies. */
    fun dispose() {
        _state.value = State()
        runOnFx { release() }
    }

    // ---- the FX thread ----------------------------------------------------------------------------

    /** FX thread only. */
    private fun open(station: RadioStation) {
        // A newer station may have superseded this one between the tap and this running.
        if (_state.value.station?.sameStation(station) != true) return
        release()
        runCatching {
            val media = Media(station.streamUrl)
            val mp = MediaPlayer(media)
            mp.volume = volume
            mp.setOnError {
                if (_state.value.station?.sameStation(station) != true) return@setOnError
                fail(station, mp.error?.message ?: media.error?.message)
            }
            mp.setOnReady {
                if (_state.value.station?.sameStation(station) != true) return@setOnReady
                _state.value = State(station, Status.PLAYING)
            }
            // A live stream has no end to loop back to, and JavaFX's default is to stop at it.
            mp.setOnEndOfMedia { fail(station, "the stream ended") }
            player = mp
            mp.play()
        }.onFailure { fail(station, it.message) }
    }

    /**
     * A failed station: keep the reason on screen, but let go of the player.
     *
     * Nothing retries automatically — a JavaFX [MediaPlayer] is not re-preparable the way ExoPlayer
     * is, so recovering means building a new one, which is what tapping the station again does.
     */
    private fun fail(station: RadioStation, reason: String?) {
        release()
        _state.value = State(station, Status.ERROR, explain(station, reason))
    }

    /**
     * Turn JavaFX's message into one somebody can act on.
     *
     * ⚠️ The codec case is the important one. A station broadcasting Opus or Ogg Vorbis fails with a
     * generic "media unsupported", and without naming the codec the reader has no way to tell that
     * from a station that is simply off the air.
     */
    private fun explain(station: RadioStation, reason: String?): String {
        val codec = station.codec.trim().lowercase()
        val undecodable = codec.contains("ogg") || codec.contains("vorbis") || codec.contains("opus")
        return when {
            undecodable ->
                "This machine cannot decode ${station.codec.uppercase()} — Windows media does not " +
                    "include it. The station is probably fine; try one broadcasting MP3 or AAC."
            reason.isNullOrBlank() -> "That stream would not open."
            else -> reason
        }
    }

    /** FX thread only. */
    private fun release() {
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
     * ⚠️ `Platform.startup` throws if the toolkit is already running — which it will be as soon as the
     * live-TV panel has been constructed — so the throw is the ordinary case rather than a failure,
     * and `setImplicitExit(false)` runs either way. Without it, closing the last JavaFX surface shuts
     * the toolkit down for good and the next station would never open.
     */
    private fun ensureToolkit() {
        if (!toolkitStarted.compareAndSet(false, true)) return
        runCatching { Platform.startup {} }
        runCatching { Platform.setImplicitExit(false) }
    }
}
