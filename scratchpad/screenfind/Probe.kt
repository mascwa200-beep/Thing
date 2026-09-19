import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind
import dev.mascwa.pulse.core.telemetry.GuideSearch
import dev.mascwa.pulse.feature.settings.SettingsSections
import java.io.File

/**
 * Two questions about the phone's own search, measured with the SHIPPED code over the REAL corpus.
 *
 *  1. Where does a screen land when you type its own name, in the ONE FLAT LIST the phone renders?
 *  2. The phone puts a screen's synonyms in `summary` where the desktop puts them in `headings`.
 *     What does that cost — on screen, and in the ranking?
 *
 * Both sides are scored throughout, because any fix that lifts destinations necessarily pushes
 * readings down and that cost must be a measurement rather than a hope.
 */

private fun tsv(name: String) = File(name).readLines().filter { it.isNotBlank() }.map { it.split("\t") }

fun main() {
    val screens = tsv("features.tsv").map { Triple(it[0], it[1], it.getOrElse(2) { "" }) }
    require(screens.size >= 35) { "only ${screens.size} screens" }
    require(screens.any { it.second == "Search Survival" }) { "cannot see a known screen" }

    // features.tsv carries "pitch + terms" joined, as the shipped index builds it. Split them back
    // out using the directory's own term lists so the two arrangements can be compared honestly.
    val termsOf = tsv("terms.tsv").associate { it[0] to it.getOrElse(1) { "" } }
    require(termsOf.isNotEmpty()) { "no search terms extracted" }

    val guideRows = tsv("guides.tsv")
    require(guideRows.size > 800) { "only ${guideRows.size} guides" }
    val guides = guideRows.map { p ->
        DeviceSearch.Record(
            entry = GuideSearch.Entry(
                id = p[0], title = p[1], category = p.getOrElse(2) { "" },
                summary = p.getOrElse(3) { "" },
                headings = p.getOrElse(4) { "" }.split(" ").filter { it.isNotBlank() },
            ),
            kind = RecordKind.GUIDE,
        )
    }
    val settings = SettingsSections.searchRecords().map { (route, title, body) ->
        DeviceSearch.of(id = route, kind = RecordKind.SETTING, title = title, body = body)
    }
    require(settings.size >= 30) { "only ${settings.size} settings rows" }

    // AS SHIPPED: pitch and synonyms both in summary, so the row prints the synonyms.
    val shipped = screens.map { (route, label, body) ->
        DeviceSearch.of(route, RecordKind.FEATURE, label, body)
    }
    // PROPOSED: pitch in summary (what the row draws), synonyms in headings (matched, never drawn).
    val proposed = screens.map { (route, label, body) ->
        val terms = termsOf[route].orEmpty().split(" ").filter { it.isNotBlank() }
        val pitch = body.removeSuffix(" " + terms.joinToString(" ")).trim()
        DeviceSearch.Record(
            entry = GuideSearch.Entry(
                id = route, title = label, category = RecordKind.FEATURE.label,
                summary = pitch, headings = terms,
            ),
            kind = RecordKind.FEATURE,
        )
    }

    fun hits(feats: List<DeviceSearch.Record>, q: String) =
        DeviceSearch.search(feats + settings + guides, q, limit = 12, perKind = 4)
    fun isDest(k: RecordKind) = k == RecordKind.FEATURE || k == RecordKind.SETTING

    println("=== ${screens.size} screens + ${settings.size} settings + ${guides.size} guides ===")

    // ---- 1. what the row PRINTS today -------------------------------------------------------
    println("\n1. WHAT THE RESULT ROW PRINTS TODAY (DeviceResultRow draws entry.summary)")
    screens.filter { termsOf[it.first].orEmpty().isNotBlank() }.take(5).forEach { (route, label, body) ->
        println("   $label:")
        println("      \"${body.take(150)}\"")
    }

    // ---- 2. does moving synonyms to headings change the ranking? ----------------------------
    println("\n2. SYNONYM QUERIES — a word that is ONLY in a screen's term list")
    var better = 0; var same = 0; var worse = 0
    val moved = mutableListOf<String>()
    for ((route, label, _) in screens) {
        val terms = termsOf[route].orEmpty().split(" ").filter { it.isNotBlank() }
        for (w in terms.distinct()) {
            if (GuideSearch.tokens(label).contains(w)) continue   // in the title; not a synonym test
            val a = hits(shipped, w).indexOfFirst { it.id == route }
            val b = hits(proposed, w).indexOfFirst { it.id == route }
            val ra = if (a < 0) 99 else a
            val rb = if (b < 0) 99 else b
            when {
                rb < ra -> { better++; moved += "   \"$w\" -> $route: #${ra + 1} => #${rb + 1}" }
                rb > ra -> { worse++; moved += "   ⚠ \"$w\" -> $route: #${ra + 1} => #${rb + 1} WORSE" }
                else -> same++
            }
        }
    }
    println("   better: $better   unchanged: $same   worse: $worse")
    moved.filter { it.contains("WORSE") }.forEach { println(it) }
    moved.filterNot { it.contains("WORSE") }.take(14).forEach { println(it) }

    // ---- 3. the label burial, under both arrangements ---------------------------------------
    println("\n3. A SCREEN'S OWN FULL LABEL, in the flat list")
    for ((tag, feats) in listOf("shipped" to shipped, "proposed" to proposed)) {
        var absent = 0; var notFirst = 0
        for ((route, label, _) in screens) {
            val at = hits(feats, label).indexOfFirst { it.id == route }
            if (at < 0) absent++ else if (at > 0) notFirst++
        }
        println("   $tag: absent $absent, not first $notFirst  (of ${screens.size})")
    }

    // ---- 4. the cost of a destinations-first block, over the whole reading vocabulary --------
    println("\n4. COST of rendering destinations above the readings")
    val vocab = guideRows.flatMap { GuideSearch.tokens(it[1]) }.distinct()
    val dist = HashMap<Int, Int>()
    for (w in vocab) dist.merge(hits(proposed, w).count { isDest(it.kind) }, 1, Int::plus)
    println("   over ${vocab.size} distinct guide-title words:")
    dist.toSortedMap().forEach { (n, c) ->
        println("      $n destination row(s) above the readings: $c (${c * 100 / vocab.size}%)")
    }
}
