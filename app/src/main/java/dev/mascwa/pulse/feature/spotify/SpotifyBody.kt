package dev.mascwa.pulse.feature.spotify

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import dev.mascwa.pulse.data.spotify.SpotifyAppRemoteController
import dev.mascwa.pulse.data.spotify.SpotifyDevice
import dev.mascwa.pulse.data.spotify.SpotifyPlayback
import dev.mascwa.pulse.data.spotify.SpotifyPlaylist
import dev.mascwa.pulse.data.spotify.SpotifyTrack
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PipHeader
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

/** Caution amber for App-Remote errors, readable against the green CRT. */
private val Amber = Color(0xFFE0B341)

/** A green-phosphor duotone (luminance → green) so album art reads as part of the Pip-Boy CRT instead
 *  of a jarring full-colour thumbnail. R/B carry a faint share for body; G carries the luminance. */
private val PhosphorDuotone = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.030f, 0.059f, 0.011f, 0f, 0f, // R = 0.10·luma
            0.299f, 0.587f, 0.114f, 0f, 0f, // G = luma
            0.090f, 0.176f, 0.034f, 0f, 0f, // B = 0.30·luma
            0f, 0f, 0f, 1f, 0f,             // A = A
        ),
    ),
)

/** A framed, green-duotone album thumbnail — from an App Remote [bitmap] or a Web API [url]. */
@Composable
private fun AlbumArt(url: String?, bitmap: android.graphics.Bitmap?, c: NightwirePalette, side: Dp) {
    if (url == null && bitmap == null) return
    Box(
        Modifier.size(side).clip(RoundedCornerShape(4.dp))
            .background(c.accent.copy(alpha = 0.06f))
            .border(1.dp, c.accent.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), "Album art", Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, colorFilter = PhosphorDuotone)
        } else if (url != null) {
            AsyncImage(model = url, contentDescription = "Album art", modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, colorFilter = PhosphorDuotone)
        }
    }
}

/**
 * The PIP-BOY MUSIC (Spotify) feed. Two layers in the Fallout green idiom:
 *  1) **PLAYER** — the App Remote "real player": connects to the installed Spotify app and plays/controls
 *     it in-app (the audio comes from the Spotify app). Works standalone.
 *  2) **ACCOUNT** — the Web API: link an account to search the catalogue and pick Connect devices. Search
 *     results play through the App Remote player when it's connected, otherwise the active Connect device.
 */
@Composable
fun SpotifyBody(vm: SpotifyViewModel, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    val context = LocalContext.current
    val auth by vm.auth.collectAsStateWithLifecycle()
    val remote by vm.remoteState.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val searchStatus by vm.searchStatus.collectAsStateWithLifecycle()
    val statusMsg by vm.status.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(auth.linked) { if (auth.linked) vm.refresh() }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        // ---- PLAYER (App Remote — the real in-app player) ----
        PipHeader("Player", trailing = if (remote.connected) "CONNECTED" else null)
        if (remote.connected) {
            ConnectedPlayer(remote, c, vm) { vm.disconnectApp() }
        } else {
            AppPlayerCard(remote, c) { vm.connectApp(context) }
        }
        // A control-result note (Premium needed / no device / failed), tappable to dismiss.
        statusMsg?.let { msg ->
            Text(
                "⚠ $msg", fontFamily = JetBrainsMono, fontSize = 10.sp, color = Amber,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable { vm.clearStatus() },
            )
        }

        // ---- ACCOUNT (Web API: search + devices) ----
        if (!auth.linked) {
            PipHeader("Account")
            PipFrame(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Link a Spotify account to search the catalogue and choose Connect devices.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                    )
                    Text(
                        "The player above controls your installed Spotify app and works without linking.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    PipButton("▸ LINK ACCOUNT", c, Modifier.padding(top = 12.dp)) { vm.connect(context) }
                }
            }
        } else {
            // When the App Remote player isn't connected, show what's on a Web-API Connect device.
            if (!remote.connected) {
                PipHeader("Now Playing", trailing = auth.displayName.ifBlank { "LINKED" })
                WebNowPlaying(playback, c, vm)
            }

            // ---- SEARCH ----
            PipHeader("Search")
            PipFrame(Modifier.fillMaxWidth()) {
                Column {
                    SpotifyField(query, { query = it }, "Search tracks…", c)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PipButton("▸ SEARCH", c, Modifier.weight(1f)) { vm.search(query) }
                        if (query.isNotEmpty() || results.isNotEmpty()) {
                            PipButton("CLEAR", c, Modifier) { query = ""; vm.clearSearch() }
                        }
                    }
                }
            }
            when (searchStatus) {
                SpotifyViewModel.SearchStatus.LOADING -> StatusLine("Searching…", c)
                SpotifyViewModel.SearchStatus.EMPTY -> StatusLine("No tracks found.", c)
                else -> results.forEach { track -> TrackRow(track, c) { vm.play(track) } }
            }

            // ---- YOUR PLAYLISTS ----
            if (playlists.isNotEmpty()) {
                PipHeader("Your Playlists", trailing = playlists.size.toString())
                playlists.forEach { pl -> PlaylistRow(pl, c) { vm.playPlaylist(pl) } }
            }

            // ---- RECENTLY PLAYED ----
            if (recent.isNotEmpty()) {
                PipHeader("Recently Played")
                recent.forEach { track -> TrackRow(track, c) { vm.play(track) } }
            }

            // ---- DEVICES ----
            PipHeader("Devices", trailing = devices.size.toString())
            if (devices.isEmpty()) {
                StatusLine("No devices. Open Spotify on a phone/desktop/speaker to make one available.", c)
            } else {
                devices.forEach { d -> DeviceRow(d, c) { vm.transferTo(d) } }
            }

            Box(Modifier.padding(top = 18.dp, bottom = 8.dp)) {
                PipButton("UNLINK ACCOUNT", c, Modifier) { vm.disconnect() }
            }
        }
        Box(Modifier.padding(bottom = 16.dp))
    }
}

/** The App Remote connect card (shown until connected). */
@Composable
private fun AppPlayerCard(remote: SpotifyAppRemoteController.RemoteState, c: NightwirePalette, onConnect: () -> Unit) {
    if (remote.connected) return
    PipFrame(Modifier.fillMaxWidth()) {
        Column {
            Text(
                "Connect to your installed Spotify app to play and control music in-app.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
            )
            if (remote.error != null) {
                Text("⚠ ${remote.error}", fontFamily = JetBrainsMono, fontSize = 10.sp, color = Amber,
                    modifier = Modifier.padding(top = 6.dp))
            }
            PipButton(
                if (remote.connecting) "CONNECTING…" else "▸ CONNECT SPOTIFY APP",
                c, Modifier.padding(top = 12.dp),
            ) { if (!remote.connecting) onConnect() }
        }
    }
}

/** The connected App Remote player — live now-playing + transport (drives the Spotify app). */
@Composable
private fun ConnectedPlayer(
    remote: SpotifyAppRemoteController.RemoteState,
    c: NightwirePalette,
    vm: SpotifyViewModel,
    onDisconnect: () -> Unit,
) {
    PipFrame(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlbumArt(null, remote.artBitmap, c, 60.dp)
                Column(Modifier.padding(start = if (remote.artBitmap != null) 12.dp else 0.dp)) {
                    Text(remote.trackName.ifBlank { "—" }, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                        fontSize = 16.sp, color = c.ink)
                    if (remote.artist.isNotBlank()) {
                        Text(remote.artist, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(Icons.Filled.SkipPrevious, "Previous", c) { vm.previous() }
                TransportButton(
                    if (remote.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play/Pause", c, primary = true,
                ) { vm.togglePlayPause() }
                TransportButton(Icons.Filled.SkipNext, "Next", c) { vm.next() }
                Box(Modifier.weight(1f))
                Text("DISCONNECT", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                    letterSpacing = 1.sp, color = c.muted, modifier = Modifier.clickable { onDisconnect() })
            }
        }
    }
}

/** Web-API now-playing (a Connect device), shown when the App Remote player isn't connected. */
@Composable
private fun WebNowPlaying(playback: SpotifyPlayback?, c: NightwirePalette, vm: SpotifyViewModel) {
    PipFrame(Modifier.fillMaxWidth()) {
        Column {
            if (playback == null) {
                Text(
                    "Nothing playing. Connect the player above, pick a device, or search a track.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(playback.albumArtUrl, null, c, 60.dp)
                    Column(Modifier.padding(start = if (playback.albumArtUrl != null) 12.dp else 0.dp)) {
                        Text(playback.trackName, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink)
                        Text(playback.artist, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2,
                            modifier = Modifier.padding(top = 2.dp))
                        if (playback.deviceName.isNotBlank()) {
                            Text("◈ ${playback.deviceName}", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(Icons.Filled.SkipPrevious, "Previous", c) { vm.previous() }
                TransportButton(
                    if (playback?.isPlaying == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play/Pause", c, primary = true,
                ) { vm.togglePlayPause() }
                TransportButton(Icons.Filled.SkipNext, "Next", c) { vm.next() }
            }
        }
    }
}

@Composable
private fun TransportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    c: NightwirePalette,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val s = if (primary) 52.dp else 42.dp
    Box(
        Modifier.size(s).clip(CircleShape)
            .background(if (primary) c.accent.copy(alpha = 0.16f) else c.accent.copy(alpha = 0.06f))
            .border(1.dp, c.accent, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = c.accent, modifier = Modifier.size(if (primary) 28.dp else 22.dp))
    }
}

@Composable
private fun TrackRow(track: SpotifyTrack, c: NightwirePalette, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(c.accent.copy(alpha = 0.05f))
            .clickable { onPlay() }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(track.albumArtUrl, null, c, 40.dp)
        Column(Modifier.weight(1f).padding(start = if (track.albumArtUrl != null) 10.dp else 0.dp)) {
            Text(track.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
            Text(track.artist, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink2,
                modifier = Modifier.padding(top = 1.dp))
        }
        Icon(Icons.Filled.PlayArrow, "Play", tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PlaylistRow(pl: SpotifyPlaylist, c: NightwirePalette, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).background(c.accent.copy(alpha = 0.05f))
            .clickable { onPlay() }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(pl.imageUrl, null, c, 40.dp)
        Column(Modifier.weight(1f).padding(start = if (pl.imageUrl != null) 10.dp else 0.dp)) {
            Text(pl.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
            val sub = (if (pl.ownerName.isNotBlank()) "${pl.ownerName} · " else "") + "${pl.trackCount} tracks"
            Text(sub, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted, modifier = Modifier.padding(top = 1.dp))
        }
        Icon(Icons.Filled.PlayArrow, "Play", tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DeviceRow(device: SpotifyDevice, c: NightwirePalette, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .background(if (device.isActive) c.accent.copy(alpha = 0.12f) else c.accent.copy(alpha = 0.04f))
            .clickable { onSelect() }.padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (device.isActive) "▶" else "○", fontFamily = JetBrainsMono, fontSize = 12.sp,
            color = if (device.isActive) c.accent else c.muted, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
            Text(device.type.uppercase(), fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.muted)
        }
    }
}

@Composable
private fun StatusLine(text: String, c: NightwirePalette) {
    Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun PipButton(label: String, c: NightwirePalette, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, c.accent, RoundedCornerShape(6.dp))
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            letterSpacing = 1.5.sp, color = c.accent)
    }
}

@Composable
private fun SpotifyField(value: String, onChange: (String) -> Unit, placeholder: String, c: NightwirePalette) {
    Box(
        Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
