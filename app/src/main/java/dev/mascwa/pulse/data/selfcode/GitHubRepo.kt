package dev.mascwa.pulse.data.selfcode

import android.util.Base64
import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal GitHub **write** client for the self-coding loop: read a file, create a branch, commit a file,
 * open a PR, read a commit's checks, and merge. Uses the `repo`-scoped GitHub token from Settings.
 *
 * Safety: [isProtected] refuses any path that would let the autonomous loop weaken its own brakes —
 * CI workflows, the build/signing config, the manifest, the approval gate, and the self-coder itself.
 */
class GitHubRepo(private val settings: SettingsRepository) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Pr(val number: Int, val url: String, val headSha: String, val headRef: String)

    suspend fun token(): String? =
        runCatching { settings.current().jarvis.githubToken }.getOrNull()?.trim()?.ifBlank { null }

    /** Paths the autonomous editor may never modify (so it can't disable CI / signing / its own gates). */
    fun isProtected(path: String): Boolean {
        val p = path.trim().trimStart('/')
        return PROTECTED.any { p == it || p.startsWith(it) }
    }

    private suspend fun raw(method: String, url: String, body: JSONObject? = null): String =
        withContext(Dispatchers.IO) {
            val tok = token() ?: throw IOException("No GitHub token set.")
            val builder = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $tok")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", HttpClient.USER_AGENT)
            if (method == "GET") builder.get() else builder.method(method, (body?.toString() ?: "{}").toRequestBody(JSON))
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("GitHub ${resp.code}: ${text.take(200)}")
                text
            }
        }

    private suspend fun obj(method: String, url: String, body: JSONObject? = null) = JSONObject(raw(method, url, body))
    private suspend fun arr(method: String, url: String) = JSONArray(raw(method, url))

    suspend fun headSha(branch: String = "main"): String =
        obj("GET", "$API/git/ref/heads/$branch").getJSONObject("object").getString("sha")

    /** Every source-file (blob) path in the repo tree at [ref], recursively, with protected paths
     *  dropped. Lets the model pick WHICH file to change from a goal (the "knows where to do it" step).
     *  Takes a commit/branch sha — the trees API resolves it to that commit's tree. */
    suspend fun tree(ref: String = "main"): List<String> {
        val sha = runCatching { headSha(ref) }.getOrElse { ref }
        val nodes = obj("GET", "$API/git/trees/$sha?recursive=1").optJSONArray("tree") ?: return emptyList()
        val out = ArrayList<String>(nodes.length())
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (node.optString("type") != "blob") continue
            val path = node.optString("path")
            if (path.isNotBlank() && !isProtected(path)) out.add(path)
        }
        return out
    }

    suspend fun createBranch(name: String, fromSha: String) {
        obj("POST", "$API/git/refs", JSONObject().put("ref", "refs/heads/$name").put("sha", fromSha))
    }

    /** Existing file blob sha for [path] on [ref], or null if it doesn't exist. */
    suspend fun fileSha(path: String, ref: String): String? =
        runCatching { obj("GET", "$API/contents/$path?ref=$ref").optString("sha").ifBlank { null } }.getOrNull()

    /** Decoded text of [path] on [ref], or null if missing. */
    suspend fun getFile(path: String, ref: String): String? = runCatching {
        val b64 = obj("GET", "$API/contents/$path?ref=$ref").optString("content").replace("\n", "")
        String(Base64.decode(b64, Base64.DEFAULT))
    }.getOrNull()

    suspend fun putFile(path: String, content: String, message: String, branch: String, sha: String?) {
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        obj("PUT", "$API/contents/$path", body)
    }

    suspend fun openPr(title: String, head: String, body: String, base: String = "main"): Pr {
        val o = obj("POST", "$API/pulls", JSONObject().put("title", title).put("head", head).put("base", base).put("body", body))
        return Pr(o.getInt("number"), o.optString("html_url"), o.getJSONObject("head").getString("sha"), o.getJSONObject("head").getString("ref"))
    }

    /** Aggregate check-runs state for a commit: "success" | "failure" | "pending" | "none". */
    suspend fun checksState(sha: String): String {
        val runs = obj("GET", "$API/commits/$sha/check-runs").optJSONArray("check_runs") ?: return "none"
        if (runs.length() == 0) return "none"
        var pending = false
        var failed = false
        for (i in 0 until runs.length()) {
            val cr = runs.getJSONObject(i)
            if (cr.optString("status") != "completed") pending = true
            else if (cr.optString("conclusion") !in OK_CONCLUSIONS) failed = true
        }
        return when {
            failed -> "failure"
            pending -> "pending"
            else -> "success"
        }
    }

    /** Open PRs from the self-coder's own `jarvis/…` branches. */
    suspend fun openSelfPrs(): List<Pr> {
        val list = arr("GET", "$API/pulls?state=open&per_page=30")
        return (0 until list.length()).mapNotNull { i ->
            val p = list.getJSONObject(i)
            val head = p.getJSONObject("head")
            val ref = head.getString("ref")
            if (!ref.startsWith("jarvis/")) null
            else Pr(p.getInt("number"), p.optString("html_url"), head.getString("sha"), ref)
        }
    }

    suspend fun merge(number: Int): Boolean =
        runCatching { obj("PUT", "$API/pulls/$number/merge", JSONObject().put("merge_method", "squash")); true }.getOrDefault(false)

    companion object {
        const val API = "https://api.github.com/repos/mascwa200-beep/Thing"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val OK_CONCLUSIONS = setOf("success", "neutral", "skipped")
        // Never edited by the autonomous loop — keeps CI, signing, manifest, the approval gate and the
        // self-coder itself off-limits so it can't remove its own safety rails.
        private val PROTECTED = listOf(
            ".github/",
            "gradle/",
            "build.gradle.kts",
            "settings.gradle.kts",
            "app/build.gradle.kts",
            "app/debug.keystore",
            "app/src/main/AndroidManifest.xml",
            "app/src/main/java/dev/mascwa/pulse/jarvis/selfedit/ApprovalGate.kt",
            "app/src/main/java/dev/mascwa/pulse/data/selfcode/",
        )
    }
}
