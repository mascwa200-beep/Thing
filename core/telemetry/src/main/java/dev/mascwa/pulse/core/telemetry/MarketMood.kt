package dev.mascwa.pulse.core.telemetry

/**
 * A plain-English read of overall market breadth from a set of daily % changes — so the Markets screen
 * can say "leaning higher · 18 up · 5 down" at a glance instead of making you scan every row. Pure +
 * CI-tested. Breadth (how many are up vs down) is used rather than an average, since averaging a tiny
 * FX move against a big crypto swing would be misleading.
 */
object MarketMood {

    /** [headline] is a short, plain summary; [plain] is a full-sentence explanation anyone can follow;
     *  [detail] is the raw count (X up · Y down of N). The remaining fields expose the underlying
     *  breadth so callers (e.g. the HUD / live wallpaper) don't have to recount: [up]/[down]/[flat] of
     *  [total], the [upShare] fraction, and [netChangePct] — the equal-weighted average of the (finite)
     *  daily % changes, a raw numeric readout only. It is deliberately NOT used to choose the mood
     *  (see the class doc on why averaging FX against crypto would mislead). */
    data class Mood(
        val headline: String,
        val detail: String,
        val plain: String,
        val up: Int = 0,
        val down: Int = 0,
        val flat: Int = 0,
        val total: Int = 0,
        val upShare: Double = 0.0,
        val netChangePct: Double = 0.0,
    )

    fun summarize(changesPct: List<Double>): Mood? {
        val changes = changesPct.filter { it.isFinite() }
        if (changes.isEmpty()) return null
        val up = changes.count { it > 0 }
        val down = changes.count { it < 0 }
        val flat = changes.size - up - down
        val total = changes.size
        val upShare = up.toDouble() / total
        val netChangePct = changes.average()
        // Plain English, no trading jargon (no "risk-on"/"rangebound") — a non-investor should get it.
        val (headline, plain) = when {
            upShare >= 0.70 ->
                "Mostly up today" to "Most of the markets we track are worth more than they were yesterday."
            upShare >= 0.55 ->
                "More up than down" to "A few more markets are higher today than are lower."
            upShare <= 0.30 ->
                "Mostly down today" to "Most of the markets we track are worth less than they were yesterday."
            upShare <= 0.45 ->
                "More down than up" to "A few more markets are lower today than are higher."
            else ->
                "Mixed — up and down" to "It's an even split — about as many markets are up as are down today."
        }
        val detail = buildString {
            append("$up up · $down down")
            if (flat > 0) append(" · $flat flat")
            append(" of $total")
        }
        return Mood(headline, detail, plain, up, down, flat, total, upShare, netChangePct)
    }
}
