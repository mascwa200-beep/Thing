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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "real" Spotify player on Android: connects to the **installed Spotify app** via the App Remote SDK
 * and drives its playback — audio plays through the Spotify app, controlled from LCARS. Process-wide so
 * the link survives leaving the tab.
 *
 * Connection model (the native analogue of the web SDK's lifecycle the owner asked for — App Remote, not
 * the browser Web Playback SDK, since this is a native app):
 *  - App Remote connection is **foreground-scoped** and drops when the app backgrounds. A drop is treated
 *    as **transient**: we auto-reconnect with exponential backoff and keep [RemoteState.connecting] true
 *    throughout, so the "connect" card never flashes.
 *  - The card is only worth showing when we are genuinely **not connected and not (re)connecting** — i.e.
 *    after reconnect attempts are exhausted, or before the first connect.
 *  - Terminal reasons (Spotify not installed / not logged in / not authorized) surface a **clear one-time
 *    message** ([RemoteState.error]) — and only when the user explicitly asked (interactive); a silent
 *    attempt never nags.
 */
object SpotifyAppRemoteController {

    data class RemoteState(
        val connected: Boolean = false,
        /** True while an attempt or a backoff reconnect is in flight — the UI shows "reconnecting", NOT the card. */
        val connecting: Boolean = false,
        val trackName: String = "",
        val artist: String = "",
        val imageUri: String? = null,
        val artBitmap: android.graphics.Bitmap? = null,
        val isPlaying: Boolean = false,
        /** A clear one-time reason shown when not connected (e.g. not installed / authorize / unreachable). */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(RemoteState())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    private var remote: SpotifyAppRemote? = null
    private var lastArtUri: String? = null

    // Self-reconnect machinery. App Remote callbacks land on the main thread, so the scope is Main.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var reconnectJob: Job? = null
    private var attempts = 0
    private const val MAX_ATTEMPTS = 5

    val isConnected: Boolean get() = remote?.isConnected == true

    /**
     * Connect to the installed Spotify app. [interactive] = the user tapped Connect/Reconnect (show the
     * auth view + surface a clear error if it fails); else a silent (auto / resume / backoff) attempt that
     * never nags. Stores an app context so backoff reconnects can run without a fresh UI context.
     */
    fun connect(context: Context, interactive: Boolean = true) {
        appContext = context.applicationContext
        if (remote?.isConnected == true) return
        // Don't stack silent attempts on top of an in-flight (re)connect; an explicit tap overrides backoff.
        if (!interactive && _state.value.connecting) return
        reconnectJob?.cancel()
        attempts = 0
        attempt(context, interactive)
    }

    /** The small manual "Reconnect" control — resets backoff and tries interactively. */
    fun reconnect(context: Context) = connect(context, interactive = true)

    private fun attempt(context: Context, interactive: Boolean) {
        _state.value = _state.value.copy(connecting = true, error = null)
        val params = ConnectionParams.Builder(SpotifyAuth.CLIENT_ID)
            .setRedirectUri(SpotifyAuth.REDIRECT_URI)
            .showAuthView(interactive)
            .build()
        runCatching {
            SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
                override fun onConnected(appRemote: SpotifyAppRemote) {
                    remote = appRemote
                    attempts = 0
                    reconnectJob?.cancel()
                    _state.value = _state.value.copy(connected = true, connecting = false, error = null)
                    runCatching {
                        appRemote.playerApi.subscribeToPlayerState().setEventCallback { s -> onPlayerState(s) }
                    }
                }

                override fun onFailure(error: Throwable) {
                    remote = null
                    onConnectFailure(error, interactive)
                }
            })
        }.onFailure { onConnectFailure(it, interactive) }
    }

    /** Classify a failure: terminal (clear message) vs transient (silent backoff reconnect). */
    private fun onConnectFailure(error: Throwable, interactive: Boolean) {
        when (error) {
            is CouldNotFindSpotifyApp -> {
                reconnectJob?.cancel()
                _state.value = RemoteState(error = "Spotify isn't installed — install it to use the in-app player.")
            }
            is NotLoggedInException, is UserNotAuthorizedException -> {
                // Auth/account issue: a clear one-time message only on an explicit tap; silent stays quiet
                // (the card then shows just the plain invite, never a nag) — and never auto-retries it.
                reconnectJob?.cancel()
                _state.value = RemoteState(error = if (interactive) error.toFriendly() else null)
            }
            else -> scheduleReconnect() // transient drop / service blip → exponential-backoff silent reconnect
        }
    }

    private fun scheduleReconnect() {
        val ctx = appContext
        if (ctx == null || attempts >= MAX_ATTEMPTS) {
            reconnectJob?.cancel()
            // Genuinely unreachable after retries → now it's worth showing the card with a retry hint.
            _state.value = RemoteState(
                error = if (attempts >= MAX_ATTEMPTS) "Couldn't reach Spotify — tap Reconnect." else null,
            )
            return
        }
        // Stay "connecting" for the whole backoff window so the connect card never flashes on a drop.
        _state.value = _state.value.copy(connected = false, connecting = true, error = null)
        val delayMs = (1000L shl attempts).coerceAtMost(16_000L) // 1s, 2s, 4s, 8s, 16s
        attempts++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            attempt(ctx, interactive = false)
        }
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
        reconnectJob?.cancel()
        attempts = 0
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
        is CouldNotFindSpotifyApp -> "Spotify isn't installed — install it to use the in-app player."
        is NotLoggedInException -> "Open Spotify and log in, then tap Reconnect."
        is UserNotAuthorizedException -> "Authorize Pulse in Spotify to connect (you only do this once)."
        else -> message ?: this::class.java.simpleName
    }
}
