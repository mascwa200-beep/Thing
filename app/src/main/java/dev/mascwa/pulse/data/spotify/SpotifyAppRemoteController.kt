package dev.mascwa.pulse.data.spotify

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.types.Image
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
        val artBitmap: android.graphics.Bitmap? = null,
        val isPlaying: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private var remote: SpotifyAppRemote? = null
    private var lastArtUri: String? = null

    val isConnected: Boolean get() = remote?.isConnected == true

    /**
     * Connect to the installed Spotify app.
     *
     * App Remote has its **own** authorization with the Spotify app (separate from the Web-API OAuth
     * token): the first connect needs the user to approve Pulse once, and Spotify only *remembers* that
     * grant when this app's package + signing SHA-1 + redirect URI are registered in the Spotify
     * dashboard. [interactive] picks how an un-authorized state is handled:
     *  - `true`  (the user tapped CONNECT): show Spotify's auth view so they can grant access, and surface
     *            a readable error if it still fails.
     *  - `false` (silent auto-reconnect on tab open): never show the auth view and never surface an error
     *            — if not yet authorized it just fails quietly back to the CONNECT card, so the user isn't
     *            nagged with "Authorize Pulse…" on every single open. Once authorized, silent reconnect
     *            succeeds with no prompt — the "connect once, then auto-reconnect" behaviour.
     */
    fun connect(context: Context, interactive: Boolean = true) {
        if (remote?.isConnected == true) return
        _state.value = _state.value.copy(connecting = true, error = null)
        val params = ConnectionParams.Builder(SpotifyAuth.CLIENT_ID)
            .setRedirectUri(SpotifyAuth.REDIRECT_URI)
            .showAuthView(interactive)
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
                    // A silent auto-attempt that isn't authorized yet drops back to the plain CONNECT card
                    // (no alarming amber line); only an explicit tap explains why it failed.
                    _state.value = if (interactive) RemoteState(error = error.toFriendly()) else RemoteState()
                }
            })
        }.onFailure { _state.value = if (interactive) RemoteState(error = it.toFriendly()) else RemoteState() }
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
        // Album art comes as a Bitmap from App Remote's ImagesApi; fetch it only when the track changes.
        val uri = t?.imageUri
        when {
            uri?.raw == null -> { lastArtUri = null; _state.value = _state.value.copy(artBitmap = null) }
            uri.raw != lastArtUri -> {
                lastArtUri = uri.raw
                runCatching {
                    remote?.imagesApi?.getImage(uri, Image.Dimension.MEDIUM)?.setResultCallback { bmp ->
                        if (lastArtUri == uri.raw) _state.value = _state.value.copy(artBitmap = bmp)
                    }
                }
            }
        }
    }

    fun disconnect() {
        remote?.let { runCatching { SpotifyAppRemote.disconnect(it) } }
        remote = null
        lastArtUri = null
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
