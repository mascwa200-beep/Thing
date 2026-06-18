package dev.mascwa.pulse.data.selfcode

import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.toList

/**
 * The self-coding brain: given a goal + a target file, has the (cloud/OpenRouter) model draft the new
 * file contents and opens a GitHub PR via [GitHubRepo]. It only ever PROPOSES a PR — CI compiles it and
 * (optionally) auto-merges only on green; the install is always user-confirmed. Protected paths are
 * refused so it can't weaken its own safety rails.
 */
class SelfCoder(
    private val engine: LocalInferenceEngine,
    private val repo: GitHubRepo,
) {
    data class Result(val ok: Boolean, val message: String)

    suspend fun propose(goal: String, path: String): Result {
        val cleanPath = path.trim().trimStart('/')
        if (goal.isBlank() || cleanPath.isBlank()) return Result(false, "Give me a goal and a file path, sir.")
        if (repo.isProtected(cleanPath)) return Result(false, "$cleanPath is protected from autonomous edits, sir.")
        if (repo.token() == null) return Result(false, "Add a GitHub token (repo scope) in Setup first, sir.")

        val base = runCatching { repo.headSha("main") }.getOrElse {
            return Result(false, "Couldn't read the repo (token scope?): ${it.message}")
        }
        val current = runCatching { repo.getFile(cleanPath, "main") }.getOrNull()
        val newContent = draft(goal, cleanPath, current)
            ?: return Result(false, "I couldn't draft a valid change for $cleanPath, sir.")

        val branch = "jarvis/" + System.currentTimeMillis()
        return runCatching {
            repo.createBranch(branch, base)
            repo.putFile(cleanPath, newContent, "J.A.R.V.I.S.: $goal", branch, repo.fileSha(cleanPath, "main"))
            val pr = repo.openPr(
                title = "J.A.R.V.I.S.: ${goal.take(60)}",
                head = branch,
                body = "Autonomous change drafted by J.A.R.V.I.S.\n\n**Goal:** $goal\n**File:** `$cleanPath`\n\n" +
                    "CI must pass before this can merge.",
            )
            Result(true, "Opened PR #${pr.number} — ${pr.url}")
        }.getOrElse { Result(false, "Couldn't open the PR: ${it.message}") }
    }

    /** Ask the model for the complete new file contents (strips any code fences). */
    private suspend fun draft(goal: String, path: String, current: String?): String? {
        val sys = "You are J.A.R.V.I.S. editing your own Android app, written in Kotlin. Output ONLY the " +
            "complete new contents of the file `$path` that accomplishes the goal — valid Kotlin, no " +
            "markdown fences, no commentary, no explanations."
        val user = buildString {
            append("Goal: ").append(goal).append("\n\n")
            if (current != null) append("Current contents of `$path`:\n").append(current)
            else append("`$path` does not exist yet — create it.")
        }
        val out = runCatching {
            engine.generate(user, emptyList(), sys).toList().joinToString("")
        }.getOrDefault("").trim()
        return stripFences(out).ifBlank { null }?.takeIf { it.length in 8..200_000 && !it.startsWith("//") }
    }

    private fun stripFences(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").substringAfter('\n', "").let { it }
            // drop a leading language tag line if removePrefix left "kotlin\n..."
            t = t.trimStart()
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
        }
        return t.trim()
    }
}
