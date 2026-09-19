import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.DeviceSearch.RecordKind
import dev.mascwa.pulse.core.telemetry.GuideSearch
import java.io.File

// Runs the SHIPPED DeviceSearch/GuideSearch over the REAL desktop screen corpus, twice:
//   BEFORE   DESK_GROUPS exactly as it stands
//   AFTER    ...with the proposed searchTerms on the Settings entry
// and answers the one question that decides whether the additions are safe: does a screen that
// OWNS a word lose the top spot to Settings?
//
// ⚠️ This corpus is screens only, deliberately. DesktopSearchIndex.screenRecords() is searched
// SEPARATELY from the library (its own KDoc explains why), so a screen competes with screens and
// with nothing else. Mixing the guides in here would measure a competition that does not happen.

private fun tsv(name: String) =
    File(name).readLines().filter { it.isNotBlank() }.map { it.split("\t") }

// Mirrors DesktopSearchIndex.screenRecords() field for field: the terms go in `headings`, never
// in `summary`. Putting them in the wrong field here would measure a ranking the app never does.
private fun rec(id: String, group: String, label: String, desc: String, terms: String) =
    DeviceSearch.Record(
        entry = GuideSearch.Entry(
            id = id,
            title = label,
            category = group,
            summary = desc,
            headings = terms.split(" ").filter { it.isNotBlank() },
        ),
        kind = RecordKind.FEATURE,
    )

fun main() {
    val rows = tsv("screens.tsv")            // screen, group, label, description, terms
    check(rows.size >= 27) { "only ${rows.size} screens parsed" }
    val added = File("added.txt").readText().trim()
    check(added.isNotBlank()) { "no proposed terms" }

    val before = rows.map { rec(it[0], it[1], it[2], it[3], it[4]) }
    val after = rows.map {
        val terms = if (it[0] == "SETTINGS") (it[4] + " " + added).trim() else it[4]
        rec(it[0], it[1], it[2], it[3], terms)
    }

    // Self-check: silence means nothing until the harness is known to find something.
    val probe = DeviceSearch.search(before, "radio")
    check(probe.isNotEmpty() && probe.first().title == "Radio") {
        "harness self-check failed: 'radio' did not return Radio (got ${probe.firstOrNull()?.title})"
    }
    println("self-check passed: 'radio' -> '${probe.first().title}'\n")

    // Every word a screen says about itself, across label + description + terms.
    val vocab = rows.flatMap { (it[2] + " " + it[3] + " " + it[4]).lowercase().split(Regex("[^a-z0-9]+")) }
        .filter { it.length > 2 }.distinct().sorted()
    println("sweeping ${vocab.size} words a screen says about itself")

    // ⚠️ The bar is OWNERSHIP, not "did the top result change". A word Settings genuinely owns
    // (`units`) is allowed to lead — that is the point. What is never right is Settings beating
    // the screen whose own LABEL is the query.
    fun titleHas(t: String, w: String) = t.lowercase().split(Regex("[^a-z0-9]+")).any { it == w }

    var flips = 0
    var evicted = 0
    val problems = ArrayList<String>()
    for (w in vocab) {
        val b = DeviceSearch.search(before, w)
        val a = DeviceSearch.search(after, w)

        val named = b.firstOrNull { titleHas(it.title, w) }
        val aTop = a.firstOrNull()
        if (named != null && aTop != null && aTop.id == "SETTINGS" && named.id != "SETTINGS") {
            flips++
            problems += "  TOP FLIP  $w: '${named.title}' -> 'Settings'"
        }
        // A screen can go 2nd -> 5th, i.e. off the capped slate, with the top result unchanged.
        val lost = b.map { it.id }.filterNot { it in a.map { r -> r.id } }
        if (lost.isNotEmpty()) {
            evicted++
            problems += "  EVICTED   $w: ${lost.joinToString()}"
        }
    }
    println("  screens losing the top spot to Settings: $flips")
    println("  screens evicted from the slate: $evicted")
    problems.take(30).forEach(::println)
    println()

    // The point of the change: the words a Settings control says about itself must reach Settings.
    val want = File("wantwords.tsv").readLines().filter { it.isNotBlank() }
    var reach = 0
    val missed = ArrayList<String>()
    for (w in want) {
        if (DeviceSearch.search(after, w).any { it.id == "SETTINGS" }) reach++ else missed += w
    }
    println("CONTROL WORDS (${want.size} taken from real control and section names):")
    println("  reach Settings now: $reach")
    println("  still do not: ${missed.size}" + if (missed.isNotEmpty()) " — ${missed.joinToString()}" else "")

    // And the ones deliberately NOT taken must still lead where they led before.
    //
    // ⚠️ Scored against BOTH corpora, because "who should own this word" is my opinion and "did
    // this change move it" is the measurement. My first version only ran `after` and reported
    // `watch` as a MISS — it goes to Anomalies, whose own `long watch` term tokenises to `long`
    // and `watch`, so Anomalies owned it before this change and still does. That is a pre-existing
    // ambiguity between two screens, and reporting it as a regression here would have sent me
    // hunting a defect my diff cannot have caused. Only a word that MOVES is this change's doing.
    println()
    println("WORDS DELIBERATELY LEFT WITH THEIR OWNER (before -> after):")
    for (q in listOf(
        "long watch", "watch", "channels", "library", "build", "update",
        "download", "fault", "record", "log", "where", "location",
    )) {
        val wasTop = DeviceSearch.search(before, q).firstOrNull()?.title
        val nowTop = DeviceSearch.search(after, q).firstOrNull()?.title
        val moved = wasTop != nowTop
        println("  ${if (moved) "MOVED" else "same "}  '$q': '$wasTop' -> '$nowTop'")
        if (moved) problems += "  OWNER MOVED  $q: $wasTop -> $nowTop"
    }

    println()
    println(if (problems.isEmpty()) "VERDICT: nothing lost its place." else "VERDICT: PROBLEMS ABOVE.")
}
