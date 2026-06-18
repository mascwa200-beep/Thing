package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfcode.GitHubRepo

/**
 * Lets J.A.R.V.I.S. READ its own source from the GitHub repo so it can answer "how's your code look?" and
 * ground a self-code proposal in the real files. Read-only; offered alongside the self-coding tool. Needs
 * the GitHub token (the repo is private).
 */
class SelfCodeReadTool(private val repo: GitHubRepo) : JarvisTool {
    override val name = "code"
    override val usage =
        "code [path] — read your OWN app source: blank lists your source files, a path returns that file"

    override suspend fun run(arg: String): String {
        if (repo.token() == null) return "Add a GitHub token (repo scope) in Setup so I can read my own source, sir."
        val path = arg.trim().trim('`', '"').trimStart('/')
        if (path.isBlank()) {
            val tree = runCatching { repo.tree("main") }.getOrDefault(emptyList())
            if (tree.isEmpty()) return "I couldn't read my source tree, sir — check the token scope."
            val shown = tree.take(MAX_TREE).joinToString("\n")
            val more = if (tree.size > MAX_TREE) "\n… (${tree.size - MAX_TREE} more; ask for a path)" else ""
            return "My source files (${tree.size}):\n$shown$more"
        }
        val content = runCatching { repo.getFile(path, "main") }.getOrNull()
            ?: return "I couldn't read `$path`, sir — check the path (run `code` with no argument to list files)."
        val body = content.take(MAX_FILE)
        val truncated = if (content.length > MAX_FILE) "\n… (truncated)" else ""
        return "`$path`:\n$body$truncated"
    }

    private companion object {
        const val MAX_TREE = 400
        // Aligned with the agent's per-observation cap so a file read isn't double-truncated.
        const val MAX_FILE = 6000
    }
}
