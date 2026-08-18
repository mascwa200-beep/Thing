package dev.mascwa.pulse.feature.tacnet

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.mascwa.pulse.core.telemetry.MediaFloor
import dev.mascwa.pulse.data.radio.IcyMetadata
import dev.mascwa.pulse.data.radio.RadioStation
import dev.mascwa.pulse.data.radio.StreamResolver
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
 * Process-wide radio playback: one [ExoPlayer] that keeps playing after the LCARS console — or the whole
 * Activity — goes away, kept alive by [RadioService] (a `mediaPlayback` foreground service). The
 * ViewModel and the service notification both drive and observe this single object, so "what's on air"
 * has one source of truth. Defensive throughout — a bad URL / network drop lands in [Status.ERROR],
 * never a crash.
 *
 * ExoPlayer (not bare MediaPlayer) so the picky commercial streams actually open: it handles ICY /
 * SHOUTcast responses, HLS, and the cross-protocol (http↔https) redirects that StreamTheWorld / Triton
 * mounts do. A real browser User-Agent is set since those CDNs 403 the default one. The player is touched
 * ONLY on the main thread.
 *
 * Now-playing: the audio connection is kept pristine (no `Icy-MetaData` header — on the Triton AAC mounts
 * that header makes the server interleave metadata ExoPlayer doesn't strip, breaking the container parse).
 * Instead a SEPARATE, brief, delayed ICY poll reads the title — and it is SKIPPED for the connection-limited
 * commercial CDNs (StreamTheWorld / Amperwave), which drop the audio when a second connection opens. So
 * SomaFM and most Icecast/SHOUTcast stations get live track text; the strict CDNs keep uninterrupted audio.
 */
object RadioController {

    enum class Status { IDLE, TUNING, ON_AIR, ERROR }

    /** Tuned station tracked by identity (not list index) so it stays lit as the local list loads in.
     *  [detail] carries the player error name on [Status.ERROR] so a failure is diagnosable. */
    data class RadioState(val tuned: RadioStation? = null, val status: Status = Status.IDLE, val detail: String? = null)

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    /** Active sleep-timer duration in minutes, or null when off. Playback auto-stops when it elapses. */
    private val _sleepMinutes = MutableStateFlow<Int?>(null)
    val sleepMinutes: StateFlow<Int?> = _sleepMinutes.asStateFlow()

    /** Live "Artist - Song" for the tuned station (in-band ICY stream metadata), or null when unavailable. */
    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepJob: Job? = null
    private var metaJob: Job? = null
    private var player: ExoPlayer? = null

    // Auto-recover from a brief live-stream drop (a momentary STATE_ENDED / IO blip) by re-preparing a
    // couple of times before surfacing an error — many live mounts hiccup but recover. Reset per tune.
    private var retries = 0
    private const val MAX_RETRIES = 2

    // Connection-limited commercial CDNs: a second listener connection makes them drop the audio, so we
    // never run the side metadata poll for these (they keep playing; just no track text).
    private val SINGLE_CONNECTION_HOSTS = listOf("streamtheworld", "amperwave")

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Arm (or clear, with null/0) a sleep timer that stops playback after [minutes]. */
    fun setSleep(context: Context, minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _sleepMinutes.value = null
            return
        }
        _sleepMinutes.value = minutes
        val app = context.applicationContext
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            stop(app)
        }
    }

    /** Tap a station: tune it, or stop if it's the one already tuning/on air. */
    fun toggle(context: Context, station: RadioStation) {
        val s = _state.value
        if (s.tuned?.streamUrl == station.streamUrl && (s.status == Status.ON_AIR || s.status == Status.TUNING)) {
            stop(context)
        } else {
            play(context, station)
        }
    }

    fun play(context: Context, station: RadioStation) {
        val app = context.applicationContext
        // Ask for the speaker first. Two players each handling their own audio focus would simply
        // fight: the newer wins and the older falls silent while still claiming to be playing.
        AudioFloor.claim(app, MediaFloor.Owner.RADIO)
        _state.value = RadioState(station, Status.TUNING)
        _nowPlaying.value = null
        // Promote to a foreground service so the stream survives leaving the app (defensive: a denied
        // FGS start just means foreground-only playback, never a crash).
        runCatching { ContextCompat.startForegroundService(app, Intent(app, RadioService::class.java)) }
        // Resolve playlist URLs (.pls/.m3u/.asx → the real stream) off the main thread BEFORE building the
        // player, so a directory's playlist entry doesn't get "played" as a text file (the dropped-stream
        // bug). Direct streams / HLS resolve to themselves instantly.
        scope.launch {
            val audioUrl = StreamResolver.resolve(station.streamUrl)
            if (_state.value.tuned?.streamUrl != station.streamUrl) return@launch // re-tuned while resolving
            startMetaPolling(station, audioUrl)
            runOnMain { startPlayer(app, station, audioUrl) }
        }
    }

    /** Build (or rebuild) the ExoPlayer for [station] on the tuned [audioUrl]. Main thread only. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun startPlayer(app: Context, station: RadioStation, audioUrl: String) {
        // A newer tune may have superseded this one while we resolved / posted to main — don't clobber it.
        if (_state.value.tuned?.streamUrl != station.streamUrl) return
        retries = 0
        releasePlayerInternal()
        runCatching {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(MediaHttp.BROWSER_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(MediaHttp.TIMEOUT_MS)
                .setReadTimeoutMs(MediaHttp.TIMEOUT_MS)
            // NOTE: we deliberately do NOT request ICY metadata (`Icy-MetaData: 1`). On these
            // StreamTheWorld/Triton AAC mounts that header makes the server interleave metadata that
            // ExoPlayer doesn't strip here, breaking the container parse
            // (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED). A single audio-only connection parses and
            // plays continuously; live now-playing text comes from the separate ICY poll.
            val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(app, httpFactory))
            val exo = ExoPlayer.Builder(app)
                .setMediaSourceFactory(sourceFactory)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                .build()
            exo.setMediaItem(MediaItem.fromUri(audioUrl))
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (_state.value.tuned?.streamUrl != station.streamUrl) return
                    when (playbackState) {
                        Player.STATE_READY -> _state.value = RadioState(station, Status.ON_AIR)
                        // A live stream shouldn't end; a brief drop often recovers on a re-prepare, so
                        // retry a couple of times before surfacing it.
                        Player.STATE_ENDED -> retryOrFail(app, station, audioUrl, "stream ended")
                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (_state.value.tuned?.streamUrl != station.streamUrl) return
                    // Transient network errors recover on a re-prepare; permanent ones (bad status, parse,
                    // decoder) won't, so fail fast on those with a readable code.
                    if (isTransient(error)) retryOrFail(app, station, audioUrl, error.errorCodeName)
                    else failPermanently(app, station, error.errorCodeName)
                }
            })
            exo.playWhenReady = true
            exo.prepare()
            player = exo
        }.onFailure { failPermanently(app, station, it.message) }
    }

    /** Network/IO errors are worth a retry; container-parse / decoder / bad-HTTP errors are not. */
    private fun isTransient(error: PlaybackException): Boolean =
        error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    /** Re-prepare the stream after a short delay, up to [MAX_RETRIES]; otherwise show [reason]. */
    private fun retryOrFail(app: Context, station: RadioStation, audioUrl: String, reason: String) {
        if (retries >= MAX_RETRIES) {
            failPermanently(app, station, reason)
            return
        }
        retries++
        _state.value = RadioState(station, Status.TUNING, "reconnecting…")
        scope.launch {
            delay(1_500)
            runOnMain {
                if (_state.value.tuned?.streamUrl != station.streamUrl) return@runOnMain
                val exo = player
                if (exo == null) {
                    failPermanently(app, station, reason)
                    return@runOnMain
                }
                runCatching {
                    exo.setMediaItem(MediaItem.fromUri(audioUrl))
                    exo.prepare()
                    exo.playWhenReady = true
                }.onFailure { failPermanently(app, station, reason) }
            }
        }
    }

    fun stop(context: Context) {
        sleepJob?.cancel()
        _sleepMinutes.value = null
        metaJob?.cancel()
        _nowPlaying.value = null
        runOnMain { releasePlayerInternal() }
        _state.value = RadioState(null, Status.IDLE)
        runCatching {
            val app = context.applicationContext
            app.stopService(Intent(app, RadioService::class.java))
        }
        // Report, not request. Ignored when something else already took the speaker — which is
        // exactly what happens when this stop was ordered BY that something else.
        AudioFloor.released(MediaFloor.Owner.RADIO)
    }

    /** Poll the tuned station's ICY now-playing title on a separate brief connection — but only for
     *  stations that tolerate a second connection (skipped for the single-connection commercial CDNs so
     *  their audio is never disturbed). A short initial delay lets the audio connection settle first. */
    private fun startMetaPolling(station: RadioStation, audioUrl: String) {
        metaJob?.cancel()
        if (SINGLE_CONNECTION_HOSTS.any { audioUrl.contains(it, ignoreCase = true) }) return
        metaJob = scope.launch {
            delay(6_000)
            while (_state.value.tuned?.streamUrl == station.streamUrl) {
                val title = runCatching { IcyMetadata.nowPlaying(audioUrl) }.getOrNull()
                if (_state.value.tuned?.streamUrl == station.streamUrl) _nowPlaying.value = title
                delay(30_000)
            }
        }
    }

    /** A permanently-failed tune: keep the error visible in the UI, but release the player (freeing its
     *  audio focus + decoder) and stop the foreground service. Nothing retries a permanent failure, so
     *  holding either is pure leak — and it left an orphaned `mediaPlayback` service + notification up. */
    private fun failPermanently(app: Context, station: RadioStation, reason: String?) {
        metaJob?.cancel()
        _nowPlaying.value = null
        runOnMain { releasePlayerInternal() }
        _state.value = RadioState(station, Status.ERROR, reason)
        runCatching { app.stopService(Intent(app, RadioService::class.java)) }
        AudioFloor.released(MediaFloor.Owner.RADIO)
    }

    /** Release the player — MUST be called on the main thread (ExoPlayer is single-thread-affine). */
    private fun releasePlayerInternal() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}
