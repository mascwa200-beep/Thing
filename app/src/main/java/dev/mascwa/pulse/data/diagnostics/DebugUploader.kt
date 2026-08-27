package dev.mascwa.pulse.data.diagnostics

import android.content.Context
import android.os.Build
import android.os.Process
import dev.mascwa.pulse.BuildConfig
import dev.mascwa.pulse.core.device.DeviceGate
import dev.mascwa.pulse.core.device.GrapheneOs
import dev.mascwa.pulse.core.util.SecretScrub
import dev.mascwa.pulse.crash.Breadcrumbs
import dev.mascwa.pulse.crash.CrashReporter
import dev.mascwa.pulse.crash.LogcatFilter
import dev.mascwa.pulse.data.selfcode.GitHubRepo
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.settings.allSecretValues
import dev.mascwa.pulse.data.usage.UsageRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pushes scrubbed debug/diagnostic reports to a dedicated `debug-reports` branch in the repo so they can
 * be read remotely (real-time error reading), reusing the existing repo-scoped GitHub token +
 * [GitHubRepo.putFile]. The branch is created from `main` on first use; reports never touch `main` / the
 * dev branch and never open a PR, so this can't interfere with the human-gated self-coding flow.
 *
 * Privacy (load-bearing): EVERYTHING uploaded is run through [SecretScrub] with the live credential
 * values supplied, so the GitHub token / cloud key / model token can never ride along — even buried in a
 * stack trace or a logcat line. Auto-upload is opt-in ([JarvisSettings.debugReports], default on) and a
 * no-op when no token is set; calls never throw to the caller.
 */
class DebugUploader(
    context: Context,
    private val repo: GitHubRepo,
    private val crashReporter: CrashReporter,
    private val usage: UsageRepository,
    private val settings: SettingsRepository,
    private val audit: dev.mascwa.pulse.data.blackbox.AuditLedgerStore,
) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "diagnostics").apply { runCatching { mkdirs() } }
    private val uploadedMarker = File(dir, ".uploaded")

    sealed interface Result {
        data class Ok(val path: String) : Result
        data class Skipped(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    /** Upload any crash reports not yet sent (called on app launch — never at crash time, when the JVM
     *  is unstable and the network unreliable). Honours the opt-in toggle. Caps a backlog burst. */
    suspend fun uploadPendingCrashes(): Result = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext Result.Skipped("auto-reporting off")
        if (!hasToken()) return@withContext Result.Skipped("no GitHub token")
        val sent = readSent()
        val pending = crashReporter.entries().filter { it.timeMs.toString() !in sent }
        if (pending.isEmpty()) return@withContext Result.Skipped("nothing new")
        var last: Result = Result.Skipped("nothing new")
        for (e in pending.take(5)) {
            val bundle = buildBundle("crash", crashReporter.read(e))
            last = upload("crash", bundle)
            if (last is Result.Ok) markSent(e.timeMs.toString())
        }
        last
    }

    /** Manually send a report now (the latest crash, if any, plus current diagnostics). Ignores the
     *  opt-in toggle — it's an explicit user action — but still requires a token. */
    suspend fun sendNow(): Result = withContext(Dispatchers.IO) {
        if (!hasToken()) return@withContext Result.Failed("Set a GitHub token in Computer Setup first.")
        val latestCrash = crashReporter.entries().firstOrNull()?.let { crashReporter.read(it) }
        upload("manual", buildBundle("manual", latestCrash))
    }

    // --- internals -------------------------------------------------------------------------------

    private suspend fun enabled(): Boolean =
        runCatching { settings.current().jarvis.debugReports }.getOrDefault(true)

    private suspend fun hasToken(): Boolean = repo.token() != null

    /** Live credential values to redact by exact match — every secret the app actually holds, read from
     *  the authoritative [allSecretValues] accessor (apiKeys + jarvis tokens + Spotify OAuth), so a
     *  newly-added secret is covered by construction rather than hand-enumerated here. The exact-match pass
     *  is what catches opaque, pattern-evading tokens (a Spotify `BQ…` blob, a hyphenated `sk-or-v1-…` key). */
    private suspend fun liveSecrets(): List<String> = runCatching {
        settings.current().allSecretValues().filter { it.length >= 8 }
    }.getOrDefault(emptyList())

    private suspend fun buildBundle(kind: String, crashText: String?): String {
        val now = System.currentTimeMillis()
        val activity = runCatching { usage.recentActivity(60) }.getOrDefault(emptyList())
        val graphene = runCatching { GrapheneOs.detect(appContext) }.getOrNull()
        val gate = runCatching { DeviceGate.evaluate() }.getOrNull()
        val raw = buildString {
            append("# LCARS debug report — ").append(kind).append("\n\n")
            append("- when: ").append(TS.format(Date(now))).append('\n')
            append("- build: ").append(BuildConfig.VERSION_NAME).append(" (#").append(BuildConfig.VERSION_CODE).append(")\n")
            append("- device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("- os: ").append(graphene?.summary ?: "unknown")
                .append(" · hardware ").append(if (gate?.isMatch == true) "match ✓" else (gate?.detectedModel ?: "unknown")).append("\n\n")
            if (crashText != null) {
                append("## Latest fault\n\n```\n").append(crashText.take(20_000)).append("\n```\n\n")
            }
            // ⚠️ **This section did not exist, and the trail it renders was reaching nobody.**
            // `Breadcrumbs` is shared with the standalone application and this bundle never read it,
            // so a LCARS report arrived with the fault and no account of what led to it. The two
            // sections below are not the same thing: the activity log is durable, aggregated and
            // written to disk on its own schedule; this is the last couple of hundred moments held
            // in memory, which is the resolution that matters for the seconds before a crash — and
            // the only one that survives to be copied into the report as the process dies.
            append("## What was happening just now\n\n```\n")
                .append(Breadcrumbs.render(now)).append("```\n\n")
            append("## Recent activity (last ").append(activity.size).append(")\n\n```\n")
            activity.forEach {
                append(TS.format(Date(it.epochMs))).append("  ").append(it.category).append("  ").append(it.label).append('\n')
            }
            append("```\n\n")
            // ⚠️ **Filtered rather than tailed, and the heading no longer claims something untrue.**
            // Counted over the three reports actually sent from two phones, the raw tail was 254
            // lines of which 149 were keyboard-and-window chatter and NOT ONE came from this
            // application's own code; and "this process" was false, because logcat's ring buffer
            // outlives a process and one report carried five different pids. See `LogcatFilter`.
            append("## Logcat\n\n")
            val log = readOwnLogcat()
            append(
                if (log == null) "_The log buffer could not be read on this device._\n"
                else LogcatFilter.report(log, Process.myPid()),
            )
        }
        return SecretScrub.scrub(raw, liveSecrets())
    }

    /**
     * Reads THIS app's own recent logcat (non-privileged apps are restricted to their own UID).
     *
     * ⚠️ Returned WHOLE, and null only when it genuinely could not be read. The budget and the
     * choosing belong to [LogcatFilter]; tailing here first would throw away the warnings and errors
     * from an earlier launch, which is the one thing in the dump that can explain a crash the fault
     * handler did not survive to record.
     */
    private fun readOwnLogcat(): String? = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "500"))
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        runCatching { proc.destroy() }
        out
    }.getOrNull()

    private suspend fun upload(kind: String, bundle: String): Result = runCatching {
        // Ensure the debug-reports branch exists (create from main on first use).
        if (runCatching { repo.headSha(BRANCH) }.getOrNull() == null) {
            runCatching { repo.createBranch(BRANCH, repo.headSha("main")) }
        }
        val path = "reports/" + System.currentTimeMillis() + "-" + kind + ".md"
        repo.putFile(path, bundle, "debug: $kind report", BRANCH, null)
        // Audit the upload in the tamper-evident ledger (the path is a branch path, not content).
        audit.record(dev.mascwa.pulse.core.telemetry.AuditEventType.DIAGNOSTIC, "debug.upload.$kind", path)
        Result.Ok(path) as Result
    }.getOrElse { Result.Failed(it.message ?: "upload failed") }

    private fun readSent(): Set<String> = runCatching {
        if (uploadedMarker.exists()) uploadedMarker.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
    }.getOrDefault(emptySet())

    private fun markSent(id: String) {
        runCatching {
            uploadedMarker.appendText(id + "\n")
            // Keep the marker bounded — CrashReporter only ever retains a handful of entries, so the last
            // MARKER_CAP ids are always enough to de-dupe; rewrite to the tail when it drifts over.
            val lines = uploadedMarker.readLines().filter { it.isNotBlank() }
            if (lines.size > MARKER_CAP) uploadedMarker.writeText(lines.takeLast(MARKER_CAP).joinToString("\n") + "\n")
        }
    }

    private companion object {
        const val BRANCH = "debug-reports"
        /** Upper bound on remembered "already uploaded" crash ids (CrashReporter keeps far fewer). */
        const val MARKER_CAP = 50
        val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    }
}
