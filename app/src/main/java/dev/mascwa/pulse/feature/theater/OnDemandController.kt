package dev.mascwa.pulse.feature.theater

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.mascwa.pulse.core.telemetry.MediaFloor
import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.core.telemetry.SponsorSegments
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
 * On-demand playback: one [ExoPlayer], process-wide, shaped after `LiveVideoController` — the same
 * disciplines because they were learned from real failures on that path: main-thread-only player
 * access, every callback re-checks it fires for the item still tuned, transient IO errors are
 * re-prepared before being surfaced, and a permanent failure releases the player rather than leaving
 * it holding a decoder and audio focus.
 *
 * **Three deliberate differences from the live controller, each because this player has a timeline.**
 *
 * 1. **A retry resumes where it left off.** A live stream re-prepares at the live edge; here the
 *    position is captured before the re-prepare and sought back after, or a two-hour video restarts
 *    from zero on a network blip.
 * 2. **Pause is a state, not a stop.** Pausing keeps the player and the floor — the user is coming
 *    back — where a live stream is either on or torn down.
 * 3. **The skip engine.** While playing, the position is polled and [SponsorSegments.skipTo] decides
 *    whether it sits inside a community-flagged segment; if so the player seeks to the segment's end
 *    and says so. The DECISIONS all live in the CI-tested core — merged segments, exclusive ends, no
 *    backward seeks — this layer only performs them. The poll is cheap (a position read four times a
 *    second against a small sorted list) and only runs while something plays.
 *
 * It is the FOURTH audio claimant beside the radio, live TV and the assistant's voice, and it asks
 * [AudioFloor] before it plays — this repo has shipped two real defects from players that did not.
 */
object OnDemandController {

    enum class Status { IDLE, CONNECTING, PLAYING, PAUSED, ERROR }

    data class OnDemandState(
        val item: MediaItem? = null,
        val status: Status = Status.IDLE,
        val detail: String? = null,
        /** True when playing the audio-only rendition — the mode that survives leaving the screen. */
        val audioOnly: Boolean = false,
    )

    /** Where playback is, for the transport bar. Milliseconds; duration 0 while unknown. */
    data class Progress(val positionMs: Long = 0, val durationMs: Long = 0)

    private val _state = MutableStateFlow(OnDemandState())
    val state: StateFlow<OnDemandState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * The last community skip performed, e.g. "Skipped sponsor · 28s", or null. Cleared on
     * stop/new-play so a stale line cannot describe the previous video.
     */
    private val _skipNote = MutableStateFlow<String?>(null)
    val skipNote: StateFlow<String?> = _skipNote.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: SurfaceView? = null
    private var pollJob: Job? = null

    /**
     * The merged, policy-filtered segments for the CURRENT item. Set by [play], read by the poll.
     * Already through [SponsorSegments.usable], so overlaps are merged and unusable ones are gone.
     */
    private var segments: List<SponsorSegments.Segment> = emptyList()

    private var retries = 0
    private const val MAX_RETRIES = 2
    private const val POLL_MS = 250L

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ---- control ---------------------------------------------------------------------------------

    /**
     * Play [item], skipping [skip] as playback crosses them.
     *
     * [skip] must already be the *usable* set — merged and policy-filtered — which the view model
     * builds via [SponsorSegments.usable]. Passing raw segments here would re-litigate the policy in
     * a second place, which is how two surfaces drift.
     */
    fun play(
        context: Context,
        item: MediaItem,
        skip: List<SponsorSegments.Segment>,
        audioOnly: Boolean = false,
    ) {
        if (!item.isResolved) {
            _state.value = OnDemandState(item, Status.ERROR, "nothing playable in that item")
            return
        }
        val app = context.applicationContext
        // Ask for the speaker BEFORE building anything — this is what stops the radio or live TV.
        AudioFloor.claim(app, MediaFloor.Owner.ONDEMAND)
        segments = skip
        _skipNote.value = null
        _state.value = OnDemandState(item, Status.CONNECTING, audioOnly = audioOnly)
        _progress.value = Progress()
        // ⚠️ Only audio-only playback earns the keep-alive service. Video with no visible surface is
        // data spent on pixels nobody sees — the live controller's reasoning, kept.
        if (audioOnly) OnDemandService.start(app)
        runOnMain { startPlayer(app, item, resumeMs = 0) }
    }

    /** Pause without giving anything up — the player, surface and floor all stay. */
    fun pause() = runOnMain {
        val exo = player ?: return@runOnMain
        exo.playWhenReady = false
        val s = _state.value
        if (s.status == Status.PLAYING) _state.value = s.copy(status = Status.PAUSED)
    }

    fun resume() = runOnMain {
        val exo = player ?: return@runOnMain
        exo.playWhenReady = true
        val s = _state.value
        if (s.status == Status.PAUSED) _state.value = s.copy(status = Status.PLAYING)
    }

    /**
     * Seek relative to now. The bound is the player's own duration when it knows one; a seek past
     * the end is clamped by the player, which is the behaviour a +30s button wants near the end.
     */
    fun seekBy(deltaMs: Long) = runOnMain {
        val exo = player ?: return@runOnMain
        runCatching { exo.seekTo((exo.currentPosition + deltaMs).coerceAtLeast(0)) }
    }

    /**
     * Seek to an absolute position — the resume path's primitive. A saved position is a place in
     * the video, not a distance from wherever playback happens to stand when the seek lands, and a
     * relative seek there is off by exactly whatever moved first (a sponsor skip at 0:00, a user
     * scrub while the resume waiter was still arming).
     */
    fun seekTo(positionMs: Long) = runOnMain {
        val exo = player ?: return@runOnMain
        runCatching { exo.seekTo(positionMs.coerceAtLeast(0)) }
    }

    fun stop(context: Context) {
        pollJob?.cancel()
        segments = emptyList()
        _skipNote.value = null
        _progress.value = Progress()
        runOnMain { releasePlayerInternal() }
        _state.value = OnDemandState()
        AudioFloor.released(MediaFloor.Owner.ONDEMAND)
    }

    // ---- the surface ------------------------------------------------------------------------------

    /** Held rather than passed through — a retry rebuilds the player under the same view. */
    fun attach(view: SurfaceView) {
        surface = view
        runOnMain { runCatching { player?.setVideoSurfaceView(view) } }
    }

    /**
     * ⚠️ Identity-guarded on purpose: a composable disposed after its replacement attached would
     * otherwise clear the surface the new one just set.
     */
    fun detach(view: SurfaceView) {
        if (surface !== view) return
        surface = null
        runOnMain { runCatching { player?.clearVideoSurfaceView(view) } }
    }

    // ---- playback ---------------------------------------------------------------------------------

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun startPlayer(app: Context, item: MediaItem, resumeMs: Long) {
        // A newer item may have superseded this one while we posted to main — don't clobber it.
        if (_state.value.item?.id != item.id) return
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
            exo.setMediaItem(ExoMediaItem.fromUri(urlOf(item)))
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (_state.value.item?.id != item.id) return
                    when (playbackState) {
                        Player.STATE_READY -> {
                            // Per OUTAGE, not per tune — the live controller's hard-won lesson.
                            retries = 0
                            // ⚠️ copy(), not a fresh construction — a rebuilt state must not drop
                            // the audioOnly flag, or the keep-alive service would read a background
                            // audio session as a video one and let the OS kill it.
                            _state.value = _state.value.copy(
                                status = if (exo.playWhenReady) Status.PLAYING else Status.PAUSED,
                                detail = null,
                            )
                            watchPosition(item)
                        }
                        // Unlike a live stream, an on-demand item ending is the normal happy end.
                        Player.STATE_ENDED -> {
                            pollJob?.cancel()
                            _state.value = OnDemandState(item, Status.IDLE, "finished")
                            runOnMain { releasePlayerInternal() }
                            AudioFloor.released(MediaFloor.Owner.ONDEMAND)
                        }
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (_state.value.item?.id != item.id) return
                    if (isTransient(error)) retryOrFail(app, item, error.errorCodeName)
                    else failPermanently(item, error.errorCodeName)
                }
            })
            surface?.let { exo.setVideoSurfaceView(it) }
            if (resumeMs > 0) exo.seekTo(resumeMs)
            exo.playWhenReady = true
            exo.prepare()
            player = exo
        }.onFailure { failPermanently(item, it.message) }
    }

    /**
     * Only the IO band. `BEHIND_LIVE_WINDOW` deliberately does NOT appear here — it is a live-stream
     * error and this player never plays live streams; naming it would imply a case that cannot occur.
     */
    private fun isTransient(error: PlaybackException): Boolean =
        error.errorCode in
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    private fun retryOrFail(app: Context, item: MediaItem, reason: String) {
        if (retries >= MAX_RETRIES) {
            failPermanently(item, reason)
            return
        }
        retries++
        // ⚠️ Captured NOW, before the delay — the position an on-demand retry must return to. The
        // player object may be the same across the re-prepare, but prepare() resets the position, and
        // "your movie restarted because the Wi-Fi blinked" is the failure this line prevents.
        val resumeMs = runCatching { player?.currentPosition }.getOrNull() ?: _progress.value.positionMs
        _state.value = _state.value.copy(status = Status.CONNECTING, detail = "reconnecting…")
        scope.launch {
            delay(1_500)
            runOnMain {
                if (_state.value.item?.id != item.id) return@runOnMain
                val exo = player
                if (exo == null) {
                    failPermanently(item, reason)
                    return@runOnMain
                }
                runCatching {
                    exo.setMediaItem(ExoMediaItem.fromUri(urlOf(item)))
                    exo.prepare()
                    exo.seekTo(resumeMs)
                    exo.playWhenReady = true
                }.onFailure { failPermanently(item, reason) }
            }
        }
    }

    private fun failPermanently(item: MediaItem, reason: String?) {
        pollJob?.cancel()
        runOnMain { releasePlayerInternal() }
        _state.value = OnDemandState(item, Status.ERROR, reason)
        AudioFloor.released(MediaFloor.Owner.ONDEMAND)
    }

    // ---- the skip engine --------------------------------------------------------------------------

    /**
     * Poll the position: publish it for the transport bar, and perform any due community skip.
     *
     * ⚠️ The seek target gets a small nudge past the segment's end. [SponsorSegments.segmentAt] is
     * exclusive of the end, so landing exactly on `endS` is already outside the segment — the nudge
     * is not what prevents a loop, it guards against the PLAYER rounding the seek down to a keyframe
     * a fraction earlier, which would land back inside and skip again. One skip per crossing.
     */
    private fun watchPosition(item: MediaItem) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                delay(POLL_MS)
                runOnMain {
                    if (_state.value.item?.id != item.id) return@runOnMain
                    val exo = player ?: return@runOnMain
                    val posMs = runCatching { exo.currentPosition }.getOrNull() ?: return@runOnMain
                    val durMs = runCatching { exo.duration }.getOrNull()
                        ?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                    _progress.value = Progress(posMs, durMs)
                    if (_state.value.status != Status.PLAYING) return@runOnMain
                    val posS = posMs / 1000.0
                    val target = SponsorSegments.skipTo(posS, segments) ?: return@runOnMain
                    val seg = SponsorSegments.segmentAt(posS, segments)
                    runCatching { exo.seekTo(((target + 0.05) * 1000).toLong()) }
                    val length = seg?.let { (it.endS - it.startS).toInt() } ?: 0
                    val label = seg?.let { SponsorSegments.label(it.category) } ?: "segment"
                    _skipNote.value = "Skipped $label · ${length}s"
                }
            }
        }
    }

    /** The address for the current mode: the audio rendition when audio-only and one exists. */
    private fun urlOf(item: MediaItem): String =
        if (_state.value.audioOnly && item.audioUrl.isNotBlank()) item.audioUrl
        else item.streamUrl.ifBlank { item.audioUrl }

    /** MUST run on the main thread — ExoPlayer is single-thread-affine. */
    private fun releasePlayerInternal() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}
