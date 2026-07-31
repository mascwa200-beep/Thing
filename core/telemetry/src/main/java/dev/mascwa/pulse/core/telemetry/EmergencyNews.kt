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
}
