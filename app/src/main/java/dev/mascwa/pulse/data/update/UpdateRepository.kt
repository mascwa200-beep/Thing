package dev.mascwa.pulse.data.update

import android.content.Context
import dev.mascwa.pulse.BuildConfig
import dev.mascwa.pulse.core.network.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One available update: its build number, a display version, release notes, and the APK download URL. */
data class UpdateInfo(val versionCode: Int, val versionName: String, val notes: String, val apkUrl: String)

/**
 * Checks the project's rolling `latest` GitHub release (published by CI on every green build, versioned
 * by the run number) and downloads the APK. Pure data layer — installing is done from the UI via the
 * system installer (the user's authorization). No token needed; the release + asset are public.
 */
class UpdateRepository(context: Context, private val http: HttpClient) {

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
    private data class GhAsset(val name: String = "", val browser_download_url: String = "")

    /** The build number currently installed (CI sets versionCode = the GitHub run number). */
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /**
     * Returns an [UpdateInfo] when the `latest` release is a higher build than this one, or null when
     * we're already current / the release can't be parsed. **Throws** on a network/HTTP failure so the
     * caller can distinguish "up to date" from "couldn't reach the server".
     */
    suspend fun check(): UpdateInfo? {
        // The release is a prerelease, so /releases/latest won't return it — fetch it by its tag.
        val rel = http.getJson("$API/releases/tags/$TAG", GhRelease.serializer())
        val code = BUILD_NUM.find(rel.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: BUILD_NUM.find(rel.body)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val apk = rel.assets.firstOrNull { it.browser_download_url.endsWith(".apk", ignoreCase = true) }
            ?.browser_download_url ?: return null
        return UpdateInfo(code, "1.0.$code", rel.body.ifBlank { rel.name }, apk)
    }

    /** Stream the APK to cache, reporting 0..100 progress. Returns the file. */
    suspend fun download(url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "apk").apply { mkdirs() }
        val out = File(dir, "update.apk")
        downloadClient.newCall(Request.Builder().url(url).header("User-Agent", HttpClient.USER_AGENT).build())
            .execute().use { resp ->
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
