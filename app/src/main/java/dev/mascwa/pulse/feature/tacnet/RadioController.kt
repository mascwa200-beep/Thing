package dev.mascwa.pulse.feature.tacnet

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.data.radio.IcyMetadata
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
 * Process-wide radio playback: one [MediaPlayer] that keeps playing after the PIP-BOY — or the whole
 * Activity — goes away, kept alive by [RadioService] (a `mediaPlayback` foreground service). The
 * ViewModel and the service notification both drive and observe this single object, so "what's on air"
 * has one source of truth. Defensive throughout — a bad URL / network drop lands in [Status.ERROR],
 * never a crash.
 */
object RadioController {

    enum class Status { IDLE, TUNING, ON_AIR, ERROR }

    /** Tuned station tracked by identity (not list index) so it stays lit as the local list loads in. */
    data class RadioState(val tuned: RadioStation? = null, val status: Status = Status.IDLE)

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    /** Active sleep-timer duration in minutes, or null when off. Playback auto-stops when it elapses. */
    private val _sleepMinutes = MutableStateFlow<Int?>(null)
    val sleepMinutes: StateFlow<Int?> = _sleepMinutes.asStateFlow()

    /** Live "Artist - Song" for the tuned station (ICY stream metadata), or null when unavailable. */
    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob())
    private var sleepJob: Job? = null
    private var metaJob: Job? = null
    private var player: MediaPlayer? = null

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
        releasePlayer()
        _state.value = RadioState(station, Status.TUNING)
        startMetaPolling(station)
        // Promote to a foreground service so the stream survives leaving the app (defensive: a denied
        // FGS start just means foreground-only playback, never a crash).
        runCatching { ContextCompat.startForegroundService(app, Intent(app, RadioService::class.java)) }
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(station.streamUrl)
                setOnPreparedListener { it.start(); _state.value = RadioState(station, Status.ON_AIR) }
                setOnErrorListener { _, _, _ -> _state.value = RadioState(station, Status.ERROR); true }
                prepareAsync()
            }
        }.onFailure { _state.value = RadioState(station, Status.ERROR) }
    }

    fun stop(context: Context) {
        sleepJob?.cancel()
        _sleepMinutes.value = null
        metaJob?.cancel()
        _nowPlaying.value = null
        releasePlayer()
        _state.value = RadioState(null, Status.IDLE)
        runCatching {
            val app = context.applicationContext
            app.stopService(Intent(app, RadioService::class.java))
        }
    }

    /** Poll the tuned station's ICY now-playing title every ~25 s while it's the current one. */
    private fun startMetaPolling(station: RadioStation) {
        metaJob?.cancel()
        _nowPlaying.value = null
        metaJob = scope.launch {
            while (_state.value.tuned?.streamUrl == station.streamUrl) {
                val title = runCatching { IcyMetadata.nowPlaying(station.streamUrl) }.getOrNull()
                if (_state.value.tuned?.streamUrl == station.streamUrl) _nowPlaying.value = title
                delay(25_000)
            }
        }
    }

    private fun releasePlayer() {
        runCatching { player?.reset(); player?.release() }
        player = null
    }
}
