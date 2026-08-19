package dev.mascwa.pulse.feature.live

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.mascwa.pulse.core.telemetry.LiveChannels
import dev.mascwa.pulse.core.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.core.telemetry.MediaFloor
import dev.mascwa.pulse.feature.media.AudioFloor
import dev.mascwa.pulse.feature.media.MediaHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Live television news: one [ExoPlayer], process-wide, shaped after `RadioController`.
 *
 * The same disciplines, because they were all learned from real failures on that path: the player is
 * touched only on the main thread (it is thread-affine), every callback re-checks that the channel
 * it fires for is still the tuned one (a fast switch could otherwise let an older stream's error
 * bury a newer stream's success), a transient IO error is re-prepared a couple of times before being
 * surfaced, and a permanent failure **releases the player** rather than leaving it holding a decoder
 * and audio focus for a stream that will never come back.
 *
 * **Three deliberate differences from the radio.**
 *
 * 1. **No foreground service.** Radio earns one: audio is worth keeping when the screen is off.
 *    Video with no visible surface is pure data spent on pixels nobody sees, so playback ends when
 *    the screen holding it goes away. That is also why no manifest change was needed.
 * 2. **The lowest variant, always.** `setForceLowestBitrate` rather than the adaptive ladder — the
 *    owner's brief was explicitly that quality is unimportant, and a news channel at 480×270 is
 *    entirely legible while costing a fraction of the data.
 * 3. **It asks [AudioFloor] before it plays**, so starting video visibly stops the radio and says
 *    so, instead of the two silently fighting over audio focus.
 */
object LiveVideoController {

    enum class Status { IDLE, CONNECTING, PLAYING, ERROR }

    /**
     * @param detail the player's own error-code name on [Status.ERROR], so a failure is diagnosable
     *   rather than just "it didn't work".
     */
    data class LiveState(
        val channel: LiveChannel? = null,
        val status: Status = Status.IDLE,
        val detail: String? = null,
    )

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    /**
     * What the stream is costing, once the player has settled on a variant — see
     * [LiveChannels.dataRateNote]. Null until then, and null again as soon as playback stops.
     */
    private val _dataRate = MutableStateFlow<String?>(null)
    val dataRate: StateFlow<String?> = _dataRate.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: SurfaceView? = null
    private var rateJob: Job? = null

    private var retries = 0
    private const val MAX_RETRIES = 2

    /** How long to let the player settle before asking what it actually chose to receive. */
    private const val RATE_SETTLE_MS = 6_000L

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ---- control ---------------------------------------------------------------------------------

    /** Tap a channel: play it, or stop if it is the one already playing. */
    fun toggle(context: Context, channel: LiveChannel) {
        val s = _state.value
        val same = s.channel?.id == channel.id
        if (same && (s.status == Status.PLAYING || s.status == Status.CONNECTING)) stop(context)
        else play(context, channel)
    }

    fun play(context: Context, channel: LiveChannel) {
        val app = context.applicationContext
        // Nothing but HLS ever reaches the player. The catalogue already guarantees it; this is the
        // guard for anything that arrives later from the opt-in catalogue.
        if (!LiveChannels.isHls(channel.url)) {
            _state.value = LiveState(channel, Status.ERROR, "not a playable stream")
            return
        }
        // Ask for the speaker BEFORE building anything — this is what stops the radio.
        AudioFloor.claim(app, MediaFloor.Owner.VIDEO)
        _state.value = LiveState(channel, Status.CONNECTING)
        _dataRate.value = null
        runOnMain { startPlayer(app, channel) }
    }

    fun stop(context: Context) {
        rateJob?.cancel()
        _dataRate.value = null
        runOnMain { releasePlayerInternal() }
        _state.value = LiveState()
        AudioFloor.released(MediaFloor.Owner.VIDEO)
    }

    // ---- the surface ------------------------------------------------------------------------------

    /**
     * Give the player somewhere to draw.
     *
     * Held rather than passed through, because the player can be rebuilt underneath the screen (a
     * retry does exactly that) and must find its way back to the same view.
     */
    fun attach(view: SurfaceView) {
        surface = view
        runOnMain { runCatching { player?.setVideoSurfaceView(view) } }
    }

    /**
     * Release [view], if it is still the one in use.
     *
     * ⚠️ Identity-guarded on purpose. A composable being disposed after its replacement has already
     * attached would otherwise clear the surface the new one just set, and the symptom — audio with
     * a black rectangle — looks nothing like its cause.
     */
    fun detach(view: SurfaceView) {
        if (surface !== view) return
        surface = null
        runOnMain { runCatching { player?.clearVideoSurfaceView(view) } }
    }

    // ---- playback ---------------------------------------------------------------------------------

    /** Build (or rebuild) the player for [channel]. Main thread only. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun startPlayer(app: Context, channel: LiveChannel) {
        // A newer channel may have superseded this one while we posted to main — don't clobber it.
        if (_state.value.channel?.id != channel.id) return
        retries = 0
        releasePlayerInternal()
        runCatching {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(MediaHttp.BROWSER_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(MediaHttp.TIMEOUT_MS)
                .setReadTimeoutMs(MediaHttp.TIMEOUT_MS)
            val exo = ExoPlayer.Builder(app)
                .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, httpFactory)))
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .build()
            // The cheapest rendition in the ladder, and no climbing out of it.
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setForceLowestBitrate(true)
                .build()
            exo.setMediaItem(MediaItem.fromUri(channel.url))
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (_state.value.channel?.id != channel.id) return
                    when (playbackState) {
                        Player.STATE_READY -> {
                            // ⚠️ The retry budget is spent per OUTAGE, not per tune. Playing again
                            // means the outage that spent it is over, so the count goes back to zero.
                            // Without this the counter only ever reset in `startPlayer`, so a channel
                            // left on for hours accumulated across unrelated blips: the first two
                            // recovered, and the third — no less recoverable than the first — was
                            // treated as permanent and released the player.
                            retries = 0
                            _state.value = LiveState(channel, Status.PLAYING)
                            watchDataRate(channel)
                        }
                        // A live channel has no end; a brief drop often survives a re-prepare.
                        Player.STATE_ENDED -> retryOrFail(app, channel, "stream ended")
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (_state.value.channel?.id != channel.id) return
                    if (isTransient(error)) retryOrFail(app, channel, error.errorCodeName)
                    else failPermanently(channel, error.errorCodeName)
                }
            })
            surface?.let { exo.setVideoSurfaceView(it) }
            exo.playWhenReady = true
            exo.prepare()
            player = exo
        }.onFailure { failPermanently(channel, it.message) }
    }

    /**
     * Network/IO errors are worth a retry; parse, decoder and bad-HTTP errors are not.
     *
     * ⚠️ **`BEHIND_LIVE_WINDOW` is named explicitly because it is the one recoverable live error that
     * does not live in the IO range.** A live HLS manifest is a sliding window; a stall, a brief
     * background, or a network hiccup can leave the playback position behind the oldest segment the
     * server still publishes, and the player reports code 1002. It is the commonest interruption a
     * long-running live stream suffers and it recovers completely — but 1002 sits below the 2000..2002
     * IO band, so it was falling through to [failPermanently], releasing the player and leaving an
     * error on screen for a channel that was perfectly fine a second later.
     *
     * The existing recovery path is already the correct handling and needs no seek: `setMediaItem`
     * delegates to `setMediaItems(list, resetPosition = true)` (confirmed in the shipped media3 1.5.1
     * bytecode), so re-preparing discards the stale position rather than returning to it.
     *
     * Named rather than folded into a wider range on purpose — widening upward would also sweep in
     * `ERROR_CODE_TIMEOUT` (1003) and `ERROR_CODE_FAILED_RUNTIME_CHECK` (1004), which are not
     * recoverable by re-preparing and would just spend the budget before failing anyway.
     */
    private fun isTransient(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
            error.errorCode in
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    private fun retryOrFail(app: Context, channel: LiveChannel, reason: String) {
        if (retries >= MAX_RETRIES) {
            failPermanently(channel, reason)
            return
        }
        retries++
        _state.value = LiveState(channel, Status.CONNECTING, "reconnecting…")
        scope.launch {
            delay(1_500)
            runOnMain {
                if (_state.value.channel?.id != channel.id) return@runOnMain
                val exo = player
                if (exo == null) {
                    failPermanently(channel, reason)
                    return@runOnMain
                }
                runCatching {
                    exo.setMediaItem(MediaItem.fromUri(channel.url))
                    exo.prepare()
                    exo.playWhenReady = true
                }.onFailure { failPermanently(channel, reason) }
            }
        }
    }

    /**
     * A permanently failed channel: keep the error on screen, but release everything.
     *
     * The floor is released too. Nothing is playing, so the radio must be free to take the speaker
     * without first being told to stop a video that is already dead.
     */
    private fun failPermanently(channel: LiveChannel, reason: String?) {
        rateJob?.cancel()
        _dataRate.value = null
        runOnMain { releasePlayerInternal() }
        _state.value = LiveState(channel, Status.ERROR, reason)
        AudioFloor.released(MediaFloor.Owner.VIDEO)
    }

    /** Ask the player what it settled on, once it has had time to settle. */
    private fun watchDataRate(channel: LiveChannel) {
        rateJob?.cancel()
        rateJob = scope.launch {
            delay(RATE_SETTLE_MS)
            runOnMain {
                if (_state.value.channel?.id != channel.id) return@runOnMain
                val format: Format? = runCatching { player?.videoFormat }.getOrNull()
                val bps = format?.bitrate ?: Format.NO_VALUE
                _dataRate.value = LiveChannels.dataRateNote(bps)
            }
        }
    }

    /** MUST run on the main thread — ExoPlayer is single-thread-affine. */
    private fun releasePlayerInternal() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}
