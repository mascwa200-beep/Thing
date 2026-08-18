package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryLedgerTest {

    @Test fun theSameEventUnderTwoBylinesIsOneStory() {
        // The defect this exists for. Google News appends " - Source", so the identical event
        // carried by two outlets produced two different strings and reprinted twice. Both of these
        // must reduce to the same key, and the "Breaking:" label must not survive into it either —
        // the same tag whose presence in EmergencyNews.MAJOR drove the permanent red alert.
        val a = StoryLedger.identity("Breaking: Storm hits the coast - CNN")
        val b = StoryLedger.identity("Storm hits the coast - BBC News")
        assertEquals(a, b)
        assertEquals("storm hits the coast", a)
    }

    @Test fun caseAndPunctuationDoNotMakeANewStory() {
        assertEquals(
            StoryLedger.identity("Dam Breaks in Valley; Thousands Flee"),
            StoryLedger.identity("dam breaks in valley thousands flee"),
        )
    }

    @Test fun aBlankStoryIsNeverNewAndNeverEnters() {
        assertEquals("", StoryLedger.identity(null))
        assertEquals("", StoryLedger.identity("   "))
        assertFalse(StoryLedger.isNew("", emptySet()))
        // …and it cannot pollute the ledger, so a blank headline can't push a real one out.
        assertEquals(listOf("a"), StoryLedger.remember("", listOf("a")))
    }

    @Test fun anUnseenStoryIsNewAndASeenOneIsNot() {
        val seen = setOf(StoryLedger.identity("Storm hits the coast - CNN"))
        assertFalse(StoryLedger.isNew(StoryLedger.identity("Storm hits the coast - Reuters"), seen))
        assertTrue(StoryLedger.isNew(StoryLedger.identity("Budget passes at last - AP"), seen))
    }

    @Test fun firstUnseenSkipsPastWhatWasAlreadyShown() {
        val seen = setOf(StoryLedger.identity("One"), StoryLedger.identity("Two"))
        assertEquals("Three", StoryLedger.firstUnseen(listOf("One", "Two", "Three"), seen))
    }

    @Test fun whenEverythingHasBeenShownItReturnsNullRatherThanRepeating() {
        // ⚠️ The load-bearing one. Null means the caller omits the row. Returning the top story
        // "anyway" is precisely the behaviour being removed — a notification that repeats itself.
        val seen = setOf(StoryLedger.identity("One"), StoryLedger.identity("Two"))
        assertNull(StoryLedger.firstUnseen(listOf("One", "Two"), seen))
    }

    @Test fun theRingIsBoundedAndDropsTheOldestFirst() {
        val full = (1..StoryLedger.MAX).map { "s$it" }
        val after = StoryLedger.remember("new", full)
        assertEquals(StoryLedger.MAX, after.size)
        assertFalse("s1" in after)
        assertEquals("new", after.last())
    }

    @Test fun reShowingAStoryMovesItToTheEndRatherThanLeavingItToAgeOut() {
        // Without this a recurring story sits at the head of a busy ledger, ages out, and re-fires —
        // which is the same defect wearing a hat.
        val seen = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), StoryLedger.remember("a", seen))
        assertEquals(3, StoryLedger.remember("a", seen).size)
    }

    @Test fun rememberAllKeepsOrderAndDeduplicates() {
        assertEquals(listOf("b", "a", "c"), StoryLedger.rememberAll(listOf("a", "c"), listOf("b", "a")))
    }
}
