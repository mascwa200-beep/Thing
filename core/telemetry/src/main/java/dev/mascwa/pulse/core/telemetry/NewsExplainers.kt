package dev.mascwa.pulse.core.telemetry

/**
 * Plain-English, tap-to-explain methodology notes for the News tab's at-a-glance strips (MOOD, COVERAGE,
 * BUZZ) — so none of them stay an unexplained colored bar. Pure + CI-tested, mirrors [MarketExplainers]/
 * [SpaceWeatherExplainers]'s shape. Every explainer is explicit about what it does NOT claim — a heuristic
 * reading of coverage/outlets/chatter, never a judgment on whether the underlying news is good or bad.
 */
object NewsExplainers {

    /** What the MOOD bar's colors mean and how they're derived — the fix for "mood is so one-dimensional":
     *  it's already 3 separate counts (positive/negative/tense), not one score, and here's what each is. */
    fun mood(b: ToneBreakdown): Explainer {
        val bits = buildList {
            if (b.positive > 0) add("${b.positive} upbeat word${if (b.positive == 1) "" else "s"} (green)")
            if (b.negative > 0) add("${b.negative} downbeat word${if (b.negative == 1) "" else "s"} (orange)")
            if (b.tense > 0) add("${b.tense} tense/conflict word${if (b.tense == 1) "" else "s"} (red)")
        }
        val breakdown = if (bits.isEmpty()) {
            "No strongly charged words were found in this story's own headline and summary."
        } else {
            "Right now: " + bits.joinToString(", ") + "."
        }
        return Explainer(
            "MOOD — three counts, not one score",
            "$breakdown MOOD reads the actual words the coverage uses — surge/plunge, breakthrough/crisis, " +
                "and so on — and shows all three counts side by side rather than collapsing them into a " +
                "single word. It measures the TONE of the writing, not whether the news is good or bad for " +
                "you: a story can read \"tense\" and still be good news for someone, or read \"upbeat\" about " +
                "something that doesn't affect you at all.",
        )
    }

    /** What the COVERAGE bias bar means, how it's built, and its honest limits. */
    fun bias(b: BiasBreakdown): Explainer {
        val verb = if (b.rated == 1) "has" else "have"
        val coverage = if (b.total == 0) {
            "No other outlets covering this exact story were found."
        } else {
            "${b.rated} of ${b.total} outlet${if (b.total == 1) "" else "s"} covering this story $verb a known lean" +
                if (b.unrated > 0) "; ${b.unrated} ${if (b.unrated == 1) "isn't" else "aren't"} in our table yet." else "."
        }
        return Explainer(
            "COVERAGE — who else is reporting this, and how they lean",
            "$coverage The bar uses the same public Left / Lean Left / Center / Lean Right / Right categories " +
                "Ground News and AllSides use, matched against a fixed list of well-known outlets — never a " +
                "guess. \"Unrated\" just means an outlet isn't in that list yet, not that it has no lean. This " +
                "is a separate thing from how trustworthy or fact-checked an outlet is — a source can be " +
                "trusted AND still lean a direction; seeing one color dominate just means you're mostly " +
                "reading one side of this particular story.",
        )
    }

    /** What the BUZZ meter means and its honest limits — it's a local overlap check, not a live count. */
    fun buzz(level: BuzzLevel): Explainer = Explainer(
        "BUZZ — ${level.label.lowercase()}",
        "This isn't a live count of mentions anywhere — Pulse doesn't send this story to any server to ask. " +
            "It's a local check: how many of the Lemmy / Hacker News / Mastodon posts already loaded in your " +
            "SOCIAL tabs this session share this story's own topic tags or a trending hashtag. A quiet reading " +
            "can just mean nobody on those three platforms happened to post about it recently, not that no " +
            "one anywhere is talking about it.",
    )
}
