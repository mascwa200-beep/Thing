package dev.mascwa.pulse.data.spotify

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.types.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The "real" Spotify player on Android: connects to the **installed Spotify app** via the App Remote SDK
 * and drives its playback — audio plays through the Spotify app, controlled from PIP-BOY. Process-wide so
 * the link survives leaving the tab. Fully defensive: no Spotify app / not logged in / unauthorized lands
 * in [RemoteState.error] with a readable reason, never a crash.
 *
 * Needs the Spotify app installed + logged in, and the redirect URI registered in the dashboard. The
 * subscribed player state feeds the live now-playing readout + transport.
 */
object SpotifyAppRemoteController {

    data class RemoteState(
        val connected: Boolean = false,
        val connecting: Boolean = false,
        val trackName: String = "",
        val artist: String = "",
        val imageUri: String? = null,
        val isPlaying: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private var remote: SpotifyAppRemote? = null

    val isConnected: Boolean get() = remote?.isConnected == true

    fun connect(context: Context) {
        if (remote?.isConnected == true) return
        _state.value = _state.value.copy(connecting = true, error = null)
        val params = ConnectionParams.Builder(SpotifyAuth.CLIENT_ID)
            .setRedirectUri(SpotifyAuth.REDIRECT_URI)
            .showAuthView(true)
            .build()
        runCatching {
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    remote = appRemote
                    _state.value = _state.value.copy(connected = true, connecting = false, error = null)
                    runCatching {
                        appRemote.playerApi.subscribeToPlayerState().setEventCallback { s -> onPlayerState(s) }
                    }
                }

                override fun onFailure(error: Throwable) {
                    remote = null
                    _state.value = RemoteState(error = error.toFriendly())
                }
            })
        }.onFailure { _state.value = RemoteState(error = it.toFriendly()) }
    }

    private fun onPlayerState(s: PlayerState) {
        val t = s.track
        _state.value = _state.value.copy(
            connected = true,
            connecting = false,
            trackName = t?.name.orEmpty(),
            artist = t?.artist?.name.orEmpty(),
            imageUri = t?.imageUri?.raw,
            isPlaying = !s.isPaused,
            error = null,
        )
    }

    fun disconnect() {
        remote?.let { runCatching { SpotifyAppRemote.disconnect(it) } }
        remote = null
        _state.value = RemoteState()
    }

    fun playUri(uri: String) { runCatching { remote?.playerApi?.play(uri) } }
    fun resume() { runCatching { remote?.playerApi?.resume() } }
    fun pause() { runCatching { remote?.playerApi?.pause() } }
    fun next() { runCatching { remote?.playerApi?.skipNext() } }
    fun previous() { runCatching { remote?.playerApi?.skipPrevious() } }
    fun togglePlayPause() { if (_state.value.isPlaying) pause() else resume() }

    private fun Throwable.toFriendly(): String = when (this) {
        is CouldNotFindSpotifyApp -> "Spotify app not installed"
        is NotLoggedInException -> "Log in to the Spotify app first"
        is UserNotAuthorizedException -> "Authorize Pulse in the Spotify app"
        else -> message ?: this::class.java.simpleName
    }
}
