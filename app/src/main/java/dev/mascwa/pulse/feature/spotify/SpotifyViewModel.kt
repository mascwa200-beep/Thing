package dev.mascwa.pulse.feature.spotify

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.settings.SpotifyAuthState
import dev.mascwa.pulse.data.spotify.SpotifyDevice
import dev.mascwa.pulse.data.spotify.SpotifyPlayback
import dev.mascwa.pulse.data.spotify.SpotifyRepository
import dev.mascwa.pulse.data.spotify.SpotifyTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the PIP-BOY MUSIC (Spotify) tab over [SpotifyRepository]. Surfaces the OAuth link state, polls
 * now-playing while linked, lists Connect devices, and runs transport/transfer/search. Connecting opens
 * the Spotify authorize page in the browser; the redirect is handled by [SpotifyAuthActivity].
 */
class SpotifyViewModel(private val repo: SpotifyRepository) : ViewModel() {

    enum class SearchStatus { IDLE, LOADING, READY, EMPTY }

    val auth: StateFlow<SpotifyAuthState> = repo.authState
        .map { it.spotify }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpotifyAuthState())

    private val _playback = MutableStateFlow<SpotifyPlayback?>(null)
    val playback: StateFlow<SpotifyPlayback?> = _playback.asStateFlow()

    private val _devices = MutableStateFlow<List<SpotifyDevice>>(emptyList())
    val devices: StateFlow<List<SpotifyDevice>> = _devices.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val searchResults: StateFlow<List<SpotifyTrack>> = _searchResults.asStateFlow()

    private val _searchStatus = MutableStateFlow(SearchStatus.IDLE)
    val searchStatus: StateFlow<SearchStatus> = _searchStatus.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Keep the now-playing readout live while linked.
        viewModelScope.launch {
            while (true) {
                if (auth.value.linked) refreshPlayback()
                delay(5_000)
            }
        }
    }

    /** Launch the Spotify authorize page in the browser (the redirect comes back to SpotifyAuthActivity). */
    fun connect(context: Context) {
        viewModelScope.launch {
            val url = repo.beginAuthUrl()
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    fun disconnect() = viewModelScope.launch {
        repo.disconnect()
        _playback.value = null
        _devices.value = emptyList()
    }

    fun refresh() = viewModelScope.launch {
        refreshPlayback()
        _devices.value = repo.devices()
    }

    private suspend fun refreshPlayback() { _playback.value = repo.currentlyPlaying() }

    fun togglePlayPause() = viewModelScope.launch {
        if (_playback.value?.isPlaying == true) repo.pause() else repo.resume()
        delay(400); refreshPlayback()
    }

    fun next() = viewModelScope.launch { repo.next(); delay(600); refreshPlayback() }
    fun previous() = viewModelScope.launch { repo.previous(); delay(600); refreshPlayback() }

    fun transferTo(device: SpotifyDevice) = viewModelScope.launch {
        repo.transferTo(device.id); delay(800); refreshPlayback(); _devices.value = repo.devices()
    }

    fun play(track: SpotifyTrack) = viewModelScope.launch { repo.playUri(track.uri); delay(600); refreshPlayback() }

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { _searchResults.value = emptyList(); _searchStatus.value = SearchStatus.IDLE; return }
        searchJob = viewModelScope.launch {
            _searchStatus.value = SearchStatus.LOADING
            val results = repo.search(query)
            _searchResults.value = results
            _searchStatus.value = if (results.isEmpty()) SearchStatus.EMPTY else SearchStatus.READY
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchResults.value = emptyList()
        _searchStatus.value = SearchStatus.IDLE
    }
}
