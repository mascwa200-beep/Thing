package dev.mascwa.pulse.core.telemetry

/**
 * What the board has already told you, so it never tells you the same thing twice.
 *
 * The one notification is a single fixed id re-posted on every refresh, and its dedup until now was
 * [UnifiedBrief.urgencyKey] — which decides whether the post **buzzes**, not what it **says**. So a
 * story that stayed at the top of the feed for six hours reprinted, word for word, on every pass.
 * A notification that repeats itself is not a notification; it is wallpaper.
 *
 * ⚠️ **The identity is not the title.** Google News appends `" - Source"`, and the same event is
 * carried by a dozen outlets under near-identical wording, so keying on the raw string re-fires the
 * same event under a different byline. [identity] normalises through the existing
 * [EmergencyNews.topicQuery] (which already strips that suffix and the "Breaking:"/"Just in:" tags
 * this codebase has learned to distrust), then folds case and punctuation away. Two outlets running
 * the same headline collapse to one entry.
 *
 * ⚠️ **It deliberately does not try to be clever about paraphrase.** Two genuinely different
 * wordings of the same event will pass as two stories. Fixing that needs similarity scoring, and a
 * scorer that is slightly too eager silently *suppresses real news* — which is a far worse failure
 * than showing one story twice. Exact-after-normalisation is the bar that cannot swallow anything.
 *
 * Pure and deterministic, so CI holds the rule.
 */
object StoryLedger {

    /** How many identities to carry. ~200 covers a couple of days of a busy feed at ~20/refresh. */
    const val MAX = 200

    private val PUNCT = Regex("[^a-z0-9 ]")
    private val SPACES = Regex("\\s+")

    /**
     * A stable key for "this story", or blank when there is no story.
     *
     * Blank in, blank out — and a blank identity is never [isNew], so an empty headline can neither
     * be shown nor pollute the ledger.
     */
    fun identity(title: String?): String {
        val t = title?.trim().orEmpty()
        if (t.isBlank()) return ""
        return EmergencyNews.topicQuery(t)
            .lowercase()
            .replace(PUNCT, " ")
            .replace(SPACES, " ")
            .trim()
    }

    /** True when this story has not been shown before. A blank identity is never new. */
    fun isNew(identity: String, seen: Collection<String>): Boolean =
        identity.isNotBlank() && identity !in seen

    /**
     * The first story in [titles] that has not been shown, or null when they have all been shown.
     *
     * Null is a real answer and the caller must honour it by **omitting the row**. Falling back to
     * "show the top one anyway" would reinstate the exact defect this exists to remove.
     */
    fun firstUnseen(titles: List<String>, seen: Collection<String>): String? =
        titles.firstOrNull { isNew(identity(it), seen) }

    /**
     * Append an identity to the bounded ring, newest last, no duplicates.
     *
     * ⚠️ Re-showing an identity moves it to the **end** rather than leaving it in place, so a story
     * that keeps recurring stays protected instead of ageing out of a busy ledger and re-firing.
     */
    fun remember(identity: String, seen: List<String>, max: Int = MAX): List<String> {
        if (identity.isBlank()) return seen
        return (seen - identity + identity).takeLast(max)
    }

    /** [remember] for several at once, in order. */
    fun rememberAll(identities: List<String>, seen: List<String>, max: Int = MAX): List<String> =
        identities.fold(seen) { acc, id -> remember(id, acc, max) }
}
