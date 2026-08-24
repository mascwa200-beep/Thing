package dev.mascwa.pulse.desktop.diagnostics

import dev.mascwa.pulse.core.util.SecretScrub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

/**
 * Pushes desktop fault reports to the repo's `debug-reports` branch, so a crash on this machine can
 * actually be read by whoever is fixing it.
 *
 * ## ⚠️ Why this exists — it closes a real asymmetry that cost a whole session
 *
 * The phone has had this since the GrapheneOS arc: `DebugUploader` puts scrubbed bundles on a
 * dedicated branch, and they can be read remotely. The desktop had **nothing** — [CrashReporter]
 * writes `<dataDir>/diagnostics/fault-*.txt` and stops there. So when the Windows console froze,
 * the only channel back was a photograph of the dialog, and three sessions were spent inferring
 * from a single line of text what one stack trace would have settled immediately.
 *
 * ## What is and is not sent
 *
 * Only files [CrashReporter] itself wrote: a header (time, thread, window, build) and a stack
 * trace. Everything goes through [SecretScrub] with the live GitHub token added to the exact-match
 * list, because that token is the one secret this process holds and a stack trace can carry a
 * request URL. Nothing else on the machine is read — no logs, no settings, no ledger, no library.
 *
 * ⚠️ **A report is uploaded at most once.** The sent ids live in `diagnostics/sent.txt` beside the
 * reports, so re-running does not re-push, and clearing the console clears that too. Without it a
 * fault that reproduces every frame would push on every launch forever.
 *
 * ## The branch, and why it is that branch
 *
 * `debug-reports` is deliberately the same branch the phone uses: it is already excluded from CI
 * (`android-build.yml` skips it), so a report cannot trigger a build or advance the version. It is
 * created from `main` on first use. Reports never touch `main` or a dev branch and never open a
 * pull request.
 */
class CrashUploader(
    private val reporter: CrashReporter,
    dataDir: File,
    private val token: suspend () -> String,
    private val buildLabel: () -> String,
) {

    private val sentFile = File(File(dataDir, "diagnostics"), "sent.txt")

    /** What happened, in a sentence, for a person to read. Never throws. */
    suspend fun send(): String = withContext(Dispatchers.IO) {
        val key = runCatching { token() }.getOrNull()?.trim().orEmpty()
        if (key.isEmpty()) return@withContext "No GitHub token — add one in Settings and try again."

        val pending = reporter.entries().filter { it.file.name !in readSent() }
        if (pending.isEmpty()) return@withContext "Nothing new to send; every report here has already gone up."

        val branchReady = runCatching { ensureBranch(key) }.getOrDefault(false)
        if (!branchReady) return@withContext "Could not reach the repository. Check the token and the connection."

        var ok = 0
        var failed = 0
        for (entry in pending) {
            val body = runCatching { reporter.read(entry) }.getOrDefault("")
            if (body.isBlank()) continue
            // ⚠️ Scrub BEFORE anything leaves the process, with the live token as an exact match —
            // it is the one secret here, and a trace can carry a request URL that embeds it.
            val safe = SecretScrub.scrub(body, listOf(key))
            val path = "desktop/${buildLabel().replace(Regex("[^A-Za-z0-9._-]"), "-")}/${entry.file.name}"
            if (runCatching { putFile(key, path, safe) }.getOrDefault(false)) {
                markSent(entry.file.name)
                ok++
            } else {
                failed++
            }
        }
        when {
            ok > 0 && failed == 0 -> "Sent $ok report${if (ok == 1) "" else "s"} to the debug-reports branch."
            ok > 0 -> "Sent $ok, but $failed could not be uploaded."
            else -> "Nothing was uploaded — the repository refused the write."
        }
    }

    /**
     * Best-effort, silent, for startup. A launch must never be slowed or interrupted by this, and a
     * machine with no token or no network is the ordinary case rather than a fault.
     */
    suspend fun sendQuietly() {
        runCatching { send() }
    }

    /** Called when the console is cleared, so the same ids can be sent again if they recur. */
    fun forgetSent() {
        runCatching { sentFile.delete() }
    }

    private fun readSent(): Set<String> =
        runCatching { sentFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
            .getOrDefault(emptySet())

    private fun markSent(name: String) {
        runCatching { sentFile.appendText(name + "\n") }
    }

    /** True when `debug-reports` exists, creating it from `main` if it does not. */
    private fun ensureBranch(key: String): Boolean {
        val existing = request("GET", "$API/git/ref/heads/$BRANCH", key, null)
        if (existing.first in 200..299) return true
        if (existing.first != 404) return false
        val main = request("GET", "$API/git/ref/heads/main", key, null)
        if (main.first !in 200..299) return false
        val sha = Regex("\"sha\"\\s*:\\s*\"([0-9a-f]{40})\"").find(main.second)?.groupValues?.get(1)
            ?: return false
        val made = request(
            "POST", "$API/git/refs", key,
            """{"ref":"refs/heads/$BRANCH","sha":"$sha"}""",
        )
        return made.first in 200..299
    }

    private fun putFile(key: String, path: String, content: String): Boolean {
        val b64 = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
        // ⚠️ Existing-file SHA is deliberately not fetched: every report has a unique timestamped
        // name, so a collision would mean the same fault file being uploaded twice, which `sent.txt`
        // already prevents. A 422 here is a real signal, not something to paper over with a retry.
        val body = """{"message":${json(COMMIT)},"content":"$b64","branch":"$BRANCH"}"""
        val out = request("PUT", "$API/contents/$path", key, body)
        return out.first in 200..299
    }

    private fun request(method: String, url: String, key: String, body: String?): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")
            code to text
        } catch (_: Throwable) {
            // A machine with no network is the ordinary case, not a fault worth reporting about a
            // fault. -1 is distinct from every real HTTP status, so `ensureBranch` will not mistake
            // it for a 404 and try to create a branch it could not see.
            -1 to ""
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Minimal JSON string escape — the only interpolated value is a fixed commit message. */
    private fun json(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private companion object {
        const val API = "https://api.github.com/repos/mascwa200-beep/Thing"
        const val BRANCH = "debug-reports"
        const val COMMIT = "debug: desktop fault report"
        const val TIMEOUT_MS = 15_000
    }
}
