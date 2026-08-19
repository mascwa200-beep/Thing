import dev.mascwa.pulse.core.telemetry.GuideSearch
import dev.mascwa.pulse.core.telemetry.LibraryConsult
import java.io.File

/** The SHIPPED consult gate (distinctiveToken -> rank -> isTopical) over the REAL 651-guide index. */
fun main() {
    val entries = File("/home/user/Thing/scratchpad/kb/index.tsv").readLines().mapNotNull { line ->
        val p = line.split("\t")
        if (p.size < 5) null else GuideSearch.Entry(
            id = p[0], title = p[1], category = p[2], summary = p[3],
            headings = if (p[4].isBlank()) emptyList() else p[4].split(" ||| "),
        )
    }
    System.err.println("loaded ${entries.size}")
    val utterances = File("/home/user/Thing/scratchpad/interr/utterances.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
    var grounded = 0
    for (u in utterances) {
        val key = GuideSearch.distinctiveToken(entries, u)
        val top = GuideSearch.rank(entries, u, 1).firstOrNull()
        val ok = key != null && top != null && LibraryConsult.isTopical(top.entry, key)
        val m = top?.matched ?: 0
        if (ok) grounded++
        println("%-4s m=%d  %s".format(if (ok) "HIT" else "-", m, u))
        println("       key=%-14s -> %s".format(key ?: "(none)", top?.entry?.title?.take(64) ?: "(nothing)"))
    }
    println("\ngrounded ${grounded}/${utterances.size}")
}
