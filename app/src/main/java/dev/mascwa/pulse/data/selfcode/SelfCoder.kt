package dev.mascwa.pulse.data.selfcode

import dev.mascwa.pulse.data.selfedit.ActionType
import dev.mascwa.pulse.data.selfedit.PendingAction
import dev.mascwa.pulse.data.selfedit.SelfEditStore
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.toList
import java.util.UUID

/**
 * The self-coding brain. Given a plain-language goal it can work out WHICH file to change (the model
 * picks from the repo's source tree when no path is given), have the (cloud/OpenRouter) model draft the
 * new file contents, and STAGE it as a [PendingAction] for the user to approve. Only on approval does
 * [commit] actually open a GitHub PR — CI compiles it and (optionally) auto-merges on green; the install
 * is still user-confirmed. Protected paths are refused so it can't weaken its own safety rails.
 */
class SelfCoder(
    private val engine: LocalInferenceEngine,
    private val repo: GitHubRepo,
    private val selfEdit: SelfEditStore,
) {
    data class Result(val ok: Boolean, val message: String)

    /** Back-compat entry point (e.g. the Settings form): stage a change for approval. A blank [path]
     *  lets the model choose the file itself. */
    suspend fun propose(goal: String, path: String): Result = stage(goal, path.trim().ifBlank { null })

    /**
     * Stage a change for the user to approve: resolve the target file (model-chosen when [pathHint] is
     * null), draft the new contents, and enqueue a CODE_PR proposal. Does NOT touch GitHub — the PR
     * opens only when the user approves (see [commit]).
     */
    suspend fun stage(goal: String, pathHint: String? = null): Result {
        if (goal.isBlank()) return Result(false, "Tell me what to change, sir.")
        if (repo.token() == null) return Result(false, "Add a GitHub token (repo scope) in Setup first, sir.")

        val path = (pathHint?.trim()?.trimStart('/')?.ifBlank { null } ?: locate(goal))
            ?: return Result(false, "I couldn't work out which file to change for that, sir — try naming the area.")
        if (repo.isProtected(path)) return Result(false, "`$path` is protected from autonomous edits, sir.")

        val current = runCatching { repo.getFile(path, "main") }.getOrNull()
        val newContent = draft(goal, path, current)
            ?: return Result(false, "I couldn't draft a valid change for `$path`, sir.")

        val preview = buildString {
            append("File: ").append(path).append('\n')
            append(if (current == null) "(new file)" else "(${current.lines().size} → ${newContent.lines().size} lines)")
            append("\n\n")
            append(newContent.lineSequence().take(40).joinToString("\n"))
        }
        selfEdit.enqueue(
            PendingAction(
                id = UUID.randomUUID().toString(),
                type = ActionType.CODE_PR,
                title = "Code change · ${goal.take(60)}",
                preview = preview,
                payload = mapOf("goal" to goal, "path" to path, "content" to newContent),
                createdAt = System.currentTimeMillis(),
            ),
        )
        return if (current == null) {
            Result(true, "Drafted a NEW file `$path` — approve it to open the PR, sir. (A new file compiles " +
                "on its own but may need wiring into the app to take effect.)")
        } else {
            Result(true, "Drafted a change to `$path` — approve it to open the PR, sir.")
        }
    }

    /** Open the PR for an approved change. Called ONLY from [dev.mascwa.pulse.jarvis.selfedit.ApprovalGate]
     *  (a user-driven tap), never from the agent loop — so no PR is opened without the user's approval. */
    suspend fun commit(goal: String, path: String, content: String): String {
        val cleanPath = path.trim().trimStart('/')
        if (cleanPath.isBlank() || content.isBlank()) return "Nothing to commit, sir."
        if (repo.isProtected(cleanPath)) return "`$cleanPath` is protected, sir."
        val base = runCatching { repo.headSha("main") }.getOrElse {
            return "Couldn't read the repo (token scope?): ${it.message}"
        }
        val branch = "jarvis/" + System.currentTimeMillis()
        return runCatching {
            repo.createBranch(branch, base)
            repo.putFile(cleanPath, content, "J.A.R.V.I.S.: $goal", branch, repo.fileSha(cleanPath, "main"))
            val pr = repo.openPr(
                title = "J.A.R.V.I.S.: ${goal.take(60)}",
                head = branch,
                body = "Autonomous change drafted by J.A.R.V.I.S.\n\n**Goal:** $goal\n**File:** `$cleanPath`\n\n" +
                    "CI must pass before this can merge.",
            )
            "Opened PR #${pr.number} — ${pr.url}"
        }.getOrElse { "Couldn't open the PR: ${it.message}" }
    }

    /** Ask the model which file to change, given the repo's (editable) source tree — either an existing
     *  path to edit, or `NEW: <path>` to create a brand-new file. The reply is validated against the tree
     *  and the denylist, so the model can't escape the protected paths or drop a file outside a source
     *  root. */
    private suspend fun locate(goal: String): String? {
        val candidates = runCatching { repo.tree("main") }.getOrDefault(emptyList())
            .filter { it.endsWith(".kt") }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return null
        val sys = "You are J.A.R.V.I.S., deciding where to make a change in your own Android app (Kotlin). " +
            "Either reply with ONE exact existing file path copied from the list (to edit it), OR — if the " +
            "goal needs a brand-new file — reply with `NEW: <path>` using a path under " +
            "app/src/main/java/… or core/<module>/src/main/java/… that is not already in the list. " +
            "Reply with ONLY the path (or `NEW: <path>`), nothing else."
        val user = buildString {
            append("Goal: ").append(goal).append("\n\nExisting files:\n")
            candidates.forEach { append(it).append('\n') }
        }
        val raw = runCatching {
            engine.generate(user, emptyList(), sys).toList().joinToString("")
        }.getOrDefault("")
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (raw.isBlank()) return null

        // New-file proposal: validate it stays Kotlin, in a real source root, off the denylist, and unused.
        Regex("(?i)^NEW:\\s*(.+)$").find(raw)?.let { m ->
            val p = m.groupValues[1].trim().trim('`', '"').trimStart('/')
            return if (isValidNewPath(p, candidates)) p else null
        }

        // Otherwise an existing path: accept only an exact or unambiguous suffix match.
        val pick = raw.trim('`', '"', '-', ' ')
        if (pick.isBlank()) return null
        return candidates.firstOrNull { it == pick }
            ?: candidates.singleOrNull { pick.endsWith(it) || it.endsWith(pick) }
    }

    /** A model-proposed NEW file path is allowed only when it's Kotlin, sits under a real source root, is
     *  not protected, and doesn't already exist — so a bad path fails closed instead of committing junk. */
    private fun isValidNewPath(path: String, existing: List<String>): Boolean {
        if (!path.endsWith(".kt") || path.contains("..")) return false
        if (repo.isProtected(path) || existing.contains(path)) return false
        return path.startsWith("app/src/main/java/") ||
            Regex("^core/[^/]+/src/main/java/").containsMatchIn(path)
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

    private companion object {
        // Bound the candidate list fed to the model so the locate prompt stays reasonable.
        const val MAX_CANDIDATES = 600
    }
}
