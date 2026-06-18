package dev.mascwa.pulse.data.update

import android.content.Context
import dev.mascwa.pulse.BuildConfig
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One available update: build number, display version, release notes, the browser APK URL, and the
 *  asset API URL (used with a token to fetch a PRIVATE repo's asset). */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkAssetUrl: String,
)

/**
 * Checks the project's rolling `latest` GitHub release (published by CI on every green build, versioned
 * by the run number) and downloads the APK. The repo is private, so the GitHub token from Settings is
 * sent on the API call and the asset download; public repos work token-free too. Installing is done from
 * the UI via the system installer (the user's authorization).
 */
class UpdateRepository(
    context: Context,
    private val http: HttpClient,
    private val settings: SettingsRepository,
) {

    private val appContext = context.applicationContext
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Serializable
    private data class GhRelease(val name: String = "", val body: String = "", val assets: List<GhAsset> = emptyList())

    @Serializable
    private data class GhAsset(val name: String = "", val url: String = "", val browser_download_url: String = "")

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** The configured GitHub token, or null when unset. Trimmed — pasted tokens often carry a trailing
     *  space/newline, which would corrupt the `Bearer` header and 401 the request. */
    suspend fun token(): String? =
        runCatching { settings.current().jarvis.githubToken }.getOrNull()?.trim()?.ifBlank { null }

    /**
     * Returns an [UpdateInfo] when the `latest` release is a higher build than this one, or null when
     * we're already current / unparseable. **Throws** on a network/HTTP failure (e.g. 404 for a private
     * repo with no token) so the caller can distinguish "up to date" from "couldn't reach the server".
     */
    suspend fun check(): UpdateInfo? {
        val headers = token()?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
        // The release is a prerelease, so /releases/latest won't return it — fetch it by its tag.
        val rel = http.getJson("$API/releases/tags/$TAG", GhRelease.serializer(), headers)
        val code = BUILD_NUM.find(rel.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: BUILD_NUM.find(rel.body)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val asset = rel.assets.firstOrNull { it.name.endsWith(".apk", true) || it.browser_download_url.endsWith(".apk", true) }
            ?: return null
        return UpdateInfo(code, "1.0.$code", rel.body.ifBlank { rel.name }, asset.browser_download_url, asset.url)
    }

    /** Stream the APK to cache, reporting 0..100 progress. Private repos: fetch the asset API URL with the
     *  token + octet-stream Accept (GitHub redirects to the signed blob); public: the browser URL. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "apk").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val tok = token()
        val useApi = tok != null && info.apkAssetUrl.isNotBlank()
        val req = Request.Builder()
            .url(if (useApi) info.apkAssetUrl else info.apkUrl)
            .header("User-Agent", HttpClient.USER_AGENT)
            .header("Accept", if (useApi) "application/octet-stream" else "application/vnd.android.package-archive")
            .apply { if (tok != null) header("Authorization", "Bearer $tok") }
            .build()
        downloadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty response")
            val total = body.contentLength()
            out.outputStream().use { o ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var downloaded = 0L
                    var n = input.read(buf)
                    var lastPct = -1
                    while (n >= 0) {
                        o.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = ((downloaded * 100L) / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                        n = input.read(buf)
                    }
                }
            }
        }
        out
    }

    companion object {
        const val API = "https://api.github.com/repos/mascwa200-beep/Thing"
        const val TAG = "latest"
        private val BUILD_NUM = Regex("#(\\d+)")
    }
}
