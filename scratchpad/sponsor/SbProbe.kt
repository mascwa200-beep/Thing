import dev.mascwa.pulse.core.telemetry.SponsorSegments as SB
import java.io.File

fun main() {
    val byVideo = LinkedHashMap<String, MutableList<SB.Segment>>()
    File("corpus.tsv").readLines().filter { it.isNotBlank() }.forEach { line ->
        val f = line.split('\t')
        byVideo.getOrPut(f[0]) { mutableListOf() }.add(
            SB.Segment(f[1], SB.categoryOf(f[2]), SB.actionOf(f[3]),
                f[4].toDouble(), f[5].toDouble(), f[6].toInt(), f[7] == "1")
        )
    }
    // ⚠️ A probe that finds no data and prints "0 problems" is the same lie as a test that passes
    // because its fixture never reached the branch. Assert the corpus loaded before measuring it.
    require(byVideo.size > 100) { "corpus did not load — got ${byVideo.size} videos, expected hundreds" }
    var raw = 0; var kept = 0; var merged = 0; var videosEmpty = 0
    var backwards = 0; var loops = 0; var savedS = 0.0
    for ((_, segs) in byVideo) {
        raw += segs.size
        val u = SB.usable(segs)
        kept += segs.count { SB.accept(it) }
        merged += u.size
        if (u.isEmpty()) videosEmpty++
        savedS += SB.totalSkippedS(u)
        // Walk the whole video in 0.25s steps and prove the skip loop always terminates and
        // always moves forward. This is the property no unit test on a fixture can establish.
        val end = (segs.maxOfOrNull { it.endS } ?: 0.0) + 5.0
        var pos = 0.0
        var guard = 0
        while (pos < end) {
            val t = SB.skipTo(pos, u)
            if (t != null) {
                if (t <= pos) { backwards++; break }
                pos = t
                if (++guard > 500) { loops++; break }
            } else pos += 0.25
        }
    }
    println("videos ${byVideo.size}  raw segments $raw")
    println("accepted $kept  merged blocks $merged  (overlaps joined: ${kept - merged})")
    println("videos with nothing to skip: $videosEmpty")
    println("total skipped: ${"%.0f".format(savedS)}s = ${"%.1f".format(savedS/3600)}h")
    println("BACKWARD SEEKS: $backwards   NON-TERMINATING: $loops")
}
