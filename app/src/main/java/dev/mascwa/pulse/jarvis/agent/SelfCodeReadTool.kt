package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfcode.GitHubRepo

/**
 * Lets J.A.R.V.I.S. READ the **entire** GitHub repository — every file (nothing hidden), the commit log,
 * and all pull-request activity (any author, not just its own). Admin-level visibility into the codebase
 * it runs on. Read-only; offered alongside the self-coding tool. Needs the `repo`-scoped GitHub token.
 */
class SelfCodeReadTool(private val repo: GitHubRepo) : JarvisTool {
    override val name = "code"
    override val usage =
        "code [path|log|prs] — read the repository: blank lists ALL files, a path returns that file, " +
            "`log` shows recent commits, `prs` shows recent pull requests"

    override suspend fun run(arg: String): String {
        if (repo.token() == null) return "Add a GitHub token (repo scope) in Setup so I can read the repository."
        val a = arg.trim().trim('`', '"')
        when (a.lowercase()) {
            "log", "commits", "history" -> {
                val commits = runCatching { repo.commits("main", 25) }.getOrDefault(emptyList())
                if (commits.isEmpty()) return "I couldn't read the commit log — check the token scope."
                return "Recent commits (main):\n" + commits.joinToString("\n")
            }
            "prs", "pulls", "pr" -> {
                val prs = runCatching { repo.pulls(25) }.getOrDefault(emptyList())
                if (prs.isEmpty()) return "No recent pull requests I can see."
                return "Recent pull requests:\n" + prs.joinToString("\n")
            }
        }
        val path = a.trimStart('/')
        if (path.isBlank()) {
            val tree = runCatching { repo.tree("main") }.getOrDefault(emptyList())
            if (tree.isEmpty()) return "I couldn't read the repository tree — check the token scope."
            val shown = tree.take(MAX_TREE).joinToString("\n")
            val more = if (tree.size > MAX_TREE) "\n… (${tree.size - MAX_TREE} more; ask for a path)" else ""
            return "Repository files (${tree.size}):\n$shown$more"
        }
        val content = runCatching { repo.getFile(path, "main") }.getOrNull()
            ?: return "I couldn't read `$path` — check the path (run `code` with no argument to list files)."
        val body = content.take(MAX_FILE)
        val truncated = if (content.length > MAX_FILE) "\n… (truncated)" else ""
        return "`$path`:\n$body$truncated"
    }

    private companion object {
        const val MAX_TREE = 600
        // Aligned with the agent's per-observation cap so a file read isn't double-truncated.
        const val MAX_FILE = 6000
    }
}
