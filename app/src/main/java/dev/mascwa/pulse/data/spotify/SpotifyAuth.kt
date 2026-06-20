package dev.mascwa.pulse.data.spotify

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/** The tokens returned by an exchange/refresh. [refreshToken] can be null on a refresh (Spotify may
 *  reuse the existing one), in which case the caller keeps the one it already has. */
data class SpotifyTokens(val accessToken: String, val refreshToken: String?, val expiresInSec: Int)

/**
 * Spotify OAuth via **Authorization Code with PKCE** — the correct flow for a public mobile client, so
 * **no client secret is embedded** (and none is needed). The client ID is public. Pure helpers build the
 * code verifier/challenge and the authorize URL; [exchangeCode]/[refresh] POST the token endpoint with a
 * dependency-free `HttpURLConnection`. Fully defensive — any failure yields null.
 *
 * Setup the owner does once in the Spotify dashboard: register the redirect URI **[REDIRECT_URI]** under
 * the app's settings. In-app audio (the Web Playback SDK) additionally needs a **Spotify Premium** account.
 */
object SpotifyAuth {

    /** Public client ID (safe to ship; PKCE means no secret is required or stored). */
    const val CLIENT_ID = "b27848e6b0024fbcb9f5f687f504178d"
    const val REDIRECT_URI = "dev.mascwa.pulse://spotify-callback"
    const val REDIRECT_SCHEME = "dev.mascwa.pulse"
    const val REDIRECT_HOST = "spotify-callback"

    private const val AUTH_URL = "https://accounts.spotify.com/authorize"
    private const val TOKEN_URL = "https://accounts.spotify.com/api/token"

    /** Read the profile, read/control playback (now-playing + transport), and read the user's library
     *  (playlists + recently-played). A scope change requires the user to re-link to take effect; until
     *  then the library calls 403 and the sections degrade to empty. */
    val SCOPES = listOf(
        "user-read-email",
        "user-read-private",
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "user-read-recently-played",
        "playlist-read-private",
        "playlist-read-collaborative",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A high-entropy PKCE code verifier (base64url, no padding). */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** S256 challenge = base64url(SHA-256(verifier)). */
    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** The browser URL that asks the user to authorize, given a PKCE [challenge]. */
    fun authorizeUrl(challenge: String): String {
        val params = linkedMapOf(
            "client_id" to CLIENT_ID,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "scope" to SCOPES.joinToString(" "),
        )
        return AUTH_URL + "?" + encodeForm(params)
    }

    suspend fun exchangeCode(code: String, verifier: String): SpotifyTokens? = post(
        linkedMapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to CLIENT_ID,
            "code_verifier" to verifier,
        ),
    )

    suspend fun refresh(refreshToken: String): SpotifyTokens? = post(
        linkedMapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to CLIENT_ID,
        ),
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int = 3600,
    )

    private suspend fun post(form: Map<String, String>): SpotifyTokens? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val body = encodeForm(form)
            conn = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: return@withContext null
            if (code !in 200..299) return@withContext null
            val parsed = json.decodeFromString(TokenResponse.serializer(), text)
            if (parsed.accessToken.isBlank()) return@withContext null
            SpotifyTokens(parsed.accessToken, parsed.refreshToken, parsed.expiresIn)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun encodeForm(params: Map<String, String>): String =
        params.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
}
