package dev.mascwa.pulse.core.telemetry

/**
 * The plain-English, tap-to-explain methodology note for the News tab's MARKET REACTION + IMPACT block.
 * Pure + CI-tested, mirrors [MarketExplainers]/[SpaceWeatherExplainers]'s shape, and explicit about what it
 * does NOT claim — a keyword read of a headline's own wording, never advice and never a market-data feed.
 *
 * ⚠️ This file used to hold three more: `mood`, `bias` and `buzz`, each explaining a coloured bar under a
 * story. All three bars were removed — a strip of colour is not a fact, and an explainer that has to be
 * opened before its own graphic means anything is evidence the graphic was not carrying meaning. What
 * replaced them says the facts in words (which outlets, how many, whether it is live on social), so there is
 * nothing left to explain. Their explainers went with them rather than staying as computed-and-never-read
 * text; this repo does not keep those.
 */
object NewsExplainers {

    /** What the MARKET REACTION + IMPACT strip means and its honest limits — not advice. */
    fun market(impact: ImpactLevel, links: List<MarketLink>): Explainer {
        val coverage = if (links.isEmpty()) {
            "No market ties were found in this story's own wording."
        } else {
            "This story's wording ties to: ${links.joinToString(", ") { it.market }}."
        }
        val headline = if (impact == ImpactLevel.NONE) {
            "MARKET REACTION + IMPACT — what this measures"
        } else {
            "MARKET REACTION + IMPACT — ${impact.label.lowercase()} impact"
        }
        return Explainer(
            headline,
            "$coverage LCARS matches the headline's own words against a fixed list of ~40 markets and sectors, " +
                "then reads whether the wording states or clearly implies a move up or down — the same " +
                "\"reality moves the market\" read as Trading Places, done with keyword matching, not financial " +
                "modeling. A ▲/▼ shown WITH a percentage is a real live quote; a bare ▲/▼ is only the heuristic " +
                "direction. When a longer desk-note paragraph appears here, it is written by the cloud model " +
                "reasoning from those same shown quotes — the only real prints it is given — not from a live " +
                "market-data feed. This is not financial advice — treat it as \"markets worth watching,\" never " +
                "a signal to act on.",
        )
    }
}
