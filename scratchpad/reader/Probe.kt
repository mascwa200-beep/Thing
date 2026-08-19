import dev.mascwa.pulse.core.telemetry.Readability
import java.io.File

/**
 * Run the SHIPPED decimator over real fetched pages.
 *
 * The lesson this repo keeps relearning: unit tests on hand-written fixtures pass while the real
 * thing returns nonsense (GuideSearch answered "treating a snake bite" with a guide on depression,
 * and only a run over the real index showed it). So the gate for Readability is a spread of real
 * pages — wire services, a public broadcaster, a wiki, a docs page, a book, plus the failure cases
 * that matter: an index page, a 401, and a Google News redirect stub.

 * The index-vs-article discriminator was measured separately and NOT shipped — the ranges overlap
 * completely, so no threshold exists. See Readability's class KDoc.
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull { !it.startsWith("--") } ?: "/tmp/reader/pages")
    val bases = mapOf(
        "art_bbc" to "https://www.bbc.com/news/articles/crl7yjlpx2po",
        "art_ap" to "https://apnews.com/article/frank-beard-died-zz-top-obit",
        "art_lwn" to "https://lwn.net/Articles/1089501/",
        "art_wiki" to "https://en.wikipedia.org/wiki/Espresso",
        "art_gutn" to "https://www.gutenberg.org/cache/epub/1342/pg1342-images.html",
        "art_mdn" to "https://developer.mozilla.org/en-US/docs/Web/HTML/Element/article",
        "art_php" to "https://www.propublica.org/",
        "bbc" to "https://www.bbc.com/news",
        "ap" to "https://apnews.com/",
        "npr" to "https://text.npr.org/",
        "hn" to "https://news.ycombinator.com/",
        "kernel" to "https://lwn.net/Articles/",
        "reuters" to "https://www.reuters.com/world/",
        "gnews" to "https://news.google.com/rss/articles/CBMitest",
        "art_npr" to "https://text.npr.org/nx-s1-5301234",
    )

    // Strings that must never survive into a body. Each is real chrome from one of these sites.
    // ⚠️ Unambiguous chrome only. The first cut listed "Advertisement" and "Sign in" and both fired
    // on real prose — Wikipedia's espresso article describes a 1922 ADVERTISEMENT for a machine.
    // That is the same substring trap the extractor's own word list is guarded against, reproduced
    // in the harness meant to check it.
    val leaks = listOf(
        "Skip to content", "Skip to main content", "Accept all cookies", "Most read",
        "Sign up for our newsletter", "All rights reserved", "Terms of Use", "Privacy Policy",
        "Share on Facebook", "Related stories",
    )

    // Which pages are genuinely articles, so the run reports right/wrong rather than just a table.
    val truth = setOf("art_bbc", "art_ap", "art_lwn", "art_wiki", "art_gutn", "art_mdn")

    val files = dir.listFiles { f -> f.name.endsWith(".html") }?.sortedBy { it.name } ?: emptyList()

    var right = 0
    var wrong = 0
    println("%-12s %-12s %-9s %6s  %s".format("PAGE", "OUTCOME", "STRATEGY", "WORDS", "TITLE / NOTE"))
    println("-".repeat(104))
    for (f in files) {
        val stem = f.name.removeSuffix(".html")
        val base = bases[stem] ?: "https://example.com/$stem"
        val e = Readability.extract(f.readText(), base)
        val expected = stem in truth
        val ok = e.isArticle == expected
        if (ok) right++ else wrong++
        val head = e.meta.title?.take(50) ?: e.note?.take(50) ?: "-"
        println("%-12s %-12s %-9s %6d  %s %s".format(
            stem, e.outcome, e.strategy, e.wordCount, if (ok) " " else "WRONG", head))
        if (e.isArticle) {
            val text = Readability.plainText(e)
            val found = leaks.filter { text.contains(it, ignoreCase = true) }
            val first = e.blocks.filterIsInstance<Readability.Block.Paragraph>().firstOrNull()?.text
            println("             by=${e.meta.byline ?: "-"}  when=${e.meta.publishedIso ?: "-"}  blocks=${e.blocks.size}${if (e.truncated) " TRUNCATED" else ""}")
            println("             first: ${first?.take(92) ?: "(no paragraph)"}")
            if (found.isNotEmpty()) println("             ! LEAKED CHROME: $found")
        } else {
            println("             note: ${e.note}")
        }
    }
    println("-".repeat(104))
    println("correct: $right   wrong: $wrong")
}
