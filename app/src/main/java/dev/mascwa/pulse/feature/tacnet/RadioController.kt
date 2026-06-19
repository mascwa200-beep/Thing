package dev.mascwa.pulse.feature.tacnet

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.data.radio.RadioStation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    private var player: MediaPlayer? = null

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
        releasePlayer()
        _state.value = RadioState(null, Status.IDLE)
        runCatching {
            val app = context.applicationContext
            app.stopService(Intent(app, RadioService::class.java))
        }
    }

    private fun releasePlayer() {
        runCatching { player?.reset(); player?.release() }
        player = null
    }
}
