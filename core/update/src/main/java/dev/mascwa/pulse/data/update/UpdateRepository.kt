package dev.mascwa.pulse.data.update

import android.content.Context
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.UpdatePolicy
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

/** The result of an update check: the latest published version name (null = unknown/unparseable), the
 *  installable update when it's newer than this build, and [pending] = a newer build exists but isn't
 *  offerable yet (still building / failed), so the UI must NOT claim you're already current. */
data class UpdateCheck(
    val latestVersionName: String?,
    val available: UpdateInfo?,
    val pending: Boolean = false,
)

/**
 * Checks a rolling GitHub release (published by CI on every green build, versioned by the run number)
 * and downloads the APK. The repo is private, so a GitHub token is sent on the API call and the asset
 * download; public repos work token-free too. Installing is [ApkInstaller]'s job.
 *
 * ## Why this is parameterised rather than copied
 *
 * ⚠️ **Three applications now read releases from this one repository** — LCARS from `latest`, the
 * standalone nutrition app from `nutrition-latest`, and LCARS again when it fetches the nutrition
 * APK so the companion can be installed in the first place. What differs between them is four
 * facts: which tag, which workflow's run numbers to trust, what build is already installed, and
 * where the token lives. Everything else — the green gate, the newest-asset pick, the private-repo
 * download dance — is identical, and a second copy of it would be a second chance to offer a build
 * that is still compiling.
 *
 * @param tag the rolling release tag, e.g. `latest`.
 * @param workflow the workflow file whose `run_number` equals the shipped `versionCode`. ⚠️ Scoped
 *   deliberately: `run_number` is per-workflow, so asking about all runs would let a different
 *   workflow carrying the same number answer, and give a confident wrong verdict.
 * @param currentVersionCode the build already installed. ⚠️ **Zero means "nothing is installed"**,
 *   which is exactly right for fetching a companion app: every published build is newer than
 *   nothing, so the newest one is always offered.
 * @param currentVersionName what to show for the installed build.
 * @param token the GitHub token, read fresh on every call rather than captured — a token pasted
 *   after this object was constructed has to work without restarting the app.
 */
class UpdateRepository(
    context: Context,
    private val http: HttpClient,
    private val tag: String,
    private val workflow: String,
    // ⚠️ **PUBLIC, and they have to be.** These two are what a screen shows as "you are on
    // build N" — `SettingsViewModel.installedVersion` reads one of them directly. Writing them
    // `private val` when this constructor was parameterised compiled fine here and broke `:app`,
    // because promoting a public property to a constructor parameter silently changes its
    // visibility if the modifier comes along for the ride. Checking that a member survived a move
    // is not the same as checking it is still reachable.
    val currentVersionCode: Int,
    val currentVersionName: String,
    private val token: suspend () -> String?,
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

    /** The configured GitHub token, or null when unset. Trimmed — pasted tokens often carry a trailing
     *  space/newline, which would corrupt the `Bearer` header and 401 the request. A failure to read
     *  it at all is the same answer as not having one: the check then runs unauthenticated and the
     *  private repo answers 404, which the caller already has to handle. */
    suspend fun token(): String? =
        runCatching { token.invoke() }.getOrNull()?.trim()?.ifBlank { null }

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
        val rel = http.getJson("$API/releases/tags/$tag", GhRelease.serializer(), headers)
        val code = UpdatePolicy.buildNumberOf(rel.name, rel.body) ?: return UpdateCheck(null, null)
        val latestName = UpdatePolicy.versionName(code)
        if (code <= currentVersionCode) return UpdateCheck(latestName, null)
        // A newer build exists. Only offer it once the CI run that produced it is GREEN — never while it's
        // still building (orange) or if it failed. The rolling `latest` release picks up the new APK + run
        // number mid-workflow, so without this gate we'd offer a build that isn't finished/verified.
        // false = still building / failed → it's PENDING (not "you're current"); true/null (green, or
        // cancelled-after-publish / run too old / API down) falls through so a real, built APK is offered.
        if (isBuildGreen(code, headers) == false) return UpdateCheck(latestName, null, pending = true)
        // Pick the NEWEST .apk — the rolling release can briefly hold a stale asset (e.g. after the
        // published filename changed), and grabbing the first one would serve an old/downgrade build.
        // created_at is ISO-8601, so lexical max == most recent.
        val asset = UpdatePolicy.newestAsset(rel.assets, { it.created_at }) {
            it.name.endsWith(".apk", true) || it.browser_download_url.endsWith(".apk", true)
        } ?: return UpdateCheck(latestName, null, pending = true)
        return UpdateCheck(
            latestName,
            UpdateInfo(code, latestName, rel.body.ifBlank { rel.name }, asset.browser_download_url, asset.url),
        )
    }

    /**
     * Whether the workflow run that produced build [code] is offerable. The shipped build's
     * `versionCode == github.run_number`, so the run with `run_number == code` is the one that built this
     * release.
     *
     * The three-state meaning of the answer is [UpdatePolicy.runVerdict]'s, and is documented there
     * rather than restated here — two copies of that reasoning is exactly how the phone and the desktop
     * would come to disagree about when a build is safe to offer. `null` additionally covers the cases
     * this method owns: a run too old to appear in the recent page, or the API call failing.
     */
    private suspend fun isBuildGreen(code: Int, headers: Map<String, String>): Boolean? {
        // Scope to the build workflow: `run_number` is per-workflow, so querying all runs would let a
        // future second workflow with the same number be matched first and give a wrong verdict.
        val runs = runCatching {
            http.getJson("$API/actions/workflows/$workflow/runs?per_page=20", RunList.serializer(), headers)
        }.getOrNull() ?: return null
        val run = runs.workflow_runs.firstOrNull { it.run_number == code } ?: return null
        return UpdatePolicy.runVerdict(run.status, run.conclusion)
    }

    /** Stream the APK to cache, reporting 0..100 progress. Private repos: fetch the asset API URL with the
     *  token + octet-stream Accept (GitHub redirects to the signed blob); public: the browser URL. */
    suspend fun download(info: UpdateInfo, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "apk").apply { mkdirs() }
        pruneCache(appContext)
        // ⚠️ Named after the tag, because LCARS now runs TWO of these — its own update and the
        // companion app's. One shared `update.apk` would have the second download overwrite the
        // first mid-install, and the failure would read as a corrupt APK rather than a collision.
        val out = File(dir, "$tag.apk")
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
        /**
         * Delete downloaded APKs nothing is waiting on any more.
         *
         * ⚠️ **Nothing deleted these, and between the two applications that is half a gigabyte held
         * for nothing.** The LCARS APK is 329 MB and the companion is 180; both are streamed into
         * `cacheDir/apk` and then handed to `PackageInstaller`, which takes its own copy into the
         * session — so the file is redundant the moment the write into that session finishes. It
         * could not be deleted at the obvious place: `commit()` usually kills this process on a
         * successful self-update, so any line written after it may simply never run. Sweeping on
         * the next launch is the only point that is reliably reached, and after a successful
         * install there always IS a next launch.
         *
         * ⚠️ **By AGE, and not by cross-referencing a pending install.** A file is named after its
         * release tag rather than its build number, so "is this the one still waiting to be
         * installed" cannot be answered from the name; and a marker that could be consulted is
         * exactly the state a killed process loses. A day is far longer than the gap between
         * downloading and installing, and anything older has either landed or been abandoned —
         * either way the newest build is one fetch away. This cannot delete a file that is about
         * to be used.
         *
         * ⚠️ **On the companion object, so a caller does not have to build a repository to sweep.**
         * The directory is per-application and shared by every tag that app downloads, so ONE call
         * at launch clears both LCARS's own APK and the companion's. Constructing an
         * [UpdateRepository] to reach an instance method would build an `OkHttpClient` on the
         * startup path for the sake of deleting files.
         *
         * `cacheDir` means Android MAY reclaim these on its own, but only under pressure and
         * possibly in the seconds between the download and the install, which is the one moment it
         * would hurt. This gives the space back on a schedule instead of hoping.
         */
        fun pruneCache(context: Context, nowMs: Long = System.currentTimeMillis()) {
            runCatching {
                val dir = File(context.applicationContext.cacheDir, "apk")
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".apk", true) && nowMs - f.lastModified() > STALE_APK_MS) {
                        runCatching { f.delete() }
                    }
                }
            }
        }

        /** How long a downloaded APK may sit before it is assumed spent. See [pruneCache]. */
        const val STALE_APK_MS = 24L * 60 * 60 * 1000

        const val API = "https://api.github.com/repos/mascwa200-beep/Thing"

        /** The LCARS application's own rolling release and the workflow that publishes it. */
        const val LCARS_TAG = "latest"
        const val LCARS_WORKFLOW = "android-build.yml"

        /**
         * The standalone nutrition app's own rolling release.
         *
         * ⚠️ **Its own tag, never `latest`.** `softprops/action-gh-release` rewrites the release
         * NAME, and the name is where every updater here reads its build number — three publishers
         * sharing one tag would each clobber the others' version, and each app would then read
         * somebody else's build number as its own.
         */
        const val NUTRITION_TAG = "nutrition-latest"
        const val NUTRITION_WORKFLOW = "nutrition-build.yml"

        /** The standalone app's applicationId, which the release build carries with no suffix. */
        const val NUTRITION_PACKAGE = "dev.mascwa.nutrition"

        /**
         * The LCARS application's applicationId.
         *
         * ⚠️ **`.debug` is part of it, and leaving the suffix off would be a package that does not
         * exist.** That module's release build carries `applicationIdSuffix = ".debug"` on purpose —
         * it keeps the identity and signing key of the previously-installed build so a sideload
         * updates in place — so the shipped package really is this. The nutrition module's release
         * build applies no suffix, which is why the two constants here look inconsistent and are
         * each correct.
         *
         * Named in this shared module because both applications need it and neither owns it: LCARS
         * to state which package it is installing on the companion's behalf, and the companion to
         * recognise LCARS as the thing that installed it.
         */
        const val LCARS_PACKAGE = "dev.mascwa.pulse.debug"
    }
}
