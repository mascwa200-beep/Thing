package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.jarvis.KnowledgeStore
import dev.mascwa.pulse.data.selfcode.GitHubRepo

/**
 * J.A.R.V.I.S.'s persistent, evolving model of THIS app's architecture — the module layout, package
 * structure, and notes it accumulates about how the system actually works. Built on the existing
 * on-device knowledge store (no parallel system): architecture entries live under the `architecture`
 * source and are retrievable like any other knowledge. `arch map` derives a fresh structural map
 * straight from the live repository tree, so the model stays grounded in the real code rather than guesses.
 *
 * Read + note-taking only — it never changes code (that stays with the gated `selfcode` path).
 */
class ArchitectureTool(
    private val knowledge: KnowledgeStore,
    private val repo: GitHubRepo,
) : JarvisTool {
    override val name = "arch"
    override val usage =
        "arch [map|note <text>|<query>] — your architecture knowledge of this app: `arch map` rebuilds a " +
            "structural map from the live repo tree, `arch note <text>` records an insight, a query recalls " +
            "what you know (blank lists what's stored)"

    override suspend fun run(arg: String): String {
        val a = arg.trim().trim('`', '"')
        val parts = a.split(Regex("\\s+"), limit = 2)
        return when (parts.getOrElse(0) { "" }.lowercase()) {
            "", "list" -> list()
            "map", "rebuild", "scan" -> map()
            "note", "record", "remember" -> note(parts.getOrElse(1) { "" })
            else -> recall(a)
        }
    }

    private suspend fun list(): String {
        val titles = runCatching { knowledge.titles() }.getOrDefault(emptyList())
            .filter { it.startsWith(TITLE_PREFIX) }
        return if (titles.isEmpty()) {
            "No architecture knowledge yet — run `arch map` to build one from the code, or `arch note <insight>`."
        } else {
            "Architecture knowledge:\n" + titles.joinToString("\n") { "· $it" }
        }
    }

    private suspend fun note(text: String): String {
        val t = text.trim()
        if (t.length < 4) return "Give me the architecture insight to record."
        val n = runCatching { knowledge.addDocument("$TITLE_PREFIX note — ${t.take(40)}", t, SOURCE) }.getOrDefault(0)
        return if (n > 0) "Recorded — I'll recall it when reasoning about the app's design." else "Couldn't record that."
    }

    private suspend fun recall(query: String): String {
        val hits = runCatching { knowledge.search(query, 5) }.getOrDefault(emptyList())
            .filter { it.source == SOURCE }
        return if (hits.isEmpty()) {
            "Nothing in my architecture notes on that — try `arch map` to (re)build the map."
        } else {
            "From my architecture knowledge:\n" + hits.joinToString("\n\n") { it.text.take(500) }
        }
    }

    private suspend fun map(): String {
        if (repo.token() == null) return "Add a GitHub token (repo scope) so I can read the tree."
        val tree = runCatching { repo.tree("main") }.getOrDefault(emptyList())
        if (tree.isEmpty()) return "I couldn't read the repository tree — check the token scope."
        val summary = summarize(tree)
        runCatching { knowledge.deleteDocument("$TITLE_PREFIX map") }
        runCatching { knowledge.addDocument("$TITLE_PREFIX map", summary, SOURCE) }
        return "Rebuilt my architecture map from the live tree (${tree.size} files):\n\n$summary"
    }

    /** Deterministic structural summary of the repo: Kotlin files per gradle module + per feature area. */
    private fun summarize(paths: List<String>): String {
        val modules = sortedMapOf<String, Int>()
        val areas = sortedMapOf<String, Int>()
        val areaRegex = Regex("/pulse/([a-z0-9]+)/")
        for (p in paths) {
            if (!p.endsWith(".kt")) continue
            val module = when {
                p.startsWith("app/") -> "app"
                p.startsWith("core/") -> "core/" + p.removePrefix("core/").substringBefore('/')
                else -> p.substringBefore('/')
            }
            modules[module] = (modules[module] ?: 0) + 1
            areaRegex.find(p)?.groupValues?.get(1)?.let { areas[it] = (areas[it] ?: 0) + 1 }
        }
        return buildString {
            append("MODULES (Kotlin files):\n")
            modules.forEach { (m, n) -> append("  ").append(m).append(": ").append(n).append('\n') }
            append("\nAREAS under dev.mascwa.pulse (by size):\n")
            areas.entries.sortedByDescending { it.value }.forEach { (a, n) ->
                append("  ").append(a).append(": ").append(n).append('\n')
            }
        }
    }

    private companion object {
        const val SOURCE = "architecture"
        const val TITLE_PREFIX = "Architecture"
    }
}
