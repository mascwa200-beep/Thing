// ADAPTED PORT of core/telemetry/.../NewsExplainers.kt — deliberately NOT a strict mirror, and so
// deliberately absent from tools/mirror_desktop_cores.py. The Android copy names the SOCIAL tabs
// (Lemmy / Hacker News / Mastodon) and the cloud desk-note the analysis engine writes; the desktop
// has neither, and describing features it does not have would be worse than differing. Keep the
// THRESHOLDS in step with the Android original by hand; the wording is meant to differ.
package dev.mascwa.pulse.desktop.telemetry

/**
 * The plain-English, tap-to-explain methodology note for the News tab's MARKET REACTION + IMPACT block.
 * Explicit about what it does NOT claim — a keyword read of a headline's own wording, never advice.
 *
 * ⚠️ This file used to hold three more: `mood`, `bias` and `buzz`, each explaining a coloured bar under a
 * story. All three bars were removed on both platforms — a strip of colour is not a fact, and an explainer
 * that has to be opened before its own graphic means anything is evidence the graphic was not carrying
 * meaning. What replaced them says the facts in words, so there is nothing left to explain.
 */
object NewsExplainers {

    /** What the MARKET REACTION + IMPACT strip means and its honest limits — a headline-reading heuristic,
     *  not advice. (The desktop has no cloud analysis engine, so unlike the Android copy this stays purely
     *  heuristic — same card title on both platforms, honest about the thinner desktop read.) */
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
                "direction. This is not financial advice — treat it as \"markets worth watching,\" never a " +
                "signal to act on.",
        )
    }
}
