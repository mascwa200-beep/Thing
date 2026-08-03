package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

class SocialBuzzTest {

    @Test
    fun noTagsOrSocialIsNone() {
        assertEquals(BuzzLevel.NONE, SocialBuzz.score(emptyList(), emptyList()))
    }

    @Test
    fun tagsWithNoSocialOverlapIsNone() {
        assertEquals(BuzzLevel.NONE, SocialBuzz.score(listOf("Politics"), emptyList(), "Senate passes new bill"))
    }

    @Test
    fun oneOverlappingSocialTitleIsLow() {
        val level = SocialBuzz.score(
            articleTags = listOf("Politics"),
            socialTitles = listOf("Senate advances the bill in a late vote"),
        )
        assertEquals(BuzzLevel.LOW, level)
    }

    @Test
    fun threeOverlappingSocialTitlesIsHigh() {
        val level = SocialBuzz.score(
            articleTags = listOf("Politics", "Economy"),
            socialTitles = listOf(
                "Senate advances the bill in a late vote",
                "Congress reacts to the new policy",
                "Economy braces for the fallout from the vote",
            ),
        )
        assertEquals(BuzzLevel.HIGH, level)
    }

    @Test
    fun fiveOrMoreMatchesIsViral() {
        val level = SocialBuzz.score(
            articleTags = listOf("Conflict", "Ukraine"),
            socialTitles = listOf(
                "Troops mobilize as invasion fears grow",
                "World reacts to the ceasefire talks",
                "Analysts weigh in on the war",
                "Markets react to Ukraine tension",
                "Kyiv under renewed attack",
            ),
            articleTitle = "Ukraine war escalates",
            trendTagNames = listOf("ukraine"),
        )
        assertEquals(BuzzLevel.VIRAL, level)
    }

    @Test
    fun trendingHashtagsCountTowardTheScore() {
        val level = SocialBuzz.score(
            articleTags = listOf("Tech"),
            socialTitles = emptyList(),
            articleTitle = "OpenAI announces new AI model",
            articleSummary = "A fresh chip breakthrough too",
            trendTagNames = listOf("ai", "chip"),
        )
        assertEquals(BuzzLevel.MODERATE, level)
    }

    /** A short trend tag like "ai" must match only as a whole word — never as a bare substring inside an
     *  unrelated word like "said"/"main"/"remain". */
    @Test
    fun shortTrendTagsDoNotFalsePositiveInsideOtherWords() {
        val level = SocialBuzz.score(
            articleTags = emptyList(),
            socialTitles = emptyList(),
            articleTitle = "He said the plan would remain the main focus",
            trendTagNames = listOf("ai"),
        )
        assertEquals(BuzzLevel.NONE, level)
    }

    @Test
    fun blankTrendTagNameIsIgnored() {
        val level = SocialBuzz.score(
            articleTags = emptyList(),
            socialTitles = emptyList(),
            articleTitle = "Some headline",
            trendTagNames = listOf(""),
        )
        assertEquals(BuzzLevel.NONE, level)
    }
}
