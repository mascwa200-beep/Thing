package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsSummaryTest {

    // ---- when the summary is the headline again ---------------------------------------------

    @Test fun aSummaryThatIsOnlyTheHeadlineIsNotPrintedTwice() {
        val title = "Central bank holds rates for a third meeting"
        assertNull(NewsSummary.subtitle(title, title))
        // The shape an aggregator's search feed files: the headline, then the outlet.
        assertNull(NewsSummary.subtitle(title, "$title Reuters", source = "Reuters"))
        assertNull(NewsSummary.subtitle(title, "$title - Reuters", source = "Reuters"))
    }

    @Test fun theComparisonSurvivesAnAggregatorRetypingThePunctuation() {
        // ⚠️ The reason this is keyed on letters and digits rather than compared directly. A copy
        // of a headline filed beside it is rarely byte-identical: quotes get straightened, dashes
        // get swapped, spacing drifts.
        val title = "“Nothing is off the table,” says minister — talks resume"
        assertNull(NewsSummary.subtitle(title, "\"Nothing is off the table\", says minister - talks resume"))
        assertNull(NewsSummary.subtitle(title, "  Nothing is off the table  says minister  talks resume  "))
    }

    @Test fun realProseIsLeftExactlyAsItIs() {
        val title = "Central bank holds rates"
        val summary = "Policymakers voted 7-2 to keep the benchmark unchanged, citing services inflation."
        assertEquals(summary, NewsSummary.subtitle(title, summary))
    }

    @Test fun whatFollowsTheRepeatedHeadlineIsKept() {
        // The Google shape once the markup is stripped: this story's headline, then other outlets'.
        val title = "Storm closes the coast road"
        val summary = "$title BBC News Coast road shut as storm lands The Guardian"
        assertEquals(
            "BBC News Coast road shut as storm lands The Guardian",
            NewsSummary.subtitle(title, summary, source = "BBC News"),
        )
    }

    @Test fun aSummaryThatMerelyBeginsWithTheSameWordsIsNotTreatedAsARepeat() {
        // "Central bank holds" is not a prefix of the key of "Central bankers held", so the whole
        // summary stands. The prefix test is on the headline, not on a similarity score.
        val title = "Central bank holds rates"
        val summary = "Central bankers held their nerve through a difficult week."
        assertEquals(summary, NewsSummary.subtitle(title, summary))
    }

    @Test fun anEmptySummaryOrAnEmptyTitleIsHandledWithoutGuessing() {
        assertNull(NewsSummary.subtitle("A headline", ""))
        assertNull(NewsSummary.subtitle("A headline", "   "))
        // No headline to compare against: nothing can be a repeat of it, so the text stands.
        assertEquals("Some text", NewsSummary.subtitle("", "Some text"))
        assertEquals("Some text", NewsSummary.subtitle("!!! ...", "Some text"))
    }

    // ---- where to stop ----------------------------------------------------------------------

    @Test fun shortEnoughTextIsUntouched() {
        assertEquals("Nine words is not very many words at all.", NewsSummary.clip("Nine words is not very many words at all.", 400))
        assertEquals("", NewsSummary.clip("", 400))
    }

    @Test fun aCutLandsOnAWordBoundaryRatherThanMidWord() {
        // 30 characters of "The quick brown fox jumped over the lazy dog" is "The quick brown fox jumped ove"
        // — the trap a plain take() falls into. The last space inside the budget is at index 26.
        val text = "The quick brown fox jumped over the lazy dog"
        val clipped = NewsSummary.clip(text, 30)
        assertEquals("The quick brown fox jumped…", clipped)
        assertTrue("must not exceed the budget plus its ellipsis", clipped.length <= 31)
    }

    @Test fun trailingPunctuationIsNotStrandedBeforeTheEllipsis() {
        assertEquals("First clause…", NewsSummary.clip("First clause, second clause, third clause", 16))
    }

    @Test fun aSingleWordLongerThanTheBudgetIsCutRatherThanLost() {
        // ⚠️ No word boundary to back up to. Giving up most of the line hunting for one would be a
        // worse answer than the hard cut, so the rule only backs up within the second half.
        val long = "Supercalifragilisticexpialidocious"
        assertEquals("Supercalif…", NewsSummary.clip(long, 10))
        // A boundary that sits too early is likewise ignored: "A " then a very long token.
        assertEquals("A Supercal…", NewsSummary.clip("A $long", 10))
    }

    @Test fun aNonPositiveBudgetYieldsNothingRatherThanThrowing() {
        assertEquals("", NewsSummary.clip("anything", 0))
        assertEquals("", NewsSummary.clip("anything", -5))
    }
}
