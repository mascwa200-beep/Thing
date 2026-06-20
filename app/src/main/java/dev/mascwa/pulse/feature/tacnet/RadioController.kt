package dev.mascwa.pulse.feature.tacnet

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import dev.mascwa.pulse.data.radio.RadioStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide radio playback: one [ExoPlayer] that keeps playing after the PIP-BOY — or the whole
 * Activity — goes away, kept alive by [RadioService] (a `mediaPlayback` foreground service). The
 * ViewModel and the service notification both drive and observe this single object, so "what's on air"
 * has one source of truth. Defensive throughout — a bad URL / network drop lands in [Status.ERROR],
 * never a crash.
 *
 * ExoPlayer (not bare MediaPlayer) so the picky commercial streams actually open: it handles ICY /
 * SHOUTcast responses, HLS, and the cross-protocol (http↔https) redirects that StreamTheWorld / Triton
 * mounts do. A real browser User-Agent is set since those CDNs 403 the default one. Crucially we use a
 * SINGLE connection: now-playing comes from ExoPlayer's IN-BAND ICY metadata (`Icy-MetaData: 1` +
 * onMetadata), not a second HTTP fetch — Triton drops the audio connection when a second one opens, which
 * was making streams cut out after a couple of seconds. The player is touched ONLY on the main thread.
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

    // A real browser User-Agent for the audio request — picky commercial CDNs (StreamTheWorld/Triton,
    // iHeart, etc.) refuse the default player UA, so the stream never opens.
    private const val STREAM_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val scope = CoroutineScope(SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepJob: Job? = null
    private var player: ExoPlayer? = null

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

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun play(context: Context, station: RadioStation) {
        val app = context.applicationContext
        _state.value = RadioState(station, Status.TUNING)
        _nowPlaying.value = null
        // Promote to a foreground service so the stream survives leaving the app (defensive: a denied
        // FGS start just means foreground-only playback, never a crash).
        runCatching { ContextCompat.startForegroundService(app, Intent(app, RadioService::class.java)) }
        runOnMain {
            releasePlayerInternal()
            runCatching {
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(STREAM_UA)
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20_000)
                    .setReadTimeoutMs(20_000)
                    // Ask for in-band ICY metadata so we never open a second connection for now-playing —
                    // Triton drops the audio stream when a second connection appears. ExoPlayer strips the
                    // metadata bytes from the audio and surfaces them via onMetadata.
                    .setDefaultRequestProperties(mapOf("Icy-MetaData" to "1"))
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
                exo.setMediaItem(MediaItem.fromUri(station.streamUrl))
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (_state.value.tuned?.streamUrl != station.streamUrl) return
                        when (playbackState) {
                            Player.STATE_READY -> _state.value = RadioState(station, Status.ON_AIR)
                            // A live stream shouldn't end; if it does the source dried up — show it instead
                            // of going silent with no indication.
                            Player.STATE_ENDED -> _state.value = RadioState(station, Status.ERROR, "stream dropped")
                            else -> {}
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (_state.value.tuned?.streamUrl == station.streamUrl) {
                            _state.value = RadioState(station, Status.ERROR, error.errorCodeName)
                        }
                    }

                    override fun onMetadata(metadata: Metadata) {
                        val title = (0 until metadata.length()).asSequence()
                            .map { metadata.get(it) }
                            .filterIsInstance<IcyInfo>()
                            .firstNotNullOfOrNull { it.title?.trim()?.ifBlank { null } }
                        if (title != null && _state.value.tuned?.streamUrl == station.streamUrl) {
                            _nowPlaying.value = title
                        }
                    }
                })
                exo.playWhenReady = true
                exo.prepare()
                player = exo
            }.onFailure { _state.value = RadioState(station, Status.ERROR, it.message) }
        }
    }

    fun stop(context: Context) {
        sleepJob?.cancel()
        _sleepMinutes.value = null
        _nowPlaying.value = null
        runOnMain { releasePlayerInternal() }
        _state.value = RadioState(null, Status.IDLE)
        runCatching {
            val app = context.applicationContext
            app.stopService(Intent(app, RadioService::class.java))
        }
    }

    /** Release the player — MUST be called on the main thread (ExoPlayer is single-thread-affine). */
    private fun releasePlayerInternal() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }
}
