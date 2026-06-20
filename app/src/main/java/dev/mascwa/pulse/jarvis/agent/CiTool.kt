package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.selfcode.GitHubRepo

/**
 * Lets J.A.R.V.I.S. read WHY its own CI builds failed — the actual `compileReleaseKotlin` errors from the
 * GitHub Actions log — so it can self-correct instead of re-proposing blind. Closes the feedback loop the
 * self-coder was missing: it could open a PR but never see the compile error that sank it.
 *
 * Read-only. `ci` (blank) reports the newest failed `jarvis/` build's errors; `ci <branch>` targets a
 * specific branch's latest run. Needs the `repo`-scoped GitHub token.
 */
class CiTool(private val repo: GitHubRepo) : JarvisTool {
    override val name = "ci"
    override val usage =
        "ci [branch] — why your last build failed: blank shows the newest failed self-code build's compile " +
            "errors; a branch name targets that branch's latest run"

    override suspend fun run(arg: String): String {
        if (repo.token() == null) return "Add a GitHub token (repo scope) so I can read CI, sir."
        val branch = arg.trim().trim('`', '"').ifBlank { null }
        val runs = runCatching { repo.recentRuns(30) }.getOrDefault(emptyList())
        if (runs.isEmpty()) return "I can't see any CI runs, sir — check the token scope."
        val run = if (branch != null) {
            runs.firstOrNull { it.branch == branch }
                ?: return "No CI run for `$branch`, sir."
        } else {
            runs.firstOrNull { it.branch.startsWith("jarvis/") && it.status == "completed" }
                ?: return "No completed self-code (jarvis/) builds to report on, sir."
        }
        if (run.status != "completed") return "CI for `${run.branch}` is still ${run.status}, sir."
        return when (run.conclusion) {
            "success" -> "`${run.branch}` is green, sir — that build compiled."
            "failure" -> "`${run.branch}` failed CI. The errors:\n\n" + repo.runErrors(run.id)
            "cancelled" -> "`${run.branch}` was cancelled (superseded by a newer push), sir."
            else -> "`${run.branch}`: ${run.conclusion}, sir."
        }
    }
}
