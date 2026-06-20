package dev.mascwa.pulse.data.selfcode

import dev.mascwa.pulse.data.selfedit.ActionType
import dev.mascwa.pulse.data.selfedit.PendingAction
import dev.mascwa.pulse.data.selfedit.SelfEditStore
import dev.mascwa.pulse.jarvis.inference.LocalInferenceEngine
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * The self-coding brain. Given a plain-language goal it plans the MINIMAL set of files to change — it can
 * create a brand-new file AND wire it in by editing existing ones, all in a single PR — has the
 * (cloud/OpenRouter) model draft each file's full contents, and STAGES the lot as one [PendingAction] for
 * the user to approve. Only on approval does [commit] open the GitHub PR — CI compiles it and (optionally)
 * auto-merges on green; the install is still user-confirmed. Protected paths are refused so it can't
 * weaken its own safety rails.
 */
class SelfCoder(
    private val engine: LocalInferenceEngine,
    private val repo: GitHubRepo,
    private val selfEdit: SelfEditStore,
) {
    data class Result(val ok: Boolean, val message: String, val action: PendingAction? = null)

    /** One file in a staged change. [isNew] just drives messaging — commit creates vs updates based on
     *  the live blob SHA either way. */
    @Serializable
    data class StagedFile(val path: String, val content: String, val isNew: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    /** Back-compat entry point (e.g. the Settings form): stage a change for approval. A blank [path]
     *  lets the model plan the files itself. */
    suspend fun propose(goal: String, path: String): Result = stage(goal, path.trim().ifBlank { null })

    /**
     * Stage a change for the user to approve: plan the target files (model-chosen when [pathHint] is
     * null), draft each file's contents, and enqueue ONE CODE_PR proposal covering them all. Does NOT
     * touch GitHub — the PR opens only when the user approves (see [commit]).
     */
    suspend fun stage(goal: String, pathHint: String? = null): Result {
        if (goal.isBlank()) return Result(false, "Tell me what to change, sir.")
        if (repo.token() == null) return Result(false, "Add a GitHub token (repo scope) in Setup first, sir.")

        val targets = planFiles(goal, pathHint?.trim()?.trimStart('/')?.ifBlank { null })
        if (targets.isEmpty()) return Result(false, "I couldn't work out which files to change for that, sir — try naming the area.")

        val diffs = StringBuilder()
        val files = targets.mapNotNull { t ->
            val current = if (t.isNew) null else runCatching { repo.getFile(t.path, "main") }.getOrNull()
            val content = draftFile(goal, t.path, targets.map { it.path }, current) ?: return@mapNotNull null
            if (diffs.isNotEmpty()) diffs.append("\n\n")
            diffs.append(LineDiff.diff(current, content, t.path))
            StagedFile(t.path, content, t.isNew)
        }
        if (files.isEmpty()) return Result(false, "I couldn't draft valid contents for that change, sir.")

        val preview = buildString {
            append(files.size).append(if (files.size == 1) " file:\n" else " files:\n")
            files.forEach { append(if (it.isNew) "NEW  " else "EDIT ").append(it.path).append('\n') }
            files.firstOrNull()?.let { append('\n').append(it.content.lineSequence().take(24).joinToString("\n")) }
        }
        val action = PendingAction(
            id = UUID.randomUUID().toString(),
            type = ActionType.CODE_PR,
            title = "Code change · ${goal.take(60)}",
            preview = preview,
            payload = mapOf(
                "goal" to goal,
                "files" to json.encodeToString(ListSerializer(StagedFile.serializer()), files),
                "diff" to diffs.toString().take(MAX_DIFF_CHARS),
            ),
            createdAt = System.currentTimeMillis(),
        )
        selfEdit.enqueue(action)
        val summary = files.joinToString(", ") { (if (it.isNew) "new " else "") + "`${it.path.substringAfterLast('/')}`" }
        val newNote = if (files.any { it.isNew }) " (a new file compiles on its own; the edits wire it in.)" else ""
        return Result(
            true,
            "Drafted $summary across ${files.size} file(s). Approve it and I'll open a PR; once CI builds " +
                "it (a few minutes), install the new build from Settings → Software update to see the change, sir.$newNote",
            action = action,
        )
    }

    /**
     * Open the PR for an approved staged change. Called ONLY from
     * [dev.mascwa.pulse.jarvis.selfedit.ApprovalGate] (a user-driven tap), never from the agent loop — so
     * no PR is opened without the user's approval. Handles the multi-file payload, with a fallback for any
     * legacy single-file proposal still in the queue.
     */
    suspend fun commit(action: PendingAction): String {
        val goal = action.payload["goal"].orEmpty()
        val files = parseStaged(action)
        if (files.isEmpty()) return "Nothing to commit, sir."
        // Last-line sanity gate: never write a malformed path (a label, spaces, no filename) to GitHub —
        // catches any legacy/queued proposal that predates path validation, so no more dead junk PRs.
        files.firstOrNull { !isCommittablePath(it.path) }?.let {
            return "`${it.path}` isn't a real file path, sir — I won't commit that. Re-issue the goal and " +
                "I'll plan the right files."
        }
        // Protected paths (CI/signing/manifest/gate/self-coder) are only refused for the fully-autonomous
        // loop; a user-approved change (a tap) may touch anything.
        if (repo.protectionEnforced()) {
            files.firstOrNull { repo.isProtected(it.path.trim().trimStart('/')) }?.let {
                return "`${it.path}` is protected while autonomous self-coding is on, sir."
            }
        }
        val base = runCatching { repo.headSha("main") }.getOrElse {
            return "Couldn't read the repo (token scope?): ${it.message}"
        }
        val branch = "jarvis/" + System.currentTimeMillis()
        return runCatching {
            repo.createBranch(branch, base)
            files.forEach { f ->
                val p = f.path.trim().trimStart('/')
                repo.putFile(p, f.content, "J.A.R.V.I.S.: $goal", branch, repo.fileSha(p, "main"))
            }
            val fileList = files.joinToString("\n") { "- `${it.path}`" + if (it.isNew) " (new)" else "" }
            val pr = repo.openPr(
                title = "J.A.R.V.I.S.: ${goal.take(60)}",
                head = branch,
                body = "Autonomous change drafted by J.A.R.V.I.S.\n\n**Goal:** $goal\n\n**Files:**\n$fileList\n\n" +
                    "CI must pass before this can merge.",
            )
            "Opened PR #${pr.number} — ${pr.url}. CI is building it now; when it's green you'll be prompted " +
                "to install the new build (Settings → Software update), and only then does the change take effect."
        }.getOrElse { "Couldn't open the PR: ${it.message}" }
    }

    private fun parseStaged(action: PendingAction): List<StagedFile> {
        action.payload["files"]?.let { jsonStr ->
            runCatching { json.decodeFromString(ListSerializer(StagedFile.serializer()), jsonStr) }
                .getOrNull()?.let { return it }
        }
        // Legacy single-file payload {path, content}.
        val path = action.payload["path"].orEmpty()
        val content = action.payload["content"].orEmpty()
        return if (path.isNotBlank() && content.isNotBlank()) listOf(StagedFile(path, content, isNew = false)) else emptyList()
    }

    /** A real repo path: non-blank, no spaces, no traversal, and an actual filename with an extension. */
    private fun isCommittablePath(path: String): Boolean {
        val p = path.trim().trimStart('/')
        return p.isNotBlank() && !p.contains(' ') && !p.contains("..") && p.substringAfterLast('/').contains('.')
    }

    private data class Target(val path: String, val isNew: Boolean)

    /** Decide which files to touch. With an explicit [pathHint] it's that single file; otherwise the model
     *  plans the minimal set (create + wire-in), validated against the tree, the denylist and source roots.
     *  Falls back to a single best-guess file if planning yields nothing usable. */
    private suspend fun planFiles(goal: String, pathHint: String?): List<Target> {
        val enforce = repo.protectionEnforced()
        val candidates = runCatching { repo.tree("main") }.getOrDefault(emptyList())
            .filter { it.endsWith(".kt") }
            .take(MAX_CANDIDATES)
        if (pathHint != null) {
            // Honour a hint ONLY if it's a real path (an existing file, or a valid new source path).
            // A freeform label (e.g. "Local code sandbox for testing") is ignored so we fall through to
            // model planning and implement the goal in real files — never commit a file named after a
            // description, which compiles to nothing and just litters the repo with dead PRs.
            resolveHint(pathHint, candidates, enforce)?.let { return it }
        }
        if (candidates.isEmpty()) return emptyList()

        val sys = "You are J.A.R.V.I.S. planning a change to your own Android app (Kotlin). List the MINIMAL " +
            "set of files to FULLY implement the goal — INCLUDING any wiring (DI registration, a screen, a " +
            "nav route) so the change actually takes effect. For each file, output one line: " +
            "`EDIT: <existing path>` or `NEW: <path>` (new paths under app/src/main/java/… or " +
            "core/<module>/src/main/java/…). Use 1 to $MAX_FILES files. Output ONLY those lines."
        val user = buildString {
            append("Goal: ").append(goal).append("\n\nExisting files:\n")
            candidates.forEach { append(it).append('\n') }
        }
        val raw = runCatching {
            engine.generate(user, emptyList(), sys).toList().joinToString("")
        }.getOrDefault("")

        val targets = LinkedHashMap<String, Target>()
        for (line in raw.lineSequence()) {
            val m = Regex("(?i)^\\s*(EDIT|NEW)\\s*:\\s*(.+)$").find(line.trim()) ?: continue
            val isNew = m.groupValues[1].equals("NEW", ignoreCase = true)
            val p = m.groupValues[2].trim().trim('`', '"').trimStart('/')
            val resolved = if (isNew) {
                if (isValidNewPath(p, candidates, enforce)) p else null
            } else {
                candidates.firstOrNull { it == p } ?: candidates.singleOrNull { p.endsWith(it) || it.endsWith(p) }
            }
            if (resolved != null && (!enforce || !repo.isProtected(resolved))) targets[resolved] = Target(resolved, isNew)
            if (targets.size >= MAX_FILES) break
        }
        if (targets.isNotEmpty()) return targets.values.toList()

        // Planning produced nothing usable — fall back to a single-file pick.
        return locate(goal)?.let { listOf(Target(it, runCatching { repo.getFile(it, "main") }.getOrNull() == null)) }
            ?: emptyList()
    }

    /** Single best-guess target (existing path, or `NEW: <path>` for a new file), validated. Used as the
     *  fallback when multi-file planning yields nothing. */
    private suspend fun locate(goal: String): String? {
        val enforce = repo.protectionEnforced()
        val candidates = runCatching { repo.tree("main") }.getOrDefault(emptyList())
            .filter { it.endsWith(".kt") }
            .take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return null
        val sys = "You are J.A.R.V.I.S., deciding where to make a change in your own Android app (Kotlin). " +
            "Either reply with ONE exact existing file path copied from the list (to edit it), OR — if the " +
            "goal needs a brand-new file — reply with `NEW: <path>` under app/src/main/java/… or " +
            "core/<module>/src/main/java/… that is not already in the list. Reply with ONLY the path."
        val user = buildString {
            append("Goal: ").append(goal).append("\n\nExisting files:\n")
            candidates.forEach { append(it).append('\n') }
        }
        val rawLine = runCatching {
            engine.generate(user, emptyList(), sys).toList().joinToString("")
        }.getOrDefault("")
            .lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (rawLine.isBlank()) return null
        Regex("(?i)^NEW:\\s*(.+)$").find(rawLine)?.let { m ->
            val p = m.groupValues[1].trim().trim('`', '"').trimStart('/')
            return if (isValidNewPath(p, candidates, enforce)) p else null
        }
        val pick = rawLine.trim('`', '"', '-', ' ')
        if (pick.isBlank()) return null
        return candidates.firstOrNull { it == pick }
            ?: candidates.singleOrNull { pick.endsWith(it) || it.endsWith(pick) }
    }

    /** Resolve a caller-supplied path hint to concrete targets, or null when it isn't actually a path (so
     *  planning takes over). An existing file → edit it; a valid new source path → create it; an explicitly
     *  named protected file → an empty list (refused outright). A freeform label falls through to planning
     *  rather than being committed as a junk file named after the text. */
    private suspend fun resolveHint(hint: String, candidates: List<String>, enforce: Boolean): List<Target>? {
        if (runCatching { repo.getFile(hint, "main") }.getOrNull() != null) {
            if (enforce && repo.isProtected(hint)) return emptyList()
            return listOf(Target(hint, isNew = false))
        }
        if (isValidNewPath(hint, candidates, enforce)) return listOf(Target(hint, isNew = true))
        if (enforce && repo.isProtected(hint)) return emptyList()
        return null
    }

    /** A model-proposed NEW file path is allowed only when it's Kotlin, sits under a real source root, is
     *  not protected, and doesn't already exist — so a bad path fails closed instead of committing junk. */
    private fun isValidNewPath(path: String, existing: List<String>, enforce: Boolean): Boolean {
        if (!path.endsWith(".kt") || path.contains("..")) return false
        if ((enforce && repo.isProtected(path)) || existing.contains(path)) return false
        return path.startsWith("app/src/main/java/") ||
            Regex("^core/[^/]+/src/main/java/").containsMatchIn(path)
    }

    /** Ask the model for the complete new contents of [path], told which other files the change spans so
     *  edits and the new file reference each other correctly. Strips any code fences. */
    private suspend fun draftFile(goal: String, path: String, allPaths: List<String>, current: String?): String? {
        val span = if (allPaths.size > 1) {
            "\n\nThis change spans these files (keep them consistent with each other):\n" +
                allPaths.joinToString("\n") { "- $it" }
        } else {
            ""
        }
        val sys = "You are J.A.R.V.I.S. editing your own Android app, written in Kotlin. Output ONLY the " +
            "complete new contents of the file `$path` that accomplishes the goal — valid Kotlin, no " +
            "markdown fences, no commentary, no explanations.$span"
        val user = buildString {
            append("Goal: ").append(goal).append("\n\n")
            if (current != null) append("Current contents of `$path`:\n").append(current)
            else append("`$path` does not exist yet — create it.")
        }
        val out = runCatching {
            engine.generate(user, emptyList(), sys).toList().joinToString("")
        }.getOrDefault("").trim()
        return stripFences(out).ifBlank { null }
            ?.takeIf { it.length in 8..200_000 && !it.startsWith("//") }
            ?.takeIf { !path.endsWith(".kt") || looksLikeKotlinSource(it) }
    }

    /** Conservative Kotlin pre-flight: a drafted `.kt` file must read like Kotlin source — a package line
     *  or a recognizable top-level declaration, no leftover markdown fence, and not a natural-language
     *  apology/prose — so a non-code draft is caught HERE instead of opening a PR that can't compile. It's
     *  intentionally lenient (real Kotlin always passes); CI remains the real compiler. */
    private fun looksLikeKotlinSource(src: String): Boolean {
        val t = src.trim()
        if (t.contains("```")) return false
        val low = t.lowercase()
        if (PROSE_STARTS.any { low.startsWith(it) }) return false
        return Regex("(?m)^\\s*package\\s+[\\w.]+").containsMatchIn(t) ||
            Regex("(?m)^\\s*(public |internal |private |abstract |open |sealed |data |enum |annotation )*" +
                "(class|object|interface|fun|val|var|typealias)\\s").containsMatchIn(t)
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
        // Bound the candidate list fed to the model so the planning prompt stays reasonable.
        const val MAX_CANDIDATES = 600
        // Cap files per change so one proposal can't balloon (keeps drafting + the PR reviewable).
        const val MAX_FILES = 4
        // Cap the stored preview diff so the approval payload stays bounded.
        const val MAX_DIFF_CHARS = 12_000
        // Openers that mark a draft as natural-language prose rather than Kotlin source.
        private val PROSE_STARTS = listOf(
            "i cannot", "i can't", "i'm sorry", "sorry", "here is", "here's", "sure,", "sure!",
            "unfortunately", "as an ai", "certainly", "of course",
        )
    }
}
