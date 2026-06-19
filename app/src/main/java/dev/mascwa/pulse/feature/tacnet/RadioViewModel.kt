package dev.mascwa.pulse.feature.tacnet

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import dev.mascwa.pulse.data.radio.DEFAULT_STATIONS
import dev.mascwa.pulse.data.radio.RadioStation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the PIP-BOY RADIO feed: a small, contextless [MediaPlayer] streaming one station at a time.
 * Held in the ViewModel so audio survives PIP-BOY sub-tab switches; released on [onCleared]. Defensive
 * throughout — a bad URL / network drop lands in [Status.ERROR], never a crash. Background playback
 * (a foreground service) is a deliberate follow-up; for now audio plays while the PIP-BOY is open.
 */
class RadioViewModel : ViewModel() {

    enum class Status { IDLE, TUNING, ON_AIR, ERROR }

    data class RadioState(val index: Int? = null, val status: Status = Status.IDLE)

    val stations: List<RadioStation> = DEFAULT_STATIONS

    private val _state = MutableStateFlow(RadioState())
    val state: StateFlow<RadioState> = _state.asStateFlow()

    private var player: MediaPlayer? = null

    /** Tap a station: tune it, or stop if it's the one already tuning/on air. */
    fun toggle(index: Int) {
        val s = _state.value
        if (s.index == index && (s.status == Status.ON_AIR || s.status == Status.TUNING)) stop() else tune(index)
    }

    private fun tune(index: Int) {
        val station = stations.getOrNull(index) ?: return
        releasePlayer()
        _state.value = RadioState(index, Status.TUNING)
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(station.streamUrl)
                setOnPreparedListener { it.start(); _state.value = RadioState(index, Status.ON_AIR) }
                setOnErrorListener { _, _, _ -> _state.value = RadioState(index, Status.ERROR); true }
                prepareAsync()
            }
        }.onFailure { _state.value = RadioState(index, Status.ERROR) }
    }

    fun stop() {
        releasePlayer()
        _state.value = RadioState(null, Status.IDLE)
    }

    private fun releasePlayer() {
        runCatching { player?.reset(); player?.release() }
        player = null
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}
