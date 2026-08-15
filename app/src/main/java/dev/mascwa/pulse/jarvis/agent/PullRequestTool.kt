package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfcode.GitHubRepo

/**
 * Lets J.A.R.V.I.S. MANAGE its own pull requests — the `jarvis/…` branches the self-coder opens. It can
 * list them with live CI status and close the dead/duplicate/superseded ones (closing is reversible on
 * GitHub; the throwaway branch is tidied up). Two safety properties: it is scoped to J.A.R.V.I.S.'s OWN
 * PRs (it can never close a human's PR), and it never MERGES here — shipping still goes through
 * auto-merge-on-green / the user. Janitorial + visibility only. Needs the `repo`-scoped GitHub token.
 *
 * This is the "safe GitHub powers" upgrade: knowing what it has already proposed (so it doesn't open
 * duplicates) and clearing its own dead proposals, without touching the human-approval gate.
 */
class PullRequestTool(private val repo: GitHubRepo) : JarvisTool {
    override val name = "pr"
    override val usage =
        "pr [list|close <number>] — manage your OWN pull requests: `list` (or blank) shows your open " +
            "jarvis/ PRs with CI status (success/pending/failure); `close <number>` closes a dead or " +
            "duplicate one of yours and tidies its branch. You cannot close a human's PR or merge here."

    override suspend fun run(arg: String): String {
        if (repo.token() == null) return "Add a GitHub token (repo scope) in Setup so I can manage PRs."
        val a = arg.trim().trim('`', '"')
        val parts = a.split(Regex("\\s+"), limit = 2)
        return when (parts.getOrElse(0) { "" }.lowercase()) {
            "", "list", "ls", "mine", "open" -> list()
            "close", "drop", "delete" -> close(parts.getOrElse(1) { "" })
            else -> "Usage: `pr list` or `pr close <number>`."
        }
    }

    private suspend fun list(): String {
        val prs = runCatching { repo.openSelfPrs() }.getOrDefault(emptyList())
        if (prs.isEmpty()) return "No open PRs of mine right now."
        val lines = prs.map { p ->
            val status = runCatching { repo.checksState(p.headSha) }.getOrDefault("none")
            "#${p.number} [$status] ${p.headRef} — ${p.url}"
        }
        return "My open PRs (${prs.size}):\n" + lines.joinToString("\n") +
            "\n\nClose any dead or duplicate one with `pr close <number>`."
    }

    private suspend fun close(rawArg: String): String {
        val number = rawArg.trim().trim('#', '`', '"').toIntOrNull()
            ?: return "Give me the PR number to close — see `pr list`."
        // Resolve against MY own open PRs so I can never close a human's PR.
        val mine = runCatching { repo.openSelfPrs() }.getOrDefault(emptyList())
        val target = mine.firstOrNull { it.number == number }
            ?: return "#$number isn't one of my open PRs — I only close my own."
        if (!repo.closePr(number)) return "Couldn't close #$number — check the token scope."
        runCatching { repo.deleteBranch(target.headRef) }
        return "Closed #$number and tidied up `${target.headRef}`."
    }
}
