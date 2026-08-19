"""Perturbations for SponsorSegments. Run: python3 scratchpad/search/perturb.py sponsor"""

SOURCE = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SponsorSegments.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SponsorSegmentsTest.kt"

# (label, exact source fragment, replacement, the test that MUST fail)
CASES = [
    ("the vote floor rejects only downvotes -> requires an upvote",
     "    const val MIN_VOTES = 0",
     "    const val MIN_VOTES = 1",
     "aSegmentNobodyHasVotedOnIsStillUsed"),

    ("a downvoted segment is rejected -> floor removed",
     "        if (!s.locked && s.votes < policy.minVotes) return false",
     "        if (false) return false",
     "aDownvotedSegmentIsRejected"),

    ("locked bypasses the vote floor -> locked ignored",
     "        if (!s.locked && s.votes < policy.minVotes) return false",
     "        if (s.votes < policy.minVotes) return false",
     "aLockedSegmentBeatsTheVoteFloor"),

    ("locked does NOT override the category choice -> it does",
     "        if (s.category !in policy.categories) return false",
     "        if (!s.locked && s.category !in policy.categories) return false",
     "aLockedSegmentDoesNotOverrideTheUsersCategoryChoice"),

    ("locked does NOT override the length floor -> it does",
     "        if (s.lengthS < policy.minLengthS) return false",
     "        if (!s.locked && s.lengthS < policy.minLengthS) return false",
     "aLockedSegmentTooShortToBeWorthASeekIsStillRejected"),

    ("only SKIP is skipped -> every action skipped",
     "        if (s.action != Action.SKIP) return false",
     "        if (s.action == Action.UNKNOWN) return false",
     "onlyASkipActionIsEverSkipped"),

    ("an unknown category is never skipped -> allowed through",
     "        if (s.category == Category.UNKNOWN) return false",
     "        if (false) return false",
     "anUnknownCategoryIsNeverSkippedEvenWhenEverythingIsEnabled"),

    ("overlapping segments merge -> no merging at all",
     "            if (next.startS <= cur.endS) {",
     "            if (false) {",
     "overlappingSegmentsBecomeOneSkip"),

    ("touching segments count as overlapping -> strict overlap only",
     "            if (next.startS <= cur.endS) {",
     "            if (next.startS < cur.endS) {",
     "touchingSegmentsResolveInOneSeek"),

    ("a contained segment does not shorten its container -> it does",
     "                if (next.endS > cur.endS) cur = cur.copy(endS = next.endS)",
     "                cur = cur.copy(endS = next.endS)",
     "aContainedSegmentDoesNotShortenItsContainer"),

    ("filter runs before merge -> merge first",
     "    fun usable(segments: List<Segment>, policy: Policy = Policy()): List<Segment> =\n"
     "        merge(segments.filter { accept(it, policy) })",
     "    fun usable(segments: List<Segment>, policy: Policy = Policy()): List<Segment> =\n"
     "        merge(segments).filter { accept(it, policy) }",
     "aDisabledCategoryIsNotDraggedInByAnEnabledOneItOverlaps"),

    # The exclusive end is now the ONLY guard against a backward seek, so this perturbation has to
    # bite. It did not until the test used two adjacent segments — see the test's own note.
    ("segment end is exclusive -> inclusive, which seeks backwards",
     "        segments.firstOrNull { positionS >= it.startS && positionS < it.endS }",
     "        segments.firstOrNull { positionS >= it.startS && positionS <= it.endS }",
     "atASharedBoundaryThePositionBelongsToTheSegmentStartingThere"),

    ("the saved total counts merged blocks -> raw double-counting",
     "    fun totalSkippedS(merged: List<Segment>): Double = merged.sumOf { it.lengthS }",
     "    fun totalSkippedS(merged: List<Segment>): Double = merged.sumOf { it.lengthS } * 2",
     "theSavedTotalDoesNotDoubleCountAnOverlap"),

    ("every category has its own label -> two the same",
     '        Category.SPONSOR -> "sponsor"',
     '        Category.SPONSOR -> "intro"',
     "everyCategoryHasItsOwnLabel"),
]
