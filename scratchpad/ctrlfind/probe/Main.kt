import dev.mascwa.pulse.feature.settings.SettingsSections
fun main() {
    val rows = SettingsSections.searchRecords()
    println("rows: ${rows.size}   (10 categories + 23 sections = 33)")
    val body = { t: String -> rows.first { it.second == t }.third }
    // The fold: a skipped section's vocabulary must ride on its category row.
    // ⚠️ Both words are in NEITHER their category's title, blurb nor keywords, so the fold is the
    // only route. `country` was the first thing I reached for and is a case that CANNOT fail — it
    // sits in REGION's blurb, so the category row carries it either way.
    for ((row, word) in listOf("Settings · Notifications" to "agenda",
                               "Settings · Safety (SOS)" to "allergies")) {
        val has = body(row).contains(word)
        println("  ${if (has) "ok  " else "MISS"} $row  carries '$word'")
        check(has) { "the fold dropped '$word'" }
    }
    // And the title filter still cancels a word the title already claims.
    check(!body("Settings · Notifications").contains("notifications")) { "title filter stopped working" }
    println("  ok   the title filter still cancels a word the title says")
}
