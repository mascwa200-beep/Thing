package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaBiasTest {

    @Test
    fun blankSourceIsUnrated() {
        assertNull(MediaBias.leanOf(""))
        assertNull(MediaBias.leanOf("   "))
    }

    @Test
    fun unknownOutletIsUnrated() {
        assertNull(MediaBias.leanOf("Random Local Blog"))
        assertNull(MediaBias.leanOf("Al Jazeera"))
        assertNull(MediaBias.leanOf("Sky News"))
    }

    @Test
    fun matchesRealWorldOutletStrings() {
        assertEquals(Lean.CENTER, MediaBias.leanOf("Reuters"))
        assertEquals(Lean.CENTER, MediaBias.leanOf("Associated Press"))
        assertEquals(Lean.CENTER, MediaBias.leanOf("BBC News"))
        assertEquals(Lean.RIGHT, MediaBias.leanOf("Fox News"))
        assertEquals(Lean.RIGHT, MediaBias.leanOf("FOX NEWS Channel"))
        assertEquals(Lean.RIGHT, MediaBias.leanOf("Breitbart News"))
        assertEquals(Lean.LEFT, MediaBias.leanOf("MSNBC"))
        assertEquals(Lean.LEAN_LEFT, MediaBias.leanOf("The New York Times"))
        assertEquals(Lean.LEAN_LEFT, MediaBias.leanOf("CNN International"))
        assertEquals(Lean.LEAN_LEFT, MediaBias.leanOf("Washington Post"))
        assertEquals(Lean.LEAN_RIGHT, MediaBias.leanOf("New York Post"))
        assertEquals(Lean.LEAN_RIGHT, MediaBias.leanOf("Washington Times"))
        assertEquals(Lean.LEAN_RIGHT, MediaBias.leanOf("Washington Examiner"))
        assertEquals(Lean.LEAN_RIGHT, MediaBias.leanOf("The Wall Street Journal"))
    }

    /** The specific collision risk flagged during planning: "the times"-style short substrings must not
     *  shadow a different, more-specific "X Times" outlet in another bucket. Every "Times" outlet in the
     *  table names its own distinct multi-word phrase, so each resolves to its own correct bucket. */
    @Test
    fun timesOutletsDoNotCollide() {
        assertEquals(Lean.LEAN_LEFT, MediaBias.leanOf("The New York Times"))
        assertEquals(Lean.LEAN_RIGHT, MediaBias.leanOf("The Washington Times"))
        assertEquals(Lean.CENTER, MediaBias.leanOf("Financial Times"))
        assertEquals(Lean.RIGHT, MediaBias.leanOf("The Epoch Times"))
    }

    /** "Fox News" (RIGHT) and "Fox Business" (LEAN_RIGHT) share a word but are distinct outlets/phrases —
     *  neither should cross-match the other's bucket. */
    @Test
    fun similarlyNamedOutletsResolveToTheirOwnBucket() {
        assertEquals(Lean.RIGHT, MediaBias.leanOf("Fox News"))
        assertEquals(Lean.LEAN_RIGHT, MediaBias.leanOf("Fox Business"))
    }

    @Test
    fun breakdownTalliesEachBucket() {
        val b = MediaBias.breakdown(
            listOf("The New York Times", "Washington Post", "CNN", "MSNBC", "Vox", "Fox News"),
        )
        assertEquals(2, b.left)
        assertEquals(3, b.leanLeft)
        assertEquals(0, b.center)
        assertEquals(0, b.leanRight)
        assertEquals(1, b.right)
        assertEquals(0, b.unrated)
        assertEquals(6, b.rated)
        assertEquals(6, b.total)
    }

    @Test
    fun breakdownCountsUnratedSeparately() {
        val b = MediaBias.breakdown(listOf("Reuters", "Random Local Blog", "Some Other Site"))
        assertEquals(1, b.center)
        assertEquals(2, b.unrated)
        assertEquals(1, b.rated)
        assertEquals(3, b.total)
    }

    @Test
    fun breakdownOfEmptyListIsAllZero() {
        val b = MediaBias.breakdown(emptyList())
        assertEquals(0, b.total)
        assertEquals(0, b.rated)
    }

    @Test
    fun summarizeOfEmptyIsBlank() {
        assertEquals("", MediaBias.summarize(MediaBias.breakdown(emptyList())))
    }

    @Test
    fun summarizeAllUnratedSaysSo() {
        val s = MediaBias.summarize(MediaBias.breakdown(listOf("Unknown Blog")))
        assertTrue(s.contains("known lean", ignoreCase = true))
    }

    @Test
    fun summarizeBalancedCenterCoverage() {
        val s = MediaBias.summarize(MediaBias.breakdown(listOf("Reuters", "BBC", "Associated Press")))
        assertTrue(s.contains("center", ignoreCase = true))
        assertTrue(s.contains("balanced", ignoreCase = true))
    }

    @Test
    fun summarizeOneSidedLeftCoverage() {
        val s = MediaBias.summarize(
            MediaBias.breakdown(listOf("The New York Times", "Washington Post", "CNN", "MSNBC", "Vox")),
        )
        assertTrue(s.contains("only seeing one side", ignoreCase = true))
    }

    @Test
    fun summarizeOneSidedRightCoverage() {
        val s = MediaBias.summarize(MediaBias.breakdown(listOf("Fox News", "Breitbart", "Newsmax")))
        assertTrue(s.contains("only seeing one side", ignoreCase = true))
    }

    @Test
    fun summarizeLopsidedButNotExclusiveCoverage() {
        val s = MediaBias.summarize(
            MediaBias.breakdown(listOf("Fox News", "Breitbart", "Newsmax", "The New York Times")),
        )
        assertTrue(s.contains("a lot more than the other", ignoreCase = true))
    }

    @Test
    fun summarizeFairlyEvenSpread() {
        val s = MediaBias.summarize(
            MediaBias.breakdown(listOf("The New York Times", "MSNBC", "Fox News")),
        )
        assertTrue(s.contains("fairly even", ignoreCase = true))
    }
}
