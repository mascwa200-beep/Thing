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
import dev.mascwa.pulse.core.telemetry.MediaFloor
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
    private var player: ExoPlayer? = null

    // Auto-recover from a brief live-stream drop (a momentary STATE_ENDED / IO blip) by re-preparing a
    // couple of times before surfacing an error — many live mounts hiccup but recover.
    // ⚠️ Reset on every STATE_READY, i.e. per OUTAGE. It used to reset only per tune, which quietly
    // turned "two retries per hiccup" into "two hiccups per listening session": a station left on all
    // evening recovered from the first two drops and then treated the third as permanent.
    private var retries = 0

    /**
     * ⚠️ Five, not two, and with a widening delay. Two attempts 1.5 s apart is a recovery window of
     * about three seconds, which is not a recovery window for live radio at all — a mount that
     * hiccups, a lift, a handover between Wi-Fi and cellular all take longer than that, and the old
     * budget turned every one of them into a dead "No signal".
     */
    private const val MAX_RETRIES = 5

    /** Backoff for attempt n (1-based): 1s, 2s, 4s, 8s, 8s. Capped so it never feels abandoned. */
    private fun retryDelayMs(attempt: Int): Long =
        (1_000L shl (attempt - 1).coerceIn(0, 3))

    /** True once a fresh address has been fetched for this tune; see [refreshAndRetry]. */
    private var reResolved = false


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
            runOnMain { startPlayer(app, station, audioUrl) }
        }
    }

    /** Build (or rebuild) the ExoPlayer for [station] on the tuned [audioUrl]. Main thread only. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun startPlayer(app: Context, station: RadioStation, audioUrl: String) {
        // A newer tune may have superseded this one while we resolved / posted to main — don't clobber it.
        if (_state.value.tuned?.streamUrl != station.streamUrl) return
        retries = 0
        // Per TUNE, unlike `retries`: one fresh address per station you choose, so a genuinely
        // dead mount fails instead of re-resolving forever.
        reResolved = false
        releasePlayerInternal()
        runCatching {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(MediaHttp.BROWSER_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(MediaHttp.TIMEOUT_MS)
                .setReadTimeoutMs(MediaHttp.TIMEOUT_MS)
            // ⚠️ The ICY request header is NOT set here, and the reason is not the one this comment
            // used to give. ExoPlayer sets it ITSELF: `ProgressiveMediaPeriod.buildDataSpec` calls
            // `setHttpRequestHeaders(ICY_METADATA_HEADERS)` unconditionally — verified in the shipped
            // 1.5.1 bytecode, there is no branch around it — and wraps the stream in `IcyDataSource`
            // to strip the interleaved blocks when the response carries `icy-metaint`.
            //
            // So metadata was always arriving on this connection and nothing was listening; see
            // `onMetadata` below. Setting the header a SECOND time by hand is what breaks the
            // container parse, because the player then does not own the metaint it is stripping.
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
                // ⚠️ Holds a partial wake lock AND a Wi-Fi lock for as long as something is playing.
                // The foreground service keeps the process alive; it does not keep the radio awake,
                // and a doze-stalled socket was landing in the retry window as a dropped stream.
                // Inert without the WAKE_LOCK permission, which the manifest now declares.
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
            exo.setMediaItem(MediaItem.fromUri(audioUrl))
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (_state.value.tuned?.streamUrl != station.streamUrl) return
                    when (playbackState) {
                        Player.STATE_READY -> {
                            // ⚠️ Spend the retry budget per OUTAGE, not per tune — see the field's
                            // own note. On air again means the outage that spent it has ended.
                            retries = 0
                            _state.value = RadioState(station, Status.ON_AIR)
                        }
                        // A live stream shouldn't end; a brief drop often recovers on a re-prepare, so
                        // retry a couple of times before surfacing it.
                        // ⚠️ Ending is not "the song finished" on a live mount — it means the body
                        // ran out, which for a text file handed over as audio happens immediately.
                        // Re-preparing the same address would just do it again, so the first end
                        // gets the sniff and only then falls back to ordinary retries.
                        Player.STATE_ENDED ->
                            if (!reResolved) refreshAndRetry(app, station, "stream ended", sniff = true)
                            else retryOrFail(app, station, audioUrl, "stream ended")
                        else -> {}
                    }
                }

                /**
                 * Now-playing, off the connection that is already open.
                 *
                 * ⚠️ This replaces a second HTTP connection to the same mount, opened every 30
                 * seconds for the whole listening session. That is what stops connection-limited
                 * stations staying tuned: to the server it is a duplicate listener from one address,
                 * and the usual response is to drop the older socket — which the app then saw as a
                 * dropped stream. The title was always available here for free.
                 */
                override fun onMetadata(metadata: Metadata) {
                    if (_state.value.tuned?.streamUrl != station.streamUrl) return
                    for (i in 0 until metadata.length()) {
                        val title = (metadata.get(i) as? IcyInfo)?.title?.trim()
                        // Blank is a real value — stations send an empty StreamTitle between tracks —
                        // so it clears the line rather than leaving the previous song showing.
                        if (title != null) {
                            _nowPlaying.value = title.ifBlank { null }
                            return
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (_state.value.tuned?.streamUrl != station.streamUrl) return
                    when {
                        isTransient(error) -> retryOrFail(app, station, audioUrl, error.errorCodeName)
                        // ⚠️ The server answered, and said no. Re-preparing the same address is right
                        // for a stutter and useless here — but the address itself may simply be stale:
                        // the directory's `url_resolved` comes from a check whose median age is 214
                        // days, and a dead edge host is a completely ordinary case. So fetch a fresh
                        // one, ONCE, before giving up.
                        isRefusal(error) -> refreshAndRetry(app, station, describe(error))
                        // ⚠️ "I cannot parse this container" is the signature of a PLAYLIST being
                        // played as audio — a .pls served from an extensionless path, which the
                        // extension-based detection cannot see. Re-resolve with the sniff on; if it
                        // really was audio, nothing parses and this fails exactly as before.
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                            refreshAndRetry(app, station, describe(error), sniff = true)
                        else -> failPermanently(app, station, describe(error))
                    }
                }
            })
            exo.playWhenReady = true
            exo.prepare()
            player = exo
        }.onFailure { failPermanently(app, station, it.message) }
    }

    /**
     * Network/IO errors are worth a retry; container-parse / decoder / bad-HTTP errors are not.
     *
     * ⚠️ **`BEHIND_LIVE_WINDOW` is named explicitly** for the same reason as the video controller's
     * copy: it is the one recoverable live error outside the IO band (code 1002, below 2000), it is
     * what a sliding HLS window produces after a stall, and it recovers completely on a re-prepare —
     * which already resets the position, so no seek is needed. This reaches the radio because
     * `StreamResolver` passes `.m3u8` straight through, so an HLS mount from the community catalogue
     * plays on exactly this path.
     */
    private fun isTransient(error: PlaybackException): Boolean =
        error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
            error.errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

    private fun isRefusal(error: PlaybackException) = MediaHttp.isRefusal(error)

    private fun describe(error: PlaybackException) = MediaHttp.describe(error)

    /** Re-prepare the stream after a short delay, up to [MAX_RETRIES]; otherwise show [reason]. */
    private fun retryOrFail(app: Context, station: RadioStation, audioUrl: String, reason: String) {
        if (retries >= MAX_RETRIES) {
            failPermanently(app, station, reason)
            return
        }
        retries++
        _state.value = RadioState(station, Status.TUNING, "reconnecting…")
        scope.launch {
            delay(retryDelayMs(retries))
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

    /**
     * The address was refused. Ask the directory for a fresh one and try that — once.
     *
     * ⚠️ Once, and then it fails for good. A station that has genuinely gone off the air refuses
     * every address anybody can resolve for it, so looping here would be a radio that never plays
     * and never says why. The case this covers is the common one: `RadioBrowserRepository` hands out
     * `url_resolved`, a post-redirect address cached by a directory whose median station check is
     * over two hundred days old, so a dead edge host is ordinary rather than exotic. Re-resolving
     * goes back through [StreamResolver], which follows the station's own playlist afresh.
     */
    private fun refreshAndRetry(
        app: Context,
        station: RadioStation,
        reason: String,
        sniff: Boolean = false,
    ) {
        if (reResolved) {
            failPermanently(app, station, reason)
            return
        }
        reResolved = true
        _state.value = RadioState(station, Status.TUNING, "finding another route…")
        scope.launch {
            val fresh = runCatching { StreamResolver.resolve(station.streamUrl, sniff) }.getOrNull()
            if (_state.value.tuned?.streamUrl != station.streamUrl) return@launch // re-tuned meanwhile
            if (fresh.isNullOrBlank()) {
                runOnMain { failPermanently(app, station, reason) }
                return@launch
            }
            // A full rebuild rather than a re-prepare: the address changed, so the player is being
            // pointed somewhere new rather than asked to try the same place again.
            runOnMain { startPlayer(app, station, fresh) }
        }
    }

    /** A permanently-failed tune: keep the error visible in the UI, but release the player (freeing its
     *  audio focus + decoder) and stop the foreground service. Nothing retries a permanent failure, so
     *  holding either is pure leak — and it left an orphaned `mediaPlayback` service + notification up. */
    private fun failPermanently(app: Context, station: RadioStation, reason: String?) {
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
