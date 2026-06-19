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

/** The result of an update check: the latest published version name (null = unknown/unparseable), and
 *  the installable update when it's newer than this build ([available] = null means already current). */
data class UpdateCheck(
    val latestVersionName: String?,
    val available: UpdateInfo?,
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
    private data class GhAsset(
        val name: String = "",
        val url: String = "",
        val browser_download_url: String = "",
        val created_at: String = "",
    )

    @Serializable
    private data class RunList(val workflow_runs: List<WorkflowRun> = emptyList())

    @Serializable
    private data class WorkflowRun(val run_number: Int = 0, val status: String = "", val conclusion: String? = null)

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** The configured GitHub token, or null when unset. Trimmed — pasted tokens often carry a trailing
     *  space/newline, which would corrupt the `Bearer` header and 401 the request. */
    suspend fun token(): String? =
        runCatching { settings.current().jarvis.githubToken }.getOrNull()?.trim()?.ifBlank { null }

    /**
     * Checks the `latest` release and reports the latest published version + an [UpdateInfo] when it's
     * newer than this build. **Throws** on a network/HTTP failure (e.g. 404 for a private repo with no
     * token) so the caller can distinguish "up to date" from "couldn't reach the server".
     */
    suspend fun check(): UpdateCheck {
        // An update check must always see the LIVE release, never a cached one. GitHub serves
        // authenticated API responses with `Cache-Control: max-age=60` and OkHttp honours it, so without
        // this a freshly-published build is missed and we wrongly report "you're on the latest build".
        val headers = buildMap {
            token()?.let { put("Authorization", "Bearer $it") }
            put("Cache-Control", "no-cache")
        }
        // The release is a prerelease, so /releases/latest won't return it — fetch it by its tag.
        val rel = http.getJson("$API/releases/tags/$TAG", GhRelease.serializer(), headers)
        val code = BUILD_NUM.find(rel.name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: BUILD_NUM.find(rel.body)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return UpdateCheck(null, null)
        val latestName = "1.0.$code"
        if (code <= BuildConfig.VERSION_CODE) return UpdateCheck(latestName, null)
        // Only offer the update once the CI run that produced it is GREEN — never while it's still
        // building (orange) or if it failed. The rolling `latest` release picks up the new APK + run
        // number mid-workflow, so without this gate we'd offer a build that isn't finished/verified.
        // Best-effort: false = confirmed not-green (suppress); true/null (green, or run too old / API
        // unavailable) falls through so old or flaky cases never hide a real update.
        if (isBuildGreen(code, headers) == false) return UpdateCheck(latestName, null)
        // Pick the NEWEST .apk — the rolling release can briefly hold a stale asset (e.g. after the
        // published filename changed), and grabbing the first one would serve an old/downgrade build.
        // created_at is ISO-8601, so lexical max == most recent.
        val asset = rel.assets
            .filter { it.name.endsWith(".apk", true) || it.browser_download_url.endsWith(".apk", true) }
            .maxByOrNull { it.created_at }
            ?: return UpdateCheck(latestName, null)
        return UpdateCheck(
            latestName,
            UpdateInfo(code, latestName, rel.body.ifBlank { rel.name }, asset.browser_download_url, asset.url),
        )
    }

    /**
     * Whether the workflow run that produced build [code] finished green. The shipped build's
     * `versionCode == github.run_number`, so the run with `run_number == code` is the one that built
     * this release. Returns **true** = completed successfully, **false** = present but not success
     * (queued / in-progress / failed / cancelled), **null** = couldn't determine (run too old to be in
     * the recent page, or the API call failed) — the caller treats null as "don't suppress".
     */
    private suspend fun isBuildGreen(code: Int, headers: Map<String, String>): Boolean? {
        val runs = runCatching {
            http.getJson("$API/actions/runs?per_page=30", RunList.serializer(), headers)
        }.getOrNull() ?: return null
        val run = runs.workflow_runs.firstOrNull { it.run_number == code } ?: return null
        return run.status == "completed" && run.conclusion == "success"
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
