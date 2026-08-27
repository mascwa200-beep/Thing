package dev.mascwa.pulse.crash

import android.content.Context
import android.os.Build
import android.util.Base64
import dev.mascwa.pulse.core.util.SecretScrub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sends recorded faults to the repository, so a failure on the phone can be read off it.
 *
 * Reports land on a dedicated **`debug-reports`** branch, created from `main` the first time one is
 * needed. Nothing here touches `main`, touches a development branch, or opens a pull request, so it
 * cannot interfere with anything else the repository is doing — and CI ignores that branch, so a
 * report does not trigger a build or move the version number.
 *
 * ## The rules that make this safe to switch on
 *
 * ⚠️ **Everything goes through [SecretScrub] with the live credential values supplied by the
 * caller.** A GitHub token can appear in a stack trace, in a logcat line, in an exception message
 * from a failed request — and a token is transferable harm in a way a stack trace is not. The
 * exact-value pass is what catches the opaque, pattern-evading ones.
 *
 * ⚠️ **Uploads happen at launch, never at fault time.** While a crash is being handled the JVM is
 * unstable and the process is about to be killed; a network call there would usually not finish and
 * might stop the report being written at all.
 *
 * ⚠️ **Two applications, one branch, different paths.** LCARS writes `reports/lcars/…` and the
 * standalone nutrition app writes `reports/nutrition/…`, so both streams are readable and there is
 * never a question of which phone or which app a report came from.
 *
 * ⚠️ **It never throws to the caller and it says why it did nothing.** "No token", "auto-send off"
 * and "nothing new" are different facts, and a screen that shows one message for all three teaches
 * its reader to ignore it.
 *
 * @param reporter where the faults are.
 * @param stream the path segment naming this application — `lcars`, `nutrition`.
 * @param appLabel what to call it in the report title.
 * @param buildLabel the running build, e.g. `1.0.42 (#42)`.
 * @param token read fresh on every call, not captured — a token pasted after this object was built
 *   has to work without restarting the app.
 * @param autoSendEnabled the opt-in switch, consulted only by [uploadPending]; [sendNow] is an
 *   explicit action and ignores it.
 * @param secrets live credential values to redact by exact match, on top of the shape patterns.
 */
class CrashUploader(
    context: Context,
    private val reporter: CrashReporter,
    private val stream: String,
    private val appLabel: String,
    private val buildLabel: String,
    private val token: suspend () -> String?,
    private val autoSendEnabled: suspend () -> Boolean = { true },
    private val secrets: suspend () -> List<String> = { emptyList() },
) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "diagnostics").apply { runCatching { mkdirs() } }
    private val marker = File(dir, ".uploaded")

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Ok(val path: String) : Result

        /** Nothing was sent, and this is why — a state, not a failure. */
        data class Skipped(val reason: String) : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Send everything recorded since the last successful upload.
     *
     * ⚠️ Capped at [BURST] per call. A phone that crash-looped overnight has a backlog, and the
     * fifth copy of one fault adds nothing that the first four did not — while a hundred pushes in a
     * row would be rate-limited into failing anyway.
     */
    suspend fun uploadPending(): Result = withContext(Dispatchers.IO) {
        if (!autoSendEnabled()) return@withContext Result.Skipped("auto-send is off")
        if (token() == null) return@withContext Result.Skipped("no GitHub token")
        val sent = readSent()
        val pending = reporter.entries().filter { it.timeMs.toString() !in sent }
        if (pending.isEmpty()) return@withContext Result.Skipped("nothing new")
        var last: Result = Result.Skipped("nothing new")
        // ⚠️ Oldest first, so a truncated burst leaves the NEWEST faults pending rather than sending
        // them and stranding the older ones for ever. `entries()` is newest-first for the screen.
        for (entry in pending.sortedBy { it.timeMs }.take(BURST)) {
            val kind = if (entry.kind == FaultKind.FATAL) "crash" else "fault"
            last = upload(kind, bundle(kind, reporter.read(entry)))
            if (last is Result.Ok) markSent(entry.timeMs.toString()) else break
        }
        last
    }

    /**
     * Send a report now because somebody asked.
     *
     * ⚠️ Ignores the auto-send switch — it is an explicit action, and a button that silently does
     * nothing because of a setting elsewhere is worse than no button. It still needs a token, and
     * says so plainly rather than failing quietly.
     */
    suspend fun sendNow(note: String? = null): Result = withContext(Dispatchers.IO) {
        if (token() == null) {
            return@withContext Result.Failed("Set a GitHub token first — the repository is private.")
        }
        val latest = reporter.entries().firstOrNull()?.let { reporter.read(it) }
        upload("manual", bundle("manual", latest, note))
    }

    // --- internals -------------------------------------------------------------------------------

    /**
     * What gets sent.
     *
     * ⚠️ The fault file already carries the header, the breadcrumbs and the trace — this adds only
     * what is true of the moment of sending rather than the moment of failing: which build is
     * running now, what the device is, and this process's own logcat.
     */
    private suspend fun bundle(kind: String, faultText: String?, note: String? = null): String {
        val now = System.currentTimeMillis()
        val raw = buildString {
            append("# ").append(appLabel).append(" fault report — ").append(kind).append("\n\n")
            append("- sent: ").append(TS.format(Date(now))).append('\n')
            append("- build: ").append(buildLabel).append('\n')
            append("- device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            if (note != null) append("- note: ").append(note).append('\n')
            append('\n')
            if (faultText != null) {
                append("## The fault\n\n```\n").append(faultText.take(MAX_FAULT_CHARS)).append("\n```\n\n")
            } else {
                append("_No fault has been recorded on this device. This report is the context only._\n\n")
            }
            append("## What was happening just now\n\n```\n").append(Breadcrumbs.render(now)).append("```\n\n")
            append("## Logcat — this process only, last lines\n\n```\n").append(readOwnLogcat()).append("\n```\n")
        }
        return SecretScrub.scrub(raw, secrets().filter { it.length >= 8 })
    }

    /**
     * This app's own recent logcat.
     *
     * ⚠️ An ordinary app is restricted to its own process's output, which is the reason this is
     * useful rather than alarming: it is what this application printed, not what the phone did. It
     * still goes through the scrubber, and it is a large part of why the auto-send switch is visible
     * rather than assumed.
     */
    private fun readOwnLogcat(): String = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-t", "400"))
        val out = proc.inputStream.bufferedReader().use { it.readText() }
        runCatching { proc.destroy() }
        if (out.length > MAX_LOGCAT_CHARS) out.takeLast(MAX_LOGCAT_CHARS) else out.ifBlank { "(no logcat captured)" }
    }.getOrDefault("(logcat unavailable on this device)")

    private suspend fun upload(kind: String, body: String): Result = runCatching {
        // ⚠️ Create the branch on first use, and only then. Asking for its head is one cheap request;
        // creating it unconditionally would fail on every later report with a 422 that reads like a
        // real error.
        if (headSha(BRANCH) == null) {
            val main = headSha("main") ?: return@runCatching Result.Failed("could not read main")
            createBranch(BRANCH, main)
        }
        val path = "reports/$stream/${System.currentTimeMillis()}-$kind.md"
        putFile(path, body, "$stream: $kind report")
        Result.Ok(path) as Result
    }.getOrElse { Result.Failed(it.message ?: "upload failed") }

    private suspend fun headSha(branch: String): String? = runCatching {
        JSONObject(request("GET", "$API/git/ref/heads/$branch", null))
            .getJSONObject("object").getString("sha")
    }.getOrNull()

    private suspend fun createBranch(name: String, fromSha: String) {
        request(
            "POST",
            "$API/git/refs",
            JSONObject().put("ref", "refs/heads/$name").put("sha", fromSha),
        )
    }

    private suspend fun putFile(path: String, content: String, message: String) {
        request(
            "PUT",
            "$API/contents/$path",
            JSONObject()
                .put("message", message)
                // ⚠️ `android.util.Base64`, not `java.util.Base64`. This module's floor is the WIDE
                // one so the standalone app can use it, and the platform one has no version
                // question attached to it at all. NO_WRAP because the API rejects line breaks.
                .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
                .put("branch", BRANCH),
        )
    }

    /**
     * Turn a refusal into the sentence somebody can act on.
     *
     * ⚠️ **The case this exists for is a token that can read and not write**, because that is the
     * token the update card asks for and updating is the only other thing the token does. Every
     * report then failed with the bare string "GitHub 403 on PUT", which names the symptom and
     * nothing else — so the one feature whose whole purpose is to explain a failure was itself
     * failing unexplained.
     *
     * ⚠️ **Only for the write methods.** A 404 on the GET of `git/ref/heads/debug-reports` is the
     * ordinary first-ever-report case — the branch does not exist yet, [headSha] swallows it to null
     * and [upload] then creates it — so mapping that one onto a permissions sentence would report a
     * successful first upload as a broken token.
     *
     * ⚠️ 404 is grouped with 403 deliberately and hedged rather than asserted: GitHub answers 404
     * rather than 403 when a credential cannot see a private repository at all, precisely so that an
     * error does not confirm the repository exists. From here the two are one problem with the token.
     */
    private fun explain(code: Int, method: String): String = when {
        method != "GET" && (code == 403 || code == 404) ->
            "GitHub $code on $method — this token cannot write to the repository. Sending reports " +
                "needs write access to contents; updating the app needs only read, so updates will " +
                "keep working while reports do not."
        code == 401 ->
            "GitHub 401 — the token was rejected outright. It has probably expired or been revoked."
        code == 429 || code == 503 ->
            "GitHub $code — rate-limited or unavailable. Nothing is lost; this retries on the next launch."
        else -> "GitHub $code on $method"
    }

    private suspend fun request(method: String, url: String, body: JSONObject?): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
            token()?.let { builder.header("Authorization", "Bearer $it") }
            val payload = body?.toString()?.toRequestBody(JSON_MEDIA)
            when (method) {
                "GET" -> builder.get()
                "POST" -> builder.post(payload ?: EMPTY_BODY)
                else -> builder.method(method, payload ?: EMPTY_BODY)
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // ⚠️ The body is NOT included in the thrown message. GitHub echoes parts of the
                    // request back in an error, and this request's body is a report that may quote a
                    // credential the scrubber ran over — putting it into an exception would hand it
                    // straight to the next thing that logs the failure.
                    throw IOException(explain(response.code, method))
                }
                text
            }
        }

    private fun readSent(): Set<String> = runCatching {
        if (marker.exists()) marker.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
    }.getOrDefault(emptySet())

    private fun markSent(id: String) {
        runCatching {
            marker.appendText(id + "\n")
            val lines = marker.readLines().filter { it.isNotBlank() }
            if (lines.size > MARKER_CAP) marker.writeText(lines.takeLast(MARKER_CAP).joinToString("\n") + "\n")
        }
    }

    private companion object {
        /** ⚠️ The same repository constant the updater reads releases from — one statement of it. */
        const val API = "https://api.github.com/repos/mascwa200-beep/Thing"
        const val BRANCH = "debug-reports"
        const val BURST = 5
        const val MARKER_CAP = 50
        const val MAX_FAULT_CHARS = 20_000
        const val MAX_LOGCAT_CHARS = 40_000
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null, 0, 0)
        val TS = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    }
}
