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
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.MediaFloor
import dev.mascwa.pulse.core.telemetry.MediaItem
import dev.mascwa.pulse.core.telemetry.MediaResolution
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

    /**
     * Whether this tune has already been given a fresh address after a refusal.
     *
     * ⚠️ **Per TUNE, not per outage — the opposite of [retries], deliberately.** A retry re-prepares
     * an address the app already has, which costs nothing; a re-resolve spawns the extractor and a
     * network round trip. Resetting this on READY would allow a source that answers the first range
     * request and refuses the rest to re-extract forever, and each turn of that loop is expensive
     * enough to be noticed as the app getting stuck. One per tune fails honestly instead.
     */
    private var reResolved = false
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
        retries = 0
        reResolved = false
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
            val exo = ExoPlayer.Builder(app)
                // ⚠️ The headers for the track this mode will actually fetch, not the video's in
                // every case. Both prepare paths hand the player an explicitly-built source, so this
                // factory is only reached by something media3 resolves for itself — a sideloaded
                // subtitle, a child load — but when that happens it must not go out with an identity
                // minted for a different track, or for no track at all. `emptyMap()` here was how the
                // audio-only mode came to be requesting with the video's User-Agent.
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(DefaultDataSource.Factory(app, httpFactory(app, headersFor(item)))),
                )
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .build()
            exo.setMediaSource(sourceFor(app, item))
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
                    when {
                        isTransient(error) -> retryOrFail(app, item, error.errorCodeName)
                        // ⚠️ A REFUSAL IS NOT A BLIP, and the two need opposite recoveries. Re-preparing
                        // the same address is right for a network stutter and useless here: a signed
                        // media URL that comes back 403 or 404 will come back 403 or 404 forever. The
                        // only thing that can help is a NEW address, so this branch re-resolves.
                        isRefusal(error) -> reResolveOrFail(app, item, describe(error))
                        else -> failPermanently(item, describe(error))
                    }
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

    /**
     * The server answered, and said no — and what to print for it.
     *
     * ⚠️ Both live in [MediaHttp] now rather than here, because the radio needs exactly the same two
     * judgements and had neither: a refused reconnect there went straight to a dead "No signal".
     * A shared definition also closed a real gap in this copy — `INVALID_HTTP_CONTENT_TYPE` (2003)
     * sat between the transient band and the refusal test and fell through to permanent failure,
     * which is what a 403 returning an HTML error page produces.
     */
    private fun isRefusal(error: PlaybackException) = MediaHttp.isRefusal(error)

    private fun describe(error: PlaybackException) = MediaHttp.describe(error)

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
                    // ⚠️ setMediaSource, not setMediaItem. A bare URI throws away the headers the
                    // address was minted for AND collapses an adaptive pair down to one of its two
                    // halves — so a network blip on a merged stream would come back silent, which is
                    // much harder to explain than the blip itself. [sourceFor] is the ONE place that
                    // decides what plays; both prepare paths go through it.
                    exo.setMediaSource(sourceFor(app, item))
                    exo.prepare()
                    exo.seekTo(resumeMs)
                    exo.playWhenReady = true
                }.onFailure { failPermanently(item, reason) }
            }
        }
    }

    /**
     * Give up, and say everything that is known about why.
     *
     * ⚠️ The extractor's own notes are appended here, and this is the case they exist for: the
     * resolve SUCCEEDED — title, duration and thumbnail all arrived — and the address it produced
     * was then refused. "403" alone leaves nobody any wiser; "403, and by the way there was no
     * JavaScript runtime so some formats were missing" is a diagnosis. The notes are carried on the
     * item precisely so they survive from the resolve to this moment, seconds or hours later.
     */
    private fun failPermanently(item: MediaItem, reason: String?) {
        pollJob?.cancel()
        runOnMain { releasePlayerInternal() }
        val detail = listOfNotNull(reason?.takeIf { it.isNotBlank() })
            .plus(item.notes)
            .joinToString("\n")
            .ifBlank { null }
        _state.value = OnDemandState(item, Status.ERROR, detail)
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

    /**
     * An HTTP source that identifies itself the way the extractor did.
     *
     * ⚠️ **The User-Agent is split out and set through `setUserAgent`; everything else goes to
     * `setDefaultRequestProperties`.** media3 has both, and rather than depend on which wins when the
     * same header is given to each, the header is only ever given to one of them. That is the whole
     * reason this is a function instead of two lines at the call site.
     *
     * ⚠️ **A MISSING header set is not an invitation to invent one.** This used to substitute a
     * desktop-Chrome User-Agent whenever the extractor named no agent, which is worse than sending
     * nothing: a signed address minted for, say, the `android_vr` client and then fetched by
     * something claiming to be Chrome on Linux is exactly the mismatch that earns a 403 — and from
     * the outside it was indistinguishable from the headers being carried correctly. The fallback is
     * now used only when there are NO headers at all, where some agent must be chosen and the shared
     * browser string is as good as any. When the extractor supplied headers but no agent, its
     * headers go out as given and media3 sends its own default agent, which at least does not
     * impersonate a client the address was not issued to.
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun httpFactory(app: Context, headers: Map<String, String>): DefaultHttpDataSource.Factory {
        val agent = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val rest = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val chosen = agent?.takeIf { it.isNotBlank() }
            ?: MediaHttp.BROWSER_UA.takeIf { headers.isEmpty() }
        return DefaultHttpDataSource.Factory()
            .setUserAgent(chosen)
            .setDefaultRequestProperties(rest)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(MediaHttp.TIMEOUT_MS)
            .setReadTimeoutMs(MediaHttp.TIMEOUT_MS)
    }

    /**
     * The media source for [item] in the current mode.
     *
     * Three shapes, and the discriminator is [MediaItem.isAdaptive] rather than "are both addresses
     * set":
     *
     * * **audio-only mode**, or an item with no video at all — one source on the audio track;
     * * **adaptive** — the video half and the audio half merged, which is what lets this play well
     *   above the muxed ceiling the extractor used to be limited to;
     * * **muxed** — one source, exactly as before.
     *
     * ⚠️ A muxed stream can sit beside a separate audio-only rendition, which is what LISTEN plays.
     * Merging *those* would play the audio twice, which is why the flag is set by the extractor —
     * the only place that knows whether it made an adaptive selection — and not inferred here.
     *
     * ⚠️ Each half gets its OWN [httpFactory]. yt-dlp emits headers per format and the video and
     * audio sets genuinely differ; one shared factory is the obvious shortcut and it fails on
     * whichever track it does not happen to match.
     */
    /**
     * The header set for the track the CURRENT mode will actually fetch.
     *
     * Small, but it exists so that "which track is this mode playing" is answered in one place
     * rather than re-derived at each call site — which is how the player-level factory came to be
     * built with the video's headers while playing audio.
     */
    private fun headersFor(item: MediaItem): Map<String, String> =
        if (_state.value.audioOnly && item.audioUrl.isNotBlank()) item.audioHeaders
        else if (item.streamUrl.isNotBlank()) item.streamHeaders
        else item.audioHeaders

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun sourceFor(app: Context, item: MediaItem): MediaSource {
        // ⚠️ An address and its headers are ONE thing and are only ever named together. Passing them
        // as two arguments invites a call site that takes one track's address and the other track's
        // headers, which is not a compile error and produces a 403 that looks like a dead video.
        // ⚠️ Progressive is right for a plain file and WRONG for a manifest. yt-dlp hands back an
        // `.m3u8` for plenty of sources — anything live, and some ordinary videos — and feeding that
        // to the progressive loader fails as a container-parse error rather than playing.
        // `media3-exoplayer-hls` is already a dependency; `DefaultMediaSourceFactory` picks the right
        // loader from the address, which is exactly the judgement wanted here.
        fun source(track: Pair<String, Map<String, String>>): MediaSource =
            DefaultMediaSourceFactory(DefaultDataSource.Factory(app, httpFactory(app, track.second)))
                .createMediaSource(ExoMediaItem.fromUri(track.first))

        val video = item.streamUrl to item.streamHeaders
        val sound = item.audioUrl to item.audioHeaders

        val audioOnly = _state.value.audioOnly && item.audioUrl.isNotBlank()
        if (audioOnly || item.streamUrl.isBlank()) {
            return source(if (item.audioUrl.isNotBlank()) sound else video)
        }
        if (item.isAdaptive && item.audioUrl.isNotBlank()) {
            return MergingMediaSource(
                // ⚠️ `adjustPeriodTimeOffsets` and `clipDurations`, both true. Two renditions of the
                // same video are not bit-identical in length — they differ by a frame or two — and
                // without clipping, the merge asserts on the mismatch rather than playing.
                /* adjustPeriodTimeOffsets = */ true,
                /* clipDurations = */ true,
                source(video),
                source(sound),
            )
        }
        return source(video)
    }

    /**
     * The source refused the address. Get a new one, once.
     *
     * ⚠️ **Once, and then it fails for good.** A video that has genuinely been taken down refuses
     * every address anybody can mint for it, so a loop here would be an app that never stops trying
     * and never says why. One fresh resolve covers the case that is actually recoverable — an
     * address invalidated earlier than its stated expiry, which the freshness check cannot see —
     * and anything past that is a real refusal worth reporting.
     *
     * The cache entry is evicted first, or [MediaExtractor] would hand back the very address that
     * just failed: it trusts the stated expiry, and the whole point of this path is that the stated
     * expiry was wrong.
     */
    private fun reResolveOrFail(app: Context, item: MediaItem, reason: String) {
        if (reResolved) {
            failPermanently(item, reason)
            return
        }
        reResolved = true
        val page = item.pageUrl
        if (page.isBlank()) {
            // Nothing to re-resolve from — a local file, or an item that arrived already resolved.
            failPermanently(item, reason)
            return
        }
        // Captured before anything suspends, for the same reason the transient path captures it.
        val resumeMs = runCatching { player?.currentPosition }.getOrNull() ?: _progress.value.positionMs
        _state.value = _state.value.copy(status = Status.CONNECTING, detail = "getting a fresh address…")
        scope.launch {
            // Reached through the application rather than held as a field: this is a process-wide
            // object, so a stored container would be one more thing to initialise in the right order
            // and one more reference outliving whatever set it. `app` is already the app context.
            val extractor = (app.applicationContext as? PulseApplication)?.container?.mediaExtractor
            if (extractor == null) {
                runOnMain { failPermanently(item, reason) }
                return@launch
            }
            extractor.evict(page)
            val fresh = (extractor.resolve(page) as? MediaResolution.Ready)?.item
            runOnMain {
                // The user may have moved on while we were resolving.
                if (_state.value.item?.id != item.id) return@runOnMain
                if (fresh == null || !fresh.isResolved) {
                    failPermanently(item, reason)
                    return@runOnMain
                }
                // ⚠️ copy() from the CURRENT state, so audioOnly survives — the same rule the READY
                // branch follows, and for the same reason.
                _state.value = _state.value.copy(item = fresh, detail = null)
                startPlayer(app, fresh, resumeMs)
            }
        }
    }

    /** MUST run on the main thread — ExoPlayer is single-thread-affine. */
    private fun releasePlayerInternal() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}
