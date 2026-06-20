package dev.mascwa.pulse.data.spotify

import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.settings.SpotifyAuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A Spotify account snapshot. [isPremium] gates the in-app Web Playback SDK (free accounts can still
 *  read now-playing + control an active device). */
data class SpotifyProfile(val displayName: String, val isPremium: Boolean)

/** What's playing right now on the account's active device (null when nothing/no active device). */
data class SpotifyPlayback(
    val trackName: String,
    val artist: String,
    val albumArtUrl: String?,
    val isPlaying: Boolean,
    val deviceName: String,
    val progressMs: Long,
    val durationMs: Long,
    val trackUri: String,
)

/** A Spotify Connect device the account can play on. */
data class SpotifyDevice(val id: String, val name: String, val type: String, val isActive: Boolean)

/** A track search result. */
data class SpotifyTrack(val name: String, val artist: String, val uri: String, val albumArtUrl: String?)

/**
 * High-level Spotify Web API client. Holds the OAuth lifecycle (begin/complete/refresh/disconnect) over
 * [SettingsRepository] (tokens persist in the settings JSON; the backup export scrubs them) and exposes
 * read/control calls used by the PIP-BOY MUSIC tab: profile, now-playing, devices, transport, transfer
 * and search. Tokens auto-refresh when near expiry. Fully defensive — every call yields null / false on
 * failure rather than throwing.
 *
 * NOTE: the Web API can only CONTROL playback on an already-active Spotify Connect device. In-app audio
 * (so the phone itself is the device) comes from the Web Playback SDK in a follow-up — Premium only.
 */
class SpotifyRepository(private val settings: SettingsRepository) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val base = "https://api.spotify.com/v1"

    val authState = settings.settings // Flow<AppSettings>; UI reads .spotify off it

    /** Generate + persist a PKCE verifier and return the browser authorize URL to launch. */
    suspend fun beginAuthUrl(): String {
        val verifier = SpotifyAuth.generateCodeVerifier()
        settings.update { it.copy(spotify = it.spotify.copy(pendingVerifier = verifier)) }
        return SpotifyAuth.authorizeUrl(SpotifyAuth.codeChallenge(verifier))
    }

    /** Complete the redirect: exchange [code] with the stored verifier, persist tokens, fetch the name. */
    suspend fun completeAuth(code: String): Boolean {
        val verifier = settings.current().spotify.pendingVerifier
        if (verifier.isBlank()) return false
        val tokens = SpotifyAuth.exchangeCode(code, verifier) ?: return false
        val expiresAt = System.currentTimeMillis() + tokens.expiresInSec * 1000L
        settings.update {
            it.copy(
                spotify = SpotifyAuthState(
                    accessToken = tokens.accessToken,
                    refreshToken = tokens.refreshToken.orEmpty(),
                    expiresAtMs = expiresAt,
                    pendingVerifier = "",
                ),
            )
        }
        // Best-effort display name + premium flag for the UI header.
        runCatching { profile() }.getOrNull()?.let { p ->
            settings.update { it.copy(spotify = it.spotify.copy(displayName = p.displayName, premium = p.isPremium)) }
        }
        return true
    }

    suspend fun disconnect() = settings.update { it.copy(spotify = SpotifyAuthState()) }

    /** A valid access token, refreshing first if it's missing or within 60s of expiry; null if not linked. */
    private suspend fun token(): String? {
        val s = settings.current().spotify
        if (s.accessToken.isBlank()) return null
        if (System.currentTimeMillis() < s.expiresAtMs - 60_000L) return s.accessToken
        if (s.refreshToken.isBlank()) return null
        val refreshed = SpotifyAuth.refresh(s.refreshToken) ?: return null
        val expiresAt = System.currentTimeMillis() + refreshed.expiresInSec * 1000L
        settings.update {
            it.copy(
                spotify = it.spotify.copy(
                    accessToken = refreshed.accessToken,
                    refreshToken = refreshed.refreshToken?.ifBlank { it.spotify.refreshToken } ?: it.spotify.refreshToken,
                    expiresAtMs = expiresAt,
                ),
            )
        }
        return refreshed.accessToken
    }

    // ---- Reads ----

    suspend fun profile(): SpotifyProfile? {
        val (code, body) = request("GET", "/me") ?: return null
        if (code !in 200..299) return null
        val p = runCatching { json.decodeFromString(ApiProfile.serializer(), body) }.getOrNull() ?: return null
        return SpotifyProfile(p.displayName.ifBlank { p.id }, p.product.equals("premium", true))
    }

    suspend fun currentlyPlaying(): SpotifyPlayback? {
        val (code, body) = request("GET", "/me/player") ?: return null
        if (code == 204 || body.isBlank()) return null // nothing playing / no active device
        if (code !in 200..299) return null
        val p = runCatching { json.decodeFromString(ApiPlayback.serializer(), body) }.getOrNull() ?: return null
        val item = p.item ?: return null
        return SpotifyPlayback(
            trackName = item.name,
            artist = item.artists.joinToString(", ") { it.name }.ifBlank { "—" },
            albumArtUrl = item.album?.images?.firstOrNull()?.url,
            isPlaying = p.isPlaying,
            deviceName = p.device?.name.orEmpty(),
            progressMs = p.progressMs,
            durationMs = item.durationMs,
            trackUri = item.uri,
        )
    }

    suspend fun devices(): List<SpotifyDevice> {
        val (code, body) = request("GET", "/me/player/devices") ?: return emptyList()
        if (code !in 200..299) return emptyList()
        val d = runCatching { json.decodeFromString(ApiDevices.serializer(), body) }.getOrNull() ?: return emptyList()
        return d.devices.map { SpotifyDevice(it.id.orEmpty(), it.name, it.type, it.isActive) }
            .filter { it.id.isNotBlank() }
    }

    suspend fun search(query: String, limit: Int = 20): List<SpotifyTrack> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val (code, body) = request("GET", "/search?type=track&limit=$limit&q=$enc") ?: return emptyList()
        if (code !in 200..299) return emptyList()
        val r = runCatching { json.decodeFromString(ApiSearch.serializer(), body) }.getOrNull() ?: return emptyList()
        return r.tracks?.items.orEmpty().map {
            SpotifyTrack(it.name, it.artists.joinToString(", ") { a -> a.name }, it.uri, it.album?.images?.firstOrNull()?.url)
        }
    }

    // ---- Controls (target the active device unless a deviceId is given) ----

    suspend fun resume(deviceId: String? = null): Boolean = ok(request("PUT", "/me/player/play".dev(deviceId), "{}"))
    suspend fun pause(deviceId: String? = null): Boolean = ok(request("PUT", "/me/player/pause".dev(deviceId)))
    suspend fun next(deviceId: String? = null): Boolean = ok(request("POST", "/me/player/next".dev(deviceId)))
    suspend fun previous(deviceId: String? = null): Boolean = ok(request("POST", "/me/player/previous".dev(deviceId)))

    /** Start playing a specific track [uri] (optionally on [deviceId]). */
    suspend fun playUri(uri: String, deviceId: String? = null): Boolean =
        ok(request("PUT", "/me/player/play".dev(deviceId), """{"uris":["$uri"]}"""))

    /** Hand playback to [deviceId] (and start it). */
    suspend fun transferTo(deviceId: String, play: Boolean = true): Boolean =
        ok(request("PUT", "/me/player", """{"device_ids":["$deviceId"],"play":$play}"""))

    private fun ok(r: Pair<Int, String>?): Boolean = r != null && r.first in 200..299

    /** Append `?device_id=` when a target device is specified. */
    private fun String.dev(deviceId: String?): String =
        if (deviceId.isNullOrBlank()) this else this + (if (contains('?')) "&" else "?") + "device_id=$deviceId"

    /** One Bearer request. Returns (status, body) or null if not linked / transport failed. */
    private suspend fun request(method: String, path: String, jsonBody: String? = null): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val tok = token() ?: return@withContext null
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    setRequestProperty("Authorization", "Bearer $tok")
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    if (jsonBody != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
                if (jsonBody != null) conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                status to body
            } catch (_: Exception) {
                null
            } finally {
                runCatching { conn?.disconnect() }
            }
        }

    // ---- Raw API models (private) ----

    @Serializable private data class ApiProfile(
        @SerialName("display_name") val displayName: String = "",
        val id: String = "",
        val product: String = "",
    )

    @Serializable private data class ApiArtist(val name: String = "")
    @Serializable private data class ApiImage(val url: String = "")
    @Serializable private data class ApiAlbum(val name: String = "", val images: List<ApiImage> = emptyList())
    @Serializable private data class ApiTrack(
        val name: String = "",
        val uri: String = "",
        @SerialName("duration_ms") val durationMs: Long = 0,
        val artists: List<ApiArtist> = emptyList(),
        val album: ApiAlbum? = null,
    )
    @Serializable private data class ApiDevice(
        val id: String? = null,
        val name: String = "",
        val type: String = "",
        @SerialName("is_active") val isActive: Boolean = false,
    )
    @Serializable private data class ApiPlayback(
        val device: ApiDevice? = null,
        @SerialName("is_playing") val isPlaying: Boolean = false,
        @SerialName("progress_ms") val progressMs: Long = 0,
        val item: ApiTrack? = null,
    )
    @Serializable private data class ApiDevices(val devices: List<ApiDevice> = emptyList())
    @Serializable private data class ApiSearchTracks(val items: List<ApiTrack> = emptyList())
    @Serializable private data class ApiSearch(val tracks: ApiSearchTracks? = null)
}
