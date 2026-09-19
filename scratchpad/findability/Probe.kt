import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind
import java.io.File

// Runs the SHIPPED DeviceSearch/GuideSearch over the REAL corpus, three ways:
//   BEFORE   features + the 10 Settings categories, all as FEATURE (the tree before this change)
//   NAIVE    ...plus the 26 sections, ALSO as FEATURE (the design the audit rejected)
//   AFTER    features as FEATURE; categories and sections as SETTING (what shipped)
// and answers the two questions that matter: does a real screen ever lose its place, and do the
// words that reached nothing now reach something.

private fun tsv(name: String) = File(name).readLines().filter { it.isNotBlank() }.map { it.split("\t") }

private fun rec(id: String, kind: RecordKind, title: String, body: String) =
    DeviceSearch.of(id = id, kind = kind, title = title, body = body)

fun main() {
    val feats = tsv("features.tsv")          // route, label, pitch+terms
    val catsRaw = tsv("cats_raw.tsv")        // the 10 category rows as they were: body unfiltered
    val secsRaw = tsv("sections_raw.tsv")    // the naive sections: body = title + keywords
    val settings = tsv("settings_after.tsv") // what searchRecords() emits now: body title-filtered
    check(feats.size >= 35) { "only ${feats.size} features parsed" }
    check(catsRaw.size == 10) { "expected 10 categories, got ${catsRaw.size}" }
    check(secsRaw.size == 23) { "expected 23 emitted sections, got ${secsRaw.size}" }
    check(settings.size == 33) { "expected 10 categories + 23 sections, got ${settings.size}" }

    val featureRecs = feats.map { rec(it[0], RecordKind.FEATURE, it[1], it[2]) }

    // BEFORE: the tree as it was — features and the 10 category rows, all FEATURE, bodies unfiltered.
    val before = featureRecs + catsRaw.map { rec(it[0], RecordKind.FEATURE, it[1], it[2]) }
    // NAIVE: the design the audit rejected — sections added as FEATURE, nothing de-duplicated.
    val naive = before + secsRaw.map { rec(it[0], RecordKind.FEATURE, it[1], it[2]) }
    // AFTER: what shipped — one SETTING kind, one body rule, applied to categories and sections alike.
    val after = featureRecs + settings.map { rec(it[0], RecordKind.SETTING, it[1], it[2]) }

    // Self-check: the harness must be able to SEE a known result before its silence means anything.
    val probe = DeviceSearch.search(before, "radar")
    check(probe.isNotEmpty() && probe.first().title.contains("Radar", true)) {
        "harness self-check failed: 'radar' did not return the radar from the BEFORE corpus"
    }
    println("self-check passed: 'radar' -> '${probe.first().title}'")

    // Every word a real screen says about itself. This is the sweep the plan demands, in place of
    // a hand-picked control list that mostly measures cases that cannot fail.
    val vocab = feats.flatMap { (it[1] + " " + it[2]).lowercase().split(Regex("[^a-z0-9]+")) }
        .filter { it.length > 2 }.distinct().sorted()
    println("sweeping ${vocab.size} words a real feature says about itself\n")

    // ⚠️ A "real screen" is identified by its ROUTE, not by its kind. Judging by kind counted the
    // 10 Settings CATEGORY rows as screens in the BEFORE corpus (they were FEATURE there) and then
    // reported them "evicted" when they correctly moved to SETTING — 44 phantom regressions, the
    // harness accusing the change of doing exactly what it was supposed to do.
    val realIds = feats.map { it[0] }.toSet()
    fun screens(rs: List<DeviceSearch.Result>) = rs.filter { it.id in realIds }.map { it.title }
    fun isScreen(r: DeviceSearch.Result?) = r != null && r.id in realIds
    fun titleHas(t: String, w: String) =
        t.lowercase().split(Regex("[^a-z0-9]+")).any { it == w }

    // Score the same query against both corpora and print the top few, so a disagreement can be
    // diagnosed from numbers rather than argued about.
    fun explain(label: String, rs: List<DeviceSearch.Record>, q: String) {
        println("  $label '$q':")
        DeviceSearch.search(rs, q).take(4).forEach {
            println("      %7.3f  %-38s %s".format(it.score, it.title, it.kind))
        }
    }

    var naiveFlips = 0
    var afterFlips = 0
    var afterEvictions = 0
    val naiveExamples = ArrayList<String>()
    val afterProblems = ArrayList<String>()

    for (w in vocab) {
        val b = DeviceSearch.search(before, w)
        val n = DeviceSearch.search(naive, w)
        val a = DeviceSearch.search(after, w)

        // 1. Did a real screen whose OWN NAME matches lose the top spot to a Settings row?
        //
        // ⚠️ The bar is title-vs-title, and the blunter "did the top result change at all" is the
        // WRONG bar: "viewscreen" returning 'Settings · Viewscreen' above Theater is correct, since
        // the setting is literally called that and Theater only matches in its description. What is
        // never right is a row named for a setting beating a screen named for the query.
        val namedScreen = b.firstOrNull { r -> isScreen(r) && titleHas(r.title, w) }
        if (namedScreen != null) {
            // ⚠️ And the displacement only counts if it sends you somewhere ELSE. For the query
            // "settings" the winning row is 'Settings · System', whose route IS Settings — the
            // screen you asked for, opened at a category. Nothing is mis-navigated, so calling that
            // a regression would be counting a cosmetic re-ordering as a wrong destination.
            fun elsewhere(r: DeviceSearch.Result) =
                !isScreen(r) && r.id.substringBefore('?') != namedScreen.id.substringBefore('?')

            val nTop = n.firstOrNull()
            if (nTop != null && elsewhere(nTop)) {
                naiveFlips++
                if (naiveExamples.size < 20) naiveExamples += "  $w: '${namedScreen.title}' -> '${nTop.title}'"
            }
            val aTop = a.firstOrNull()
            if (aTop != null && elsewhere(aTop)) {
                afterFlips++
                afterProblems += "  TOP FLIP  $w: '${namedScreen.title}' -> '${aTop.title}'"
            }
        }
        // 2. Did any real screen fall OFF the slate? (a 2nd -> 5th move is invisible to a top-1 check)
        val lost = screens(b).filterNot { it in screens(a) }
        if (lost.isNotEmpty()) {
            afterEvictions++
            afterProblems += "  EVICTED   $w: ${lost.joinToString()}"
        }
    }

    println("NAIVE (sections as FEATURE, the rejected design):")
    println("  real screens losing the top spot: $naiveFlips")
    naiveExamples.forEach(::println)
    println()
    println("AFTER (sections as SETTING, what shipped):")
    println("  real screens losing the top spot: $afterFlips")
    println("  real screens evicted from the slate: $afterEvictions")
    afterProblems.take(20).forEach(::println)
    println()

    // 3. The point of the change: words that reached nothing must now reach a Settings row.
    val gap = File("gapwords.tsv").readLines().filter { it.isNotBlank() }
    var reached = 0
    val stillNothing = ArrayList<String>()
    for (w in gap) {
        val b = DeviceSearch.search(before, w)
        val a = DeviceSearch.search(after, w)
        val nowSetting = a.any { it.kind == RecordKind.SETTING }
        if (b.isEmpty() && nowSetting) reached++ else if (!nowSetting) stillNothing += w
    }
    // ⚠️ Three buckets, not two. `reached` counts only words that returned LITERALLY NOTHING
    // before, so a word that already returned a screen and now also returns a Settings row falls
    // in neither — and printing just "80 of 90" read as ten failures when there were none.
    println("GAP WORDS (${gap.size} measured as reaching no Settings destination):")
    println("  returned nothing before, reach a Settings row now: $reached")
    println("  already returned something, reach a Settings row now: ${gap.size - reached - stillNothing.size}")
    println("  STILL reach no Settings row: ${stillNothing.size}" +
        if (stillNothing.isNotEmpty()) " — ${stillNothing.joinToString()}" else "")

    println()
    println()
    println("DIAGNOSTICS (scores, so a disagreement is settled by numbers):")
    for (q in listOf("settings", "markets", "sponsorblock")) {
        explain("BEFORE", before, q); explain("AFTER ", after, q)
    }

    println()
    println(if (afterFlips == 0 && afterEvictions == 0) "VERDICT: no named screen lost its place." else "VERDICT: REGRESSIONS ABOVE.")
}
