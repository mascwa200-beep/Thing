import dev.mascwa.pulse.core.telemetry.GuideSearch
import java.io.File

fun main(args: Array<String>) {
    val entries = File("/home/user/Thing/scratchpad/kb/index.tsv").readLines().mapNotNull { line ->
        val p = line.split("\t")
        if (p.size < 5) null else GuideSearch.Entry(
            id = p[0], title = p[1], category = p[2], summary = p[3],
            headings = if (p[4].isBlank()) emptyList() else p[4].split(" ||| "),
        )
    }
    System.err.println("loaded ${entries.size} entries")
    File("/home/user/Thing/scratchpad/kb/queries.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.forEach { q ->
            val hits = GuideSearch.rank(entries, q, 3)
            println("Q: $q")
            if (hits.isEmpty()) println("     (nothing)")
            hits.forEach { h -> println("     %-6.2f m=%d  %s  [%s]".format(h.score, h.matched, h.entry.title.take(72), h.entry.category)) }
        }
}
