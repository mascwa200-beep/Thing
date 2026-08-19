package dev.mascwa.pulse.core.telemetry

/**
 * Which page of the offline library explains a given reasoning mistake — stage 4 of the cascade,
 * and the R in this feature's RAG.
 *
 * ⚠️ **CURATED, NOT RANKED, AND THAT WAS MEASURED RATHER THAN ASSUMED.** The obvious design is to
 * hand the fallacy's name to [GuideSearch] and use whatever comes back. Running the shipped ranker
 * over the real 651-guide index with all 25 labels returns noise, every hit on a single matched word:
 *
 * ```
 *   "Appeal to popularity" -> Reading Flood Maps and Base Flood Elevation
 *   "Slippery slope"       -> Slope Aspect and Solar Warmth
 *   "Straw man"            -> Charlie Chaplin and Silent Comedy
 *   "Appeal to fear"       -> Stress, Fear & Psychological First Aid
 * ```
 *
 * A flood-map citation under a rebuttal would be merely embarrassing; the same passage reaches the
 * adjudicator's prompt as "REFERENCE, from an offline library", where it would actively push the
 * judgement around. Not a ranking bug — the library has no per-fallacy page to find. Exactly two of
 * its 651 guides discuss fallacies at all, and one of them has a section named for them.
 *
 * This is the same argument [EmergencyTriage] makes in its own words: ranking is the right tool for
 * a question and the wrong one for a known destination. [LibraryLookup.exact] exists for this.
 *
 * ⚠️ **The other obvious design — grounding on the SUBJECT of what was said rather than on the
 * fallacy — was also measured, and is not shipped.** `LibraryLookup.consult` is tuned for typed
 * questions, where the rarest word is the subject ("bowline", "schengen"). In ambient speech it
 * usually is not: over twelve realistic spoken claims it keyed on *minutes*, *either*, *obviously*
 * and *grandfather*, citing a levee-breach guide for boiling water and a demand-curve guide for a
 * budget argument, while refusing the correct Vaccines and Blood Pressure pages it had already
 * ranked first. `Hit.matched` does not separate them either — the worst hit scored 4 and the correct
 * one scored 1. It is a real and desirable feature; it needs a retrieval bar fitted to speech, and
 * inventing one against a dozen sentences would be overfitting. Recorded as open, not papered over.
 */
object FallacyReference {

    /** A named guide and section: the destination, not a search. */
    data class Route(val guideId: String, val heading: String)

    /**
     * Where the library explains fallacies in general.
     *
     * ⚠️ Every id and heading here is a promise about bundled content, which drifts when a guide is
     * re-edited — `FallacyReferenceRoutesTest` resolves all of them against the real assets, so a
     * renamed heading fails the build instead of silently citing a page that no longer says this.
     */
    val GENERAL = Route("cs-formal-logic", "Common Logical Fallacies")

    /**
     * The three that belong somewhere better.
     *
     * Deliberately short. A per-fallacy map would be a nicer thing to own and a worse thing to
     * maintain: the library does not have twenty-five such sections, and inventing routes to pages
     * that merely mention a related word is how the ranked version failed in the first place. These
     * three are named sections about that exact mistake.
     */
    private val SPECIAL: Map<String, Route> = mapOf(
        "sunk_cost" to Route("psy-cognitive-biases-decisions", "Sunk Cost Fallacy and Escalation of Commitment"),
        "bandwagon" to Route("psy-cognitive-biases-decisions", "Social and group biases"),
        "anecdote" to Route("psy-cognitive-biases-decisions", "The major heuristics"),
    )

    /** The page for [fallacyId]. Never null: the general section is a real answer, not a fallback. */
    fun routeFor(fallacyId: String): Route = SPECIAL[fallacyId] ?: GENERAL

    /** Every distinct destination, for the test that resolves them. */
    fun allRoutes(): List<Route> = (SPECIAL.values + GENERAL).distinct()
}
