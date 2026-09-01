package dev.mascwa.pulse.data.update

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * An application keeping ITSELF current: check, download, and install on the way out.
 *
 * ⚠️ **This is a state machine, not a screen.** The surface exists so the owner can see what
 * happened and force it, but nothing there has to be touched for an update to arrive: a check runs
 * when the app comes to the foreground, a newer build downloads, and it installs when the app is put
 * down. An app you have to remember to update is one that silently stops being updated.
 *
 * ## Why this is in the shared module rather than in each application
 *
 * ⚠️ **Three applications now update themselves from this repository's releases**, and this was
 * written the day the third one needed it. [UpdateRepository]'s own KDoc already makes the argument
 * for parameterising the network half; the half above it — what the states are, when a pending
 * install may be cleared, what a 404 means — is just as identical and just as easy to get subtly
 * wrong. A third copy of a state machine that INSTALLS SOFTWARE is not a duplication worth having,
 * and this repository has corrected that shape eight times in code with far less consequence.
 *
 * What genuinely differs between the applications is four things, and all four are constructor
 * arguments: which release to read ([repo]), where the token is kept ([saveToken]), where the
 * one-at-a-time guard is kept ([pendingInstall]/[setPendingInstall]), and whether some other
 * application on the phone already keeps this one current ([companionPackage]).
 *
 * ## What it cannot do, said once
 *
 * ⚠️ **The releases are in a private repository, so a GitHub token is required and there is no way
 * around that.** Without one the check gets a 404 and says so. A public mirror would remove the
 * requirement and is not something this file can decide.
 *
 * ⚠️ **The first install of an update may show the system's confirmation.** [ApkInstaller]'s ladder
 * needs either device-owner provisioning or the installer-of-record status that the first committed
 * session earns — so for an app that has neither yet, the honest description is "one tap the first
 * time, none after", not "silent".
 *
 * @param repo the release to read and the APK to fetch. Already carries the tag, the workflow, the
 *   installed build and the token, so none of those is restated here.
 * @param saveToken where a pasted token goes. A blank value clears it.
 * @param pendingInstall the build already committed but not yet confirmed as running — see
 *   [clearPendingIfLanded] for why it is persisted rather than held in a field.
 * @param companionPackage another application of ours that installs and maintains this one, if any.
 *   ⚠️ Null is the ordinary case and means "nothing else keeps this app current"; naming a package
 *   makes [maintainedByCompanion] a real question rather than a constant false.
 */
class SelfUpdate(
    context: Context,
    private val repo: UpdateRepository,
    private val saveToken: suspend (String) -> Unit,
    private val pendingInstall: suspend () -> Int,
    private val setPendingInstall: suspend (Int) -> Unit,
    private val companionPackage: String? = null,
) {

    private val appContext = context.applicationContext

    /** What the update surface shows. Every state says what actually happened, never just "no". */
    sealed interface State {
        /** Nothing has been asked yet this session. */
        data object Idle : State

        data object Checking : State

        /** The newest published build is the one running. */
        data class Current(val latest: String) : State

        /**
         * A newer build exists but is not offerable yet — still compiling, or its run failed.
         *
         * ⚠️ Distinct from [Current] on purpose. Telling somebody they are up to date when a newer
         * build is halfway through CI is a small lie that makes the next check look broken.
         */
        data class Pending(val latest: String) : State

        data class Available(val info: UpdateInfo) : State

        data class Downloading(val info: UpdateInfo, val percent: Int) : State

        /**
         * Downloaded and waiting to be installed when the app is next put down.
         *
         * ⚠️ Carries the [file] rather than leaving the caller to rebuild its path. The path is this
         * module's business — it is composed from the release tag inside [UpdateRepository] — and a
         * screen that reconstructed it would be a second statement of it, silently wrong the day the
         * naming changes.
         */
        data class Ready(val info: UpdateInfo, val file: File) : State

        /**
         * The check or the download failed, with what the failure actually was.
         *
         * ⚠️ A 404 here almost always means "no token, private repository" rather than "no such
         * release", so the message names that possibility rather than making the reader guess.
         */
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val installedVersion: String get() = repo.currentVersionName
    val installedCode: Int get() = repo.currentVersionCode

    /** Whether a token is set, so a surface can show that without holding it. */
    suspend fun hasToken(): Boolean = repo.token() != null

    /**
     * Whether another application of ours put this one here, and therefore keeps it current.
     *
     * ⚠️ **The installer of record, not merely "is that app installed".** Presence would be an
     * inference; this is a fact about how this copy arrived. When it is true, the token this surface
     * would otherwise insist on is not needed and asking for it again is noise.
     *
     * ⚠️ **It reports what is true, and never disables anything.** The token field stays: the
     * companion can be uninstalled, and this app's own updater is then the only route left. What
     * changes is the copy.
     *
     * ⚠️ Needs a `<queries>` entry in the consuming application's manifest naming [companionPackage].
     * Without it, package visibility filters the installing package to null and this silently answers
     * false on a phone where it is true.
     *
     * ⚠️ `suspend` with the dispatcher chosen HERE rather than at the call site.
     * `getInstallSourceInfo` is a binder call into the package manager, and a caller that forgot to
     * move it would put one on the main thread — a decision that belongs beside the work rather than
     * repeated by every reader of it.
     */
    suspend fun maintainedByCompanion(): Boolean {
        val expected = companionPackage ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val pm = appContext.packageManager
                val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    pm.getInstallSourceInfo(appContext.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(appContext.packageName)
                }
                installer == expected
            }.getOrDefault(false)
        }
    }

    /** Save a pasted token; a blank one clears it. */
    suspend fun saveToken(value: String) = saveToken.invoke(value)

    /**
     * Ask GitHub what the newest build is.
     *
     * ⚠️ Returns the [UpdateInfo] when there is one so a caller that wants to go straight on to
     * downloading does not have to read it back out of the state and race with the surface.
     */
    suspend fun check(): UpdateInfo? {
        _state.value = State.Checking
        val result = runCatching { repo.check() }
        val check = result.getOrElse { err ->
            _state.value = State.Failed(explain(err))
            return null
        }
        // ⚠️ **Hoisted to a local, and it has to be.** `UpdateCheck.available` is a `val` declared in
        // this module, but a consumer reading it from another one gets no smart cast — and this file
        // is now the only place that reads it, which is part of the point of moving it here. A local
        // val smart-casts normally. This repository has paid for that rule five times.
        val available = check.available
        val name = check.latestVersionName ?: "unknown"
        return when {
            available != null -> available.also { _state.value = State.Available(it) }
            check.pending -> { _state.value = State.Pending(name); null }
            else -> { _state.value = State.Current(name); null }
        }
    }

    /** Fetch the APK, reporting progress. Returns the file, or null when it could not be had. */
    suspend fun download(info: UpdateInfo): File? {
        // ⚠️ **Refused only when it CANNOT fit, never when it merely might not.** The LCARS build
        // carries a 4.5-million-barcode food database, a 36.9-million-star catalogue and three
        // native engines, so the APK is measured in hundreds of megabytes and an install wants
        // roughly two and a half times that transiently — the downloaded copy, the installer's own
        // staging copy, and the ahead-of-time artefacts. Guessing at that multiplier and refusing on
        // it would block installs that would have worked. What is NOT a guess is that a download
        // needs somewhere to land, so that is the only bar here, and the sentence carries the two
        // numbers rather than saying "not enough space".
        shortOfSpace(estimatedApkBytes(), "download")?.let {
            _state.value = State.Failed(it); return null
        }
        _state.value = State.Downloading(info, 0)
        return runCatching {
            repo.download(info) { pct -> _state.value = State.Downloading(info, pct) }
        }.onSuccess { file ->
            _state.value = State.Ready(info, file)
        }.onFailure { err ->
            _state.value = State.Failed(explain(err))
        }.getOrNull()
    }

    /**
     * Install a downloaded build.
     *
     * ⚠️ **The pending marker is written BEFORE the commit, and that ordering is load-bearing.**
     * Android tears this process down while its own package is replaced, so anything written after
     * `commit` may simply never run. Recording it first means a successful install always leaves the
     * marker set, and the next launch — which is the newer build — clears it by comparison.
     */
    suspend fun install(): Boolean {
        val ready = _state.value as? State.Ready ?: return false
        // ⚠️ Checked again, against the file's REAL size this time. `PackageInstaller` streams the
        // APK into a staging session of its own, so a phone with room for the download and not for
        // the copy would fail inside the platform — and a background install failure is about as
        // quiet as a failure gets. Saying so costs one `statvfs`.
        shortOfSpace(ready.file.length(), "install")?.let {
            _state.value = State.Failed(it); return false
        }
        setPendingInstall(ready.info.versionCode)
        return ApkInstaller.install(appContext, ready.file)
    }

    /**
     * How big the next APK will be, near enough: this one.
     *
     * ⚠️ Estimated from the RUNNING package rather than from the release, because the GitHub asset's
     * size is not something [UpdateInfo] carries and adding it would mean a second thing to keep in
     * step for a figure this already knows. Builds of one application do not change size by much
     * between neighbouring versions, which is all this needs to be right about.
     */
    private fun estimatedApkBytes(): Long =
        runCatching { File(appContext.applicationInfo.sourceDir).length() }.getOrDefault(0L)

    /** A sentence when [needed] plainly will not fit where downloads go, or null when it will. */
    private fun shortOfSpace(needed: Long, verb: String): String? {
        if (needed <= 0L) return null                       // nothing measured; do not invent a refusal
        val free = runCatching { appContext.cacheDir.usableSpace }.getOrDefault(Long.MAX_VALUE)
        if (free >= needed) return null
        return "Not enough room to $verb this build: it needs about ${megabytes(needed)} and this " +
            "phone has ${megabytes(free)} free. Clearing some space and checking again is all it needs."
    }

    // Mebibytes and Locale.US, matching every other size this project prints — the file manager the
    // reader would compare against uses the same unit, and a comma decimal in a number is a defect
    // this repository has corrected repeatedly.
    private fun megabytes(bytes: Long): String =
        String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)

    /** Whether there is a downloaded build sitting ready to be installed. */
    val hasDownload: Boolean get() = _state.value is State.Ready

    /**
     * Whether an automatic update may run right now.
     *
     * ⚠️ **Clearing the marker is done by comparison with the running build, not by "we got here".**
     * The evidence that an install landed is that this code is executing FROM it. A build that failed
     * to install leaves the marker above the running version, so the guard stays set and the app
     * stops trying — which is the whole point of it.
     */
    suspend fun clearPendingIfLanded(): Boolean {
        val pending = pendingInstall()
        if (pending == 0) return true
        if (repo.currentVersionCode >= pending) {
            setPendingInstall(0)
            return true
        }
        return false
    }

    private fun explain(err: Throwable): String {
        val message = err.message.orEmpty()
        return when {
            "404" in message ->
                "GitHub answered 404. The releases are in a private repository, so this needs a " +
                    "token with repo scope — or the token it has cannot see that repository."
            "401" in message || "403" in message ->
                "GitHub refused the token. Check it has not expired and carries repo scope."
            message.isBlank() -> "Could not reach GitHub (${err.javaClass.simpleName})."
            else -> message
        }
    }
}
