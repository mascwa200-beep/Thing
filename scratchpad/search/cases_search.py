"""Perturbations for SearchPlan. Run: python3 scratchpad/search/perturb.py search"""

SOURCE = "core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/SearchPlan.kt"
TEST = "core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/SearchPlanTest.kt"

# (label, exact source fragment, replacement, the test that MUST fail)
CASES = [
    ("word-boundary matching -> plain contains",
     'private fun containsPhrase(haystack: String, phrase: String): Boolean =\n'
     '    " $haystack ".contains(" $phrase ")',
     'private fun containsPhrase(haystack: String, phrase: String): Boolean =\n'
     '    haystack.contains(phrase)',
     "aMarkerInsideALongerWordDoesNotFire"),

    ("CURRENT checked before PRACTICAL -> reversed",
     'if (CURRENT_MARKERS.any { containsPhrase(q, it) }) return Shape.CURRENT\n'
     '        if (PRACTICAL_MARKERS.any { containsPhrase(q, it) }) return Shape.PRACTICAL',
     'if (PRACTICAL_MARKERS.any { containsPhrase(q, it) }) return Shape.PRACTICAL\n'
     '        if (CURRENT_MARKERS.any { containsPhrase(q, it) }) return Shape.CURRENT',
     "currentBeatsPracticalWhenAQuestionIsBoth"),

    ("apostrophe deleted -> retained",
     "s.lowercase().filter { it != '\\'' && it != '’' }\n"
     "        .map { if (it.isLetterOrDigit()) it else ' ' }",
     "s.lowercase()\n"
     "        .map { if (it.isLetterOrDigit() || it == '\\'' || it == '’') it else ' ' }",
     "apostrophesCollapseOntoTheWrittenForm"),

    ("the gap notice is shape-scoped -> always WEB",
     '    private fun requiredTier(shape: Shape): Tier? = when (shape) {\n'
     '        Shape.CURRENT -> Tier.WEB\n        else -> null\n    }',
     '    private fun requiredTier(shape: Shape): Tier? = Tier.WEB',
     "aPracticalQuestionWithNoWebTierReportsNoGap"),

    ("front-only stripping -> drop scaffold words anywhere",
     '        var cut = 0\n        while (cut < words.size && words[cut] in LEADING_SCAFFOLD) cut++',
     '        words = words.filterNot { it in LEADING_SCAFFOLD }\n        var cut = 0',
     "interiorWordsAndOrderAreKept"),

    ("never strip to nothing -> allow it",
     '        if (cut in 1 until words.size) words = words.drop(cut)',
     '        if (cut >= 1) words = words.drop(cut)',
     "aQueryThatIsAllScaffoldingSurvivesIntact"),

    ("per-tier cap -> uncapped",
     '                if (taken >= perTier || out.size >= limit) break',
     '                if (out.size >= limit) break',
     "aTierIsCappedSoTheOthersStillAppear"),

    ("url dedupe -> none",
     '                if (!seen.add(key)) continue',
     '                seen.add(key)',
     "theSameUrlFromTwoTiersAppearsOnce"),

    ("urlless answers keyed by title -> keyed by tier alone",
     'val key = a.url?.lowercase() ?: "${a.tier}:${a.title.lowercase()}"',
     'val key = a.url?.lowercase() ?: "${a.tier}"',
     "urllessAnswersAreNotTreatedAsDuplicates"),

    ("tier order decides precedence -> a fixed order",
     '        for (tier in order) {',
     '        for (tier in listOf(Tier.WEB, Tier.LIBRARY, Tier.ENCYCLOPAEDIA)) {',
     "theTierOrderDecidesPrecedence"),

    ("provenance distinct per tier -> two the same",
     '    fun provenance(tier: Tier): String = when (tier) {\n'
     '        Tier.LIBRARY -> "From the offline library on this device"',
     '    fun provenance(tier: Tier): String = when (tier) {\n'
     '        Tier.LIBRARY -> "From Wikipedia"',
     "everyTierIntroducesItselfDistinctly"),
]


