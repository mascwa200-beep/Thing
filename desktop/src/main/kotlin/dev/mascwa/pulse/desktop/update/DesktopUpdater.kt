package dev.mascwa.pulse.desktop.update

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.core.telemetry.UpdatePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files

/** A build that is published, finished, and newer than this one. */
data class DesktopUpdate(
    val build: Int,
    val versionName: String,
    val notes: String,
    /** The asset API URL — a private repo's installer is not reachable by its browser URL without auth. */
    val assetApiUrl: String,
)

/** What a check concluded, in the terms the ABOUT screen shows. */
data class DesktopUpdateCheck(
    val verdict: UpdatePolicy.Verdict,
    val latestVersionName: String?,
    val update: DesktopUpdate?,
    val error: String? = null,
)

/**
 * Keeps the desktop app up to date from the same place it is built.
 *
 * The Windows companion had no update path at all: CI produced an MSI and attached it to the workflow
 * run as an artifact, so getting a new build meant knowing to go to GitHub Actions and dig one out. This
 * reads the release CI now publishes and offers what it finds.
 *
 * Every judgement about *whether* to offer a build lives in [UpdatePolicy], shared with the phone — the
 * tri-state green gate especially, which has an edge (a run cancelled after it already published) that
 * cost real debugging to get right once and should not be rediscovered here.
 */
class DesktopUpdater(
    private val http: HttpClient,
    private val settings: DesktopSettingsStore,
) {

    @Serializable
    private data class GhAsset(
        val name: String = "",
        val url: String = "",
        val browser_download_url: String = "",
        val created_at: String = "",
    )

    @Serializable
    private data class GhRelease(
        val name: String = "",
        val body: String = "",
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class WorkflowRun(val run_number: Int = 0, val status: String = "", val conclusion: String? = null)

    @Serializable
    private data class RunList(val workflow_runs: List<WorkflowRun> = emptyList())

    private suspend fun headers(): Map<String, String> = buildMap {
        val token = runCatching { settings.current().githubToken }.getOrNull()?.trim().orEmpty()
        if (token.isNotEmpty()) put("Authorization", "Bearer $token")
        // The phone learned this the hard way: GitHub serves authenticated API responses with
        // max-age=60 and a caching client will happily answer an update check from them, so a build
        // published a moment ago is missed and the app reports itself current.
        put("Cache-Control", "no-cache")
        put("Accept", "application/vnd.github+json")
    }

    /** One request for the release, plus one for the run that produced it. Never installs anything. */
    suspend fun check(): DesktopUpdateCheck {
        val h = headers()
        val rel = runCatching { http.getJson("$API/releases/tags/$TAG", GhRelease.serializer(), h) }
            .getOrElse { e ->
                return DesktopUpdateCheck(
                    UpdatePolicy.Verdict.UNKNOWN, null, null,
                    error = describe(e),
                )
            }

        val build = UpdatePolicy.buildNumberOf(rel.name, rel.body)
        val latestName = build?.let { UpdatePolicy.versionName(it) }
        val asset = UpdatePolicy.newestAsset(rel.assets, { it.created_at }) {
            it.name.endsWith(".msi", true) || it.browser_download_url.endsWith(".msi", true)
        }
        val green = build?.let { runState(it, h) }
        val verdict = UpdatePolicy.verdict(
            installedBuild = BuildInfo.build,
            releaseBuild = build,
            green = green,
            hasInstaller = asset != null,
        )
        val update = if (verdict == UpdatePolicy.Verdict.AVAILABLE && build != null && asset != null) {
            DesktopUpdate(build, latestName.orEmpty(), rel.body.ifBlank { rel.name }, asset.url)
        } else {
            null
        }
        return DesktopUpdateCheck(verdict, latestName, update)
    }

    /**
     * Whether the run that produced [build] blocks it. Scoped to the DESKTOP workflow: `run_number` is
     * per-workflow, so asking across all of them would match the Android build's counter and answer about
     * an unrelated run. Meaning of the three states is [UpdatePolicy.runVerdict]'s.
     */
    private suspend fun runState(build: Int, h: Map<String, String>): Boolean? {
        val runs = runCatching {
            http.getJson("$API/actions/workflows/$WORKFLOW/runs?per_page=20", RunList.serializer(), h)
        }.getOrNull() ?: return null
        val run = runs.workflow_runs.firstOrNull { it.run_number == build } ?: return null
        return UpdatePolicy.runVerdict(run.status, run.conclusion)
    }

    /**
     * Fetch the installer to a temporary directory.
     *
     * The asset API URL with an octet-stream Accept is what works for a private repo — GitHub answers it
     * with a redirect to a signed blob. The browser URL would return an HTML sign-in page, which would
     * download "successfully" and then fail as an installer.
     */
    suspend fun download(update: DesktopUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = Files.createTempDirectory("lcars-update").toFile()
        val dest = File(dir, "LCARS-${update.versionName}.msi")
        http.download(
            url = update.assetApiUrl,
            dest = dest,
            maxBytes = MAX_INSTALLER_BYTES,
            headers = headers() + mapOf("Accept" to "application/octet-stream"),
            onProgress = onProgress,
        )
        dest
    }

    /**
     * Hand the installer to Windows and expect no questions.
     *
     * ⚠️ **`/qn` is only possible because the MSI is a PER-USER install** — see `perUserInstall` in
     * `desktop/build.gradle.kts`. A per-machine MSI writes under Program Files and therefore needs
     * elevation, and `/qn` cannot suppress a UAC prompt; it can only fail behind one. Installing
     * under `%LOCALAPPDATA%` needs no elevation, so the upgrade genuinely runs with no window and no
     * click. That change costs the owner one manual uninstall, once, because a per-user MSI is a
     * different install context and will not upgrade a per-machine copy — it would sit beside it.
     *
     * ⚠️ **Detached, because this process is about to die.** The MSI replaces files the JVM holds
     * open, so it cannot run over a live app; `cmd /c start` hands msiexec to the shell and lets it
     * outlive us. The caller quits immediately afterwards.
     *
     * Returns false if nothing could be started, so the caller can say so rather than quitting into
     * nothing.
     */
    fun launchInstaller(installer: File, silent: Boolean = true): Boolean {
        val path = installer.absolutePath
        if (silent && runCatching {
                // `start` needs an empty title argument first, or it reads a quoted path as the title.
                ProcessBuilder("cmd", "/c", "start", "", "msiexec", "/i", path, "/qn", "/norestart")
                    .redirectErrorStream(true)
                    .start()
                true
            }.getOrDefault(false)
        ) {
            return true
        }
        // Fallback: the ordinary interactive install. A broken unattended path should degrade to the
        // behaviour this replaced, not to nothing.
        return runCatching {
            ProcessBuilder("msiexec", "/i", path).redirectErrorStream(true).start()
            true
        }.getOrElse {
            // Not Windows, or msiexec is not on the path. Opening the file lets the desktop
            // environment decide, which at worst shows it in a file manager rather than doing nothing.
            runCatching {
                java.awt.Desktop.getDesktop().open(installer)
                true
            }.getOrDefault(false)
        }
    }

    private fun describe(e: Throwable): String = when {
        e.message?.contains("401") == true || e.message?.contains("404") == true ->
            "Could not read the release. A private repository needs a GitHub token — add one below."
        else -> e.message ?: "The update check failed."
    }

    private companion object {
        const val API = "https://api.github.com/repos/mascwa200-beep/Thing"

        /** The desktop's OWN rolling release. Sharing the phone's `latest` tag would have each build's
         *  publish overwrite the other's release name, and the name is where the build number lives. */
        const val TAG = "desktop-latest"

        /** The workflow whose run_number is the shipped build number. */
        const val WORKFLOW = "desktop-build.yml"

        /** The installer is around 180 MB with the bundled library; this is a sanity ceiling, not a target. */
        const val MAX_INSTALLER_BYTES = 600L * 1024 * 1024
    }
}
