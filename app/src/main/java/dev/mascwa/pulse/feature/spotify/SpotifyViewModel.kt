package dev.mascwa.pulse.feature.spotify

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.data.settings.SpotifyAuthState
import dev.mascwa.pulse.data.spotify.PlaybackResult
import dev.mascwa.pulse.data.spotify.SpotifyAppRemoteController
import dev.mascwa.pulse.data.spotify.SpotifyDevice
import dev.mascwa.pulse.data.spotify.SpotifyPlayback
import dev.mascwa.pulse.data.spotify.SpotifyPlaylist
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

    private val _playlists = MutableStateFlow<List<SpotifyPlaylist>>(emptyList())
    val playlists: StateFlow<List<SpotifyPlaylist>> = _playlists.asStateFlow()

    private val _recent = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val recent: StateFlow<List<SpotifyTrack>> = _recent.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SpotifyTrack>>(emptyList())
    val searchResults: StateFlow<List<SpotifyTrack>> = _searchResults.asStateFlow()

    private val _searchStatus = MutableStateFlow(SearchStatus.IDLE)
    val searchStatus: StateFlow<SearchStatus> = _searchStatus.asStateFlow()

    /** A transient one-line explanation of the last control result (e.g. Premium / no device), or null. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private var searchJob: Job? = null

    /** The App Remote "real player" — controls the installed Spotify app (audio plays through it). */
    val remoteState = SpotifyAppRemoteController.state

    init {
        // Keep the now-playing readout live while linked.
        viewModelScope.launch {
            while (true) {
                if (auth.value.linked) refreshPlayback()
                delay(5_000)
            }
        }
    }

    /** Connect the App Remote player to the installed Spotify app (the real in-app player). This is the
     *  explicit user tap, so it shows Spotify's auth view to grant access once. Remembers the intent so
     *  the MUSIC tab can silently auto-reconnect next time (Spotify wakes in the background). */
    fun connectApp(context: Context) {
        viewModelScope.launch { repo.setAutoConnect(true) }
        SpotifyAppRemoteController.connect(context, interactive = true)
    }

    fun disconnectApp() {
        viewModelScope.launch { repo.setAutoConnect(false) }
        SpotifyAppRemoteController.disconnect()
    }

    /** Auto-reconnect path used on tab open — a SILENT connect (no auth view, no error nag) so the user is
     *  never re-prompted to "Authorize Pulse" on every open; once authorized it reconnects with no prompt. */
    fun autoConnectApp(context: Context) = SpotifyAppRemoteController.connect(context, interactive = false)

    /** The manual "Reconnect" control — resets backoff and tries interactively (for the rare stuck case). */
    fun reconnectApp(context: Context) {
        viewModelScope.launch { repo.setAutoConnect(true) }
        SpotifyAppRemoteController.reconnect(context)
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
        _playlists.value = repo.playlists()
        _recent.value = repo.recentlyPlayed()
    }

    private suspend fun refreshPlayback() { _playback.value = repo.currentlyPlaying() }

    /** A device to target when nothing is active: the active one, else the first available (so playback
     *  actually starts on, e.g., the only Connect speaker, instead of 404-ing with "no active device"). */
    private suspend fun ensureDevice(): String? {
        val devs = repo.devices()
        _devices.value = devs
        return devs.firstOrNull { it.isActive }?.id ?: devs.firstOrNull()?.id
    }

    // Transport routes to the App Remote player when it's connected (audio in the Spotify app), else to
    // the Web API (controls whatever Connect device is active — falling back to the first device).

    fun togglePlayPause() {
        if (SpotifyAppRemoteController.isConnected) { SpotifyAppRemoteController.togglePlayPause(); return }
        viewModelScope.launch {
            val res = if (_playback.value?.isPlaying == true) {
                repo.pause()
            } else {
                // Resuming with no active device → target one so it actually starts.
                val target = if (_playback.value == null) ensureDevice() else null
                repo.resume(target)
            }
            reportStatus(res)
            delay(400); refreshPlayback()
        }
    }

    fun next() {
        if (SpotifyAppRemoteController.isConnected) { SpotifyAppRemoteController.next(); return }
        viewModelScope.launch { reportStatus(repo.next()); delay(600); refreshPlayback() }
    }

    fun previous() {
        if (SpotifyAppRemoteController.isConnected) { SpotifyAppRemoteController.previous(); return }
        viewModelScope.launch { reportStatus(repo.previous()); delay(600); refreshPlayback() }
    }

    fun transferTo(device: SpotifyDevice) = viewModelScope.launch {
        reportStatus(repo.transferTo(device.id)); delay(800); refreshPlayback(); _devices.value = repo.devices()
    }

    fun play(track: SpotifyTrack) {
        if (SpotifyAppRemoteController.isConnected) { SpotifyAppRemoteController.playUri(track.uri); return }
        viewModelScope.launch {
            // Start on the active device, or the first available — Web API play() no-ops without a target.
            val target = ensureDevice()
            reportStatus(repo.playUri(track.uri, target), targetWasNull = target == null)
            delay(600); refreshPlayback()
        }
    }

    /** Play a whole playlist (its context URI). App Remote plays any URI; Web API needs a target device. */
    fun playPlaylist(playlist: SpotifyPlaylist) {
        if (SpotifyAppRemoteController.isConnected) { SpotifyAppRemoteController.playUri(playlist.uri); return }
        viewModelScope.launch {
            val target = ensureDevice()
            reportStatus(repo.playContext(playlist.uri, target), targetWasNull = target == null)
            delay(600); refreshPlayback()
        }
    }

    /** Map a control outcome to a one-line UI message (cleared on success). */
    private fun reportStatus(res: PlaybackResult, targetWasNull: Boolean = false) {
        _status.value = when (res) {
            PlaybackResult.OK -> null
            PlaybackResult.NO_DEVICE ->
                if (targetWasNull) "No device found. Open Spotify on a phone, desktop or speaker first."
                else "Couldn't reach that device — open Spotify on it."
            PlaybackResult.NEEDS_PREMIUM -> "Spotify Premium is required to control playback from here."
            PlaybackResult.FAILED -> "Couldn't control playback — check the connection and try again."
        }
    }

    fun clearStatus() { _status.value = null }

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
