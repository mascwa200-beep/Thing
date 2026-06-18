package dev.mascwa.pulse.core.telemetry

/**
 * A plain-English read of overall market breadth from a set of daily % changes — so the Markets screen
 * can say "leaning higher · 18 up · 5 down" at a glance instead of making you scan every row. Pure +
 * CI-tested. Breadth (how many are up vs down) is used rather than an average, since averaging a tiny
 * FX move against a big crypto swing would be misleading.
 */
object MarketMood {

    data class Mood(val headline: String, val detail: String)

    fun summarize(changesPct: List<Double>): Mood? {
        val changes = changesPct.filter { it.isFinite() }
        if (changes.isEmpty()) return null
        val up = changes.count { it > 0 }
        val down = changes.count { it < 0 }
        val flat = changes.size - up - down
        val total = changes.size
        val upShare = up.toDouble() / total
        val headline = when {
            upShare >= 0.70 -> "Risk-on — broadly higher"
            upShare >= 0.55 -> "Leaning higher"
            upShare <= 0.30 -> "Risk-off — broadly lower"
            upShare <= 0.45 -> "Leaning lower"
            else -> "Mixed / rangebound"
        }
        val detail = buildString {
            append("$up up · $down down")
            if (flat > 0) append(" · $flat flat")
            append(" of $total")
        }
        return Mood(headline, detail)
    }
}
