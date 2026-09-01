package dev.mascwa.pulse.core.telemetry

import java.io.File

/**
 * Does the mail setting become findable, and does the surface that already worked stay working?
 *
 * Runs the SHIPPED ranker over the SHIPPED strings: the category literals are parsed out of
 * SettingsCategory.kt and the section keyword strings out of SettingsScreen.kt, so this checks what
 * is in the tree rather than a paraphrase of it. Records are built exactly as DeviceSearchIndex
 * builds them (id/title/body), and `vis` is reproduced exactly — a plain substring contains over
 * "title keywords sectionKeywords".
 */
object MailFindProbe {

    private class Cat(val name: String, val title: String, val blurb: String, val keywords: String)

    @JvmStatic
    fun main(args: Array<String>) {
        val cats = File("scratchpad/mailfind/cats.tsv").readLines().filter { it.isNotBlank() }.map {
            val f = it.split("\t")
            Cat(f[0], f[1], f[2], f.getOrElse(4) { "" })
        }
        val sections = File("scratchpad/mailfind/content_sections.tsv").readLines().filter { it.isNotBlank() }
        check(cats.size == 10) { "expected 10 categories, got ${cats.size}" }
        check(sections.size == 5) { "expected 5 CONTENT sections, got ${sections.size}" }

        // ---- 1. tokenisation: both words must survive, as distinct tokens -------------------------
        println("== tokens ==")
        for (q in listOf("mail", "email", "unread mail", "my email", "how do I link my email")) {
            println("  ${q.padEnd(24)} -> ${GuideSearch.tokens(q)}")
        }

        // ---- 2. device search over the real category records --------------------------------------
        // Every MENU destination, exactly as DeviceSearchIndex.featureRecords builds them:
        // body = the entry's own description plus its search terms.
        val menu = File("scratchpad/mailfind/menu.tsv").readLines().filter { it.isNotBlank() }.map {
            val f = it.split("\t")
            DeviceSearch.of(
                id = "menu:${f[0]}",
                kind = DeviceSearch.RecordKind.FEATURE,
                title = f[0],
                body = "${f[1]} ${f.getOrElse(2) { "" }}",
            )
        }
        val records = menu + cats.map { c ->
            DeviceSearch.of(
                id = "settings?cat=${c.name.lowercase()}",
                kind = DeviceSearch.RecordKind.FEATURE,
                title = "Settings · ${c.title}",
                body = "${c.blurb} ${c.keywords}",
            )
        }
        println("\n== device search over ${records.size} FEATURE records (menu + settings categories) ==")
        for (q in listOf("email", "mail", "texts", "inbox", "unread mail", "sms", "mail settings")) {
            val hits = DeviceSearch.search(records, q, limit = 3)
            val shown = if (hits.isEmpty()) "NOTHING" else hits.joinToString(", ") {
                "${it.title}(${"%.1f".format(java.util.Locale.US, it.score)})"
            }
            println("  ${q.padEnd(14)} -> $shown")
        }

        // ---- 3. the in-Settings search must NOT regress --------------------------------------------
        // vis(cat, sectionKeywords) with a non-blank query, verbatim from SettingsScreen.kt:168.
        val content = cats.first { it.name == "CONTENT" }
        fun vis(sectionKeywords: String, q: String) =
            "${content.title} ${content.keywords} $sectionKeywords".contains(q, ignoreCase = true)

        println("\n== in-Settings search over the five CONTENT sections ==")
        for (q in listOf("mail", "email", "texts", "sms", "inbox", "watchlist", "rss")) {
            val n = sections.count { vis(it, q) }
            val which = sections.withIndex().filter { vis(it.value, q) }.joinToString(", ") {
                it.value.split(" ").first()
            }
            println("  ${q.padEnd(12)} -> $n of 5 ${if (n > 0) "[$which]" else ""}")
        }

        // ---- 4. the blurb budget -------------------------------------------------------------------
        println("\n== blurb widths (budget ~46ch at 10sp JetBrainsMono on a 360dp phone) ==")
        cats.sortedByDescending { it.blurb.length }.take(3).forEach {
            println("  ${it.name.padEnd(14)} ${it.blurb.length}ch  ${it.blurb}")
        }
    }
}
