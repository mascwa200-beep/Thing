// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/MediaBias.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

/**
 * A widely-recognized political lean for a news outlet, using AllSides' own public 5-bucket taxonomy — the
 * same vocabulary Ground News surfaces — so a "COVERAGE" strip can show how a story's outlets spread across
 * the spectrum. Substring-matched against outlet-name strings (e.g. `Article.source`), mirroring
 * `BreakingCoverageRepository.TRUSTED`'s existing matching style; that list is trustworthiness, a DIFFERENT
 * axis from lean — an outlet can be trusted AND have a lean, both render independently.
 *
 * Only outlets with a genuinely uncontroversial, widely-cited public classification are listed here; anything
 * else is [MediaBias.leanOf] == null ("Unrated") rather than guessed — the same "never guess" discipline this
 * codebase applies to every other heuristic classification. This is one honest read among several possible
 * ones, most visibly on the Wall Street Journal: its opinion page is widely seen as Lean Right while its news
 * desk is closer to Center, but only a flat outlet name is available here, so the whole outlet is classified
 * Lean Right, matching AllSides' general placement — the single most arguable entry in the table.
 */
enum class Lean(val label: String) {
    LEFT("Left"), LEAN_LEFT("Lean Left"), CENTER("Center"), LEAN_RIGHT("Lean Right"), RIGHT("Right"),
}

/** How many outlets covering one story fall in each [Lean] bucket, plus how many couldn't be classified. */
data class BiasBreakdown(
    val left: Int = 0,
    val leanLeft: Int = 0,
    val center: Int = 0,
    val leanRight: Int = 0,
    val right: Int = 0,
    val unrated: Int = 0,
) {
    val rated: Int get() = left + leanLeft + center + leanRight + right
    val total: Int get() = rated + unrated
}

object MediaBias {

    private val LEFT_OUTLETS = listOf(
        "mother jones", "the nation", "huffpost", "huffington post", "msnbc", "the daily beast",
        "jacobin", "salon", "vox", "slate", "the intercept", "alternet", "raw story", "occupy democrats",
    )
    private val LEAN_LEFT_OUTLETS = listOf(
        "new york times", "washington post", "cnn", "the guardian", "npr", "pbs", "abc news", "cbs news",
        "nbc news", "politico", "the atlantic", "time magazine", "vanity fair", "the new yorker",
        "bloomberg", "business insider", "axios", "the independent", "the hill", "propublica", "usa today",
    )
    private val CENTER_OUTLETS = listOf(
        "reuters", "associated press", "ap news", "bbc", "afp", "the economist", "financial times",
        "c-span", "newsnation", "real clear politics",
    )
    private val LEAN_RIGHT_OUTLETS = listOf(
        "fox business", "new york post", "washington examiner", "the dispatch", "national review",
        "wall street journal", "washington times", "reason",
    )
    private val RIGHT_OUTLETS = listOf(
        "fox news", "breitbart", "the daily wire", "newsmax", "the daily caller", "the federalist",
        "oann", "one america news", "the blaze", "townhall", "the epoch times", "american thinker",
    )

    private val TABLE: List<Pair<Lean, List<String>>> = listOf(
        Lean.LEFT to LEFT_OUTLETS,
        Lean.LEAN_LEFT to LEAN_LEFT_OUTLETS,
        Lean.CENTER to CENTER_OUTLETS,
        Lean.LEAN_RIGHT to LEAN_RIGHT_OUTLETS,
        Lean.RIGHT to RIGHT_OUTLETS,
    )

    /** The outlet's widely-recognized lean, or null if it isn't in the table ("Unrated" — never guessed).
     *  If a source string happens to match names in more than one bucket (not the case with today's table,
     *  but defensive against future additions), the LONGEST matched name wins — the more specific match. */
    fun leanOf(source: String): Lean? {
        if (source.isBlank()) return null
        val s = source.lowercase()
        return TABLE
            .flatMap { (lean, names) -> names.filter { s.contains(it) }.map { it to lean } }
            .maxByOrNull { it.first.length }
            ?.second
    }

    /** Tallies how a story's [sources] (outlet names) spread across the political spectrum. */
    fun breakdown(sources: List<String>): BiasBreakdown {
        var left = 0; var leanLeft = 0; var center = 0; var leanRight = 0; var right = 0; var unrated = 0
        for (s in sources) {
            when (leanOf(s)) {
                Lean.LEFT -> left++
                Lean.LEAN_LEFT -> leanLeft++
                Lean.CENTER -> center++
                Lean.LEAN_RIGHT -> leanRight++
                Lean.RIGHT -> right++
                null -> unrated++
            }
        }
        return BiasBreakdown(left, leanLeft, center, leanRight, right, unrated)
    }

    /** A plain-English read of the spread, e.g. "6 of 9 rated outlets lean left, 2 lean right — you're
     *  seeing one side a lot more than the other." A non-political-junkie can follow it, matching this
     *  codebase's established "plain English, no jargon" voice (see [MarketMood.summarize]). */
    fun summarize(b: BiasBreakdown): String {
        if (b.rated == 0) return if (b.total == 0) "" else "No outlet in this coverage has a known lean yet."
        val leftLeaning = b.left + b.leanLeft
        val rightLeaning = b.right + b.leanRight
        val total = b.rated
        val outlets = if (total == 1) "outlet" else "outlets"
        return when {
            leftLeaning == 0 && rightLeaning == 0 ->
                "All $total rated $outlets lean center — a balanced spread."
            leftLeaning > 0 && rightLeaning == 0 ->
                "$leftLeaning of $total rated $outlets lean left, none lean right — you're only seeing one side."
            rightLeaning > 0 && leftLeaning == 0 ->
                "$rightLeaning of $total rated $outlets lean right, none lean left — you're only seeing one side."
            leftLeaning > rightLeaning * 2 ->
                "$leftLeaning of $total rated $outlets lean left, $rightLeaning lean right — you're seeing one side a lot more than the other."
            rightLeaning > leftLeaning * 2 ->
                "$rightLeaning of $total rated $outlets lean right, $leftLeaning lean left — you're seeing one side a lot more than the other."
            else ->
                "A fairly even spread: $leftLeaning lean left, ${b.center} center, $rightLeaning lean right."
        }
    }
}
