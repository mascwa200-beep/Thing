package dev.mascwa.pulse.core.telemetry

/**
 * Detects a major EMERGENCY / "this just in" event from a news headline — the kind that warrants its own
 * urgent, distinct notification, separate from the general breaking-news feed. Pure logic (CI-gated):
 * keyword matching over the headline (+ optional summary), with a two-tier severity and a guard against
 * obvious showbiz/sport false positives (a "box-office explosion", a film called "Attack", …).
 *
 * The point is high-signal recall on the disasters/violence/crises everyone hears about at once — not every
 * "breaking" label (most breaking news isn't an emergency). STRONG keywords fire on their own; MODERATE ones
 * fire only when no entertainment/sport context word sits alongside them.
 */
object EmergencyNews {

    // Almost-always-real emergencies — a hit here is enough on its own.
    private val STRONG = listOf(
        "earthquake", "tsunami", "wildfire", "eruption", "tornado", "hurricane", "typhoon", "cyclone",
        "flash flood", "landslide", "mudslide", "state of emergency", "evacuat", "mass casualt",
        "active shooter", "mass shooting", "school shooting", "plane crash", "jet crash", "train derail",
        "derailment", "airstrike", "air strike", "missile strike", "declares war", "invasion",
        "assassinat", "hostage", "terror attack", "terrorist attack", "martial law", "amber alert",
        "nuclear strike", "nuclear attack", "meltdown", "radiation leak", "chemical spill", "dam burst",
        "levee break", "building collapse", "bridge collapse", "shelter in place", "curfew imposed",
        "outbreak declared", "pandemic declared", "suicide bomb", "car bomb",
    )

    // Real emergencies in most contexts, but the same words show up in showbiz/sport — require no
    // entertainment/sport context word alongside them.
    private val MODERATE = listOf(
        "explosion", "bombing", "gas leak", "manhunt", "shooting spree", "gunman", "stabbing", "blast",
    )

    // If any of these appear, a MODERATE signal is read as showbiz/sport, not an emergency.
    private val ENTERTAINMENT = listOf(
        "box office", "box-office", "film", "movie", "trailer", "episode", "season", "premiere", "review",
        "album", "concert", "series", "match", "goal", "esports", "streaming", "netflix", "marvel",
        "actor", "actress", "celebrity", "red carpet", "showbiz", "chart", "single ",
    )

    /** True when [title] (+ optional [summary]) reads as a major emergency / breaking crisis event. */
    fun isEmergency(title: String, summary: String = ""): Boolean = severity(title, summary) > 0

    /** 2 for a STRONG signal, 1 for a MODERATE-only signal, 0 for none — used to pick the most urgent item. */
    fun severity(title: String, summary: String = ""): Int {
        val t = (title + " " + summary).lowercase()
        if (STRONG.any { it in t }) return 2
        if (MODERATE.any { it in t } && ENTERTAINMENT.none { it in t }) return 1
        return 0
    }

    // The death of a notable person — "the way Hollywood portrays it": a headline everyone hears at once.
    private val DEATH = listOf(
        "dies at", "dead at", "has died", "passed away", "found dead", "dies aged", "dies after",
        "dead aged", "obituary", "confirmed dead", "pronounced dead",
    )

    // Fiction context — so "a character dies in the finale" doesn't force a takeover (a real death does).
    private val FICTION = listOf(
        "character", "episode", "finale", "fictional", "plot", "spoiler", "in the show", "in the film",
        "in the movie", "storyline", "cliffhanger", "recap",
    )

    // Other historic/major breaking events worth a takeover beyond the STRONG disaster set.
    private val MAJOR = listOf(
        "declares war", "state of emergency", "coup", "assassinat", "impeach", "resigns amid",
        "steps down amid", "verdict reached", "found guilty", "mass casualt", "nationwide emergency",
        "breaking:", "just in:", "developing:",
    )

    /**
     * The HIGHER bar for a full-screen INTERRUPT (force-open the cinematic page) — deliberately rarer than
     * [isEmergency]: a STRONG disaster/attack, the death of a notable person, or a historic breaking event.
     * Most "breaking" news does NOT clear this bar.
     */
    fun isMajor(title: String, summary: String = ""): Boolean {
        if (severity(title, summary) == 2) return true
        val t = " ${(title + " " + summary).lowercase()} "
        if (DEATH.any { it in t } && FICTION.none { it in t }) return true
        return MAJOR.any { it in t }
    }

    /**
     * A clean search query for aggregating coverage of a breaking headline: drops the " - Source" suffix
     * Google News RSS appends, strips leading "Breaking:"/"Just in:" tags, collapses whitespace, and caps
     * the length so it stays a usable topic query.
     */
    fun topicQuery(title: String): String {
        var q = title.substringBefore(" - ").trim() // Google News "Headline - Source"
        val tags = listOf("breaking:", "just in:", "developing:", "update:", "live:", "watch:")
        var lower = q.lowercase()
        var changed = true
        while (changed) {
            changed = false
            for (tag in tags) if (lower.startsWith(tag)) {
                q = q.substring(tag.length).trim(); lower = q.lowercase(); changed = true
            }
        }
        q = q.replace(Regex("\\s+"), " ").trim()
        return if (q.length > 120) q.take(120).substringBeforeLast(' ') else q
    }
}
