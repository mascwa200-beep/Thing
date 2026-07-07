package dev.mascwa.pulse.core.telemetry

/**
 * NEED SENSING — the bridge from what the camera + microphone actually SEE/HEAR you doing ([ActivitySensing]
 * turns raw scene/sound labels into [RealActivity] evidence) to the survival needs the game tracks. When the
 * phone catches you drinking, eating, washing or brushing, the game can credit the matching need automatically
 * — no button press — so the life-sim genuinely reads your real behaviour ("the game bleeds into reality").
 *
 * Pure + deterministic → CI-testable; the on-device layer (a sampler observer) turns [sensedNeeds] into the
 * real [SpecialGameStore] care actions, rate-limited so one long drink doesn't credit hydration ten times.
 */
object NeedSensing {

    /** Confidence at/above which a sensed activity auto-credits its need — firm corroboration, not a guess. */
    const val AUTO_CREDIT_CONFIDENCE = ActivitySensing.CONFIRM_CONFIDENCE

    /**
     * The game [NeedKind] a sensed [activity] tends, or null when it maps to none. Brushing your teeth tends
     * the distinct BRUSHING need (not generic hygiene); showering / washing up tends HYGIENE; eating, drinking
     * map straight across; a bathroom trip tends nothing the game tracks.
     */
    fun needFor(activity: RealActivity): NeedKind? = when (activity) {
        RealActivity.SHOWER, RealActivity.HANDWASH -> NeedKind.HYGIENE
        RealActivity.TOOTHBRUSH -> NeedKind.BRUSHING
        RealActivity.EATING -> NeedKind.NOURISHMENT
        RealActivity.DRINKING -> NeedKind.HYDRATION
        RealActivity.TOILET -> null
    }

    /**
     * The needs the camera/mic caught you tending in a window of [evidence] — every activity at/above
     * [minConfidence] whose need maps, deduped (you either brushed or you didn't). The store auto-restores these.
     */
    fun sensedNeeds(evidence: List<ActivityEvidence>, minConfidence: Float = AUTO_CREDIT_CONFIDENCE): Set<NeedKind> =
        evidence.asSequence()
            .filter { it.confidence >= minConfidence }
            .mapNotNull { needFor(it.activity) }
            .toSet()

    /** Which real activities the game can auto-credit a need from (everything with a non-null [needFor]). */
    val CREDITABLE: Set<RealActivity> = RealActivity.entries.filterTo(mutableSetOf()) { needFor(it) != null }
}
