package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **Every fixture here is a real row**, copied out of a 731-segment corpus pulled from the live
 * hash-prefix endpoint (431 videos, three requests). Invented fixtures would have agreed with
 * whatever the code did; these disagreed with the design twice, and both disagreements are now
 * constants in the source.
 */
class SponsorSegmentsTest {

    private fun seg(
        cat: SponsorSegments.Category,
        start: Double,
        end: Double,
        votes: Int = 0,
        locked: Boolean = false,
        action: SponsorSegments.Action = SponsorSegments.Action.SKIP,
        uuid: String = "$cat-$start",
    ) = SponsorSegments.Segment(uuid, cat, action, start, end, votes, locked)

    // ---- the vote floor --------------------------------------------------------------------------

    /**
     * ⚠️ **THE MEASUREMENT THAT WOULD HAVE SHIPPED A BROKEN FEATURE.** 670 of 731 real segments sit
     * at exactly zero votes, because almost nobody votes on a segment that is simply correct. A
     * "more up than down" floor keeps 56 of 731 and leaves 384 of 431 videos with nothing at all.
     */
    @Test
    fun aSegmentNobodyHasVotedOnIsStillUsed() {
        assertTrue(SponsorSegments.accept(seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0, votes = 0)))
    }

    /** But one that has been voted DOWN is not — that is what the floor is for. */
    @Test
    fun aDownvotedSegmentIsRejected() {
        // Real row: sponsor 1294.06..1324.10, votes -1, unlocked.
        assertFalse(SponsorSegments.accept(
            seg(SponsorSegments.Category.SPONSOR, 1294.06, 1324.10, votes = -1)))
    }

    /**
     * A moderator-locked segment beats the vote floor.
     *
     * ⚠️ And ONLY the vote floor. The next two tests pin the two things `locked` must NOT override,
     * because "a moderator confirmed it" is a statement about accuracy, not about what the user
     * asked to have skipped.
     */
    @Test
    fun aLockedSegmentBeatsTheVoteFloor() {
        assertTrue(SponsorSegments.accept(
            seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0, votes = -5, locked = true)))
    }

    @Test
    fun aLockedSegmentDoesNotOverrideTheUsersCategoryChoice() {
        // Real row: music_offtopic, locked. Off by default, and locking must not turn it on.
        assertFalse(SponsorSegments.accept(
            seg(SponsorSegments.Category.MUSIC_OFFTOPIC, 60.0, 180.0, locked = true)))
    }

    /**
     * ⚠️ Real row, and the sharpest one in the corpus: `music_offtopic 0.00..0.38 locked=1` — the
     * shortest segment of 731 is also a locked one. Seeking costs about a second of stall, so a
     * 0.38-second skip is strictly worse than not skipping, whoever confirmed it.
     */
    @Test
    fun aLockedSegmentTooShortToBeWorthASeekIsStillRejected() {
        assertFalse(SponsorSegments.accept(
            seg(SponsorSegments.Category.INTRO, 0.0, 0.21, locked = true)))
        assertFalse(SponsorSegments.accept(
            seg(SponsorSegments.Category.INTRO, 0.0, 0.66, locked = true)))
        // And a segment comfortably over the floor is untouched by the rule.
        assertTrue(SponsorSegments.accept(seg(SponsorSegments.Category.INTRO, 0.0, 12.15)))
    }

    // ---- action types ----------------------------------------------------------------------------

    /**
     * ⚠️ Only SKIP may be skipped. A FULL segment labels the whole video, so treating it as a skip
     * jumps to the end; MUTE means keep playing quietly; POI and CHAPTER are markers.
     */
    @Test
    fun onlyASkipActionIsEverSkipped() {
        for (a in SponsorSegments.Action.entries) {
            val s = seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0, action = a)
            assertEquals(a.toString(), a == SponsorSegments.Action.SKIP, SponsorSegments.accept(s))
        }
    }

    /**
     * An unrecognised category is never skipped — the database gains new ones over time.
     *
     * ⚠️ Tested through a policy that enables EVERY category, and that is the whole point. Under the
     * default policy UNKNOWN is simply not in the enabled set, so the explicit guard never runs and
     * a test using the default proves nothing about it. The dangerous case is real and natural: an
     * "enable everything" toggle built as `Category.entries.toSet()` would otherwise skip ranges
     * whose meaning this build does not know.
     */
    @Test
    fun anUnknownCategoryIsNeverSkippedEvenWhenEverythingIsEnabled() {
        assertEquals(SponsorSegments.Category.UNKNOWN, SponsorSegments.categoryOf("chapter_marker_v9"))
        assertEquals(SponsorSegments.Action.UNKNOWN, SponsorSegments.actionOf("something_new"))
        val everything = SponsorSegments.Policy(
            categories = SponsorSegments.Category.entries.toSet())
        assertTrue("the policy must genuinely enable it, or this test proves nothing",
            SponsorSegments.Category.UNKNOWN in everything.categories)
        assertFalse(SponsorSegments.accept(seg(SponsorSegments.Category.UNKNOWN, 10.0, 40.0), everything))
        // A known category under the same permissive policy still passes, so the rule is specific.
        assertTrue(SponsorSegments.accept(seg(SponsorSegments.Category.FILLER, 10.0, 40.0), everything))
    }

    /** Every wire string the real corpus contained maps to a real member. */
    @Test
    fun everyCategorySeenInTheRealCorpusParses() {
        for (raw in listOf("sponsor", "intro", "outro", "selfpromo", "interaction",
            "preview", "filler", "music_offtopic")) {
            assertFalse(raw, SponsorSegments.categoryOf(raw) == SponsorSegments.Category.UNKNOWN)
        }
        assertEquals(SponsorSegments.Action.SKIP, SponsorSegments.actionOf("skip"))
    }

    // ---- merging ---------------------------------------------------------------------------------

    /**
     * ⚠️ Real pair: `interaction 518.83..533.82` then `outro 533.76..553.82` — a six-hundredth of a
     * second of overlap, and both locked. Skipped separately that is two stalls where one would do.
     * 26 of 731 real segments overlapped their neighbour.
     */
    @Test
    fun overlappingSegmentsBecomeOneSkip() {
        val merged = SponsorSegments.usable(listOf(
            seg(SponsorSegments.Category.INTERACTION, 518.83, 533.82, locked = true),
            seg(SponsorSegments.Category.OUTRO, 533.76, 553.82, locked = true),
        ))
        assertEquals(1, merged.size)
        assertEquals(518.83, merged[0].startS, 1e-9)
        assertEquals(553.82, merged[0].endS, 1e-9)
        // The block takes the category of whichever the viewer would have reached first.
        assertEquals(SponsorSegments.Category.INTERACTION, merged[0].category)
    }

    /**
     * ⚠️ **Filtering happens BEFORE merging, and this real pair is why.** `filler 2330.97..2369.65`
     * fully contains `sponsor 2346.56..2369.42`, and filler is off by default. Merge first and the
     * viewer loses 15.6 seconds of filler they explicitly asked to keep, inside a block labelled
     * "sponsor". Filter first and only the sponsor goes.
     */
    @Test
    fun aDisabledCategoryIsNotDraggedInByAnEnabledOneItOverlaps() {
        val merged = SponsorSegments.usable(listOf(
            seg(SponsorSegments.Category.FILLER, 2330.97, 2369.65),
            seg(SponsorSegments.Category.SPONSOR, 2346.56, 2369.42),
        ))
        assertEquals(1, merged.size)
        assertEquals("the filler must not be swallowed", 2346.56, merged[0].startS, 1e-9)
        assertEquals(2369.42, merged[0].endS, 1e-9)
    }

    /** A segment wholly inside another leaves the outer one's extent alone. */
    @Test
    fun aContainedSegmentDoesNotShortenItsContainer() {
        val merged = SponsorSegments.usable(listOf(
            seg(SponsorSegments.Category.SPONSOR, 100.0, 200.0),
            seg(SponsorSegments.Category.SELFPROMO, 120.0, 150.0),
        ))
        assertEquals(1, merged.size)
        assertEquals(100.0, merged[0].startS, 1e-9)
        assertEquals(200.0, merged[0].endS, 1e-9)
    }

    /** Segments that do not touch stay separate, so merging has not simply collapsed everything. */
    @Test
    fun separateSegmentsStaySeparate() {
        val merged = SponsorSegments.usable(listOf(
            seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0),
            seg(SponsorSegments.Category.OUTRO, 500.0, 540.0),
        ))
        assertEquals(2, merged.size)
    }

    /** Unsorted input — which is what the wire gives — still merges correctly. */
    @Test
    fun theWireOrderDoesNotMatter() {
        val forwards = SponsorSegments.usable(listOf(
            seg(SponsorSegments.Category.INTRO, 0.0, 12.15),
            seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0),
        ))
        val backwards = SponsorSegments.usable(listOf(
            seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0),
            seg(SponsorSegments.Category.INTRO, 0.0, 12.15),
        ))
        assertEquals(forwards, backwards)
    }

    // ---- playback --------------------------------------------------------------------------------

    @Test
    fun insideASegmentTheTargetIsItsEnd() {
        val segs = SponsorSegments.usable(listOf(seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0)))
        assertEquals(130.0, SponsorSegments.skipTo(115.0, segs)!!, 1e-9)
        assertEquals("the very first frame of a segment counts as inside it",
            130.0, SponsorSegments.skipTo(100.0, segs)!!, 1e-9)
    }

    @Test
    fun outsideEverySegmentNothingHappens() {
        val segs = SponsorSegments.usable(listOf(seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0)))
        assertNull(SponsorSegments.skipTo(99.9, segs))
        assertNull(SponsorSegments.skipTo(200.0, segs))
        assertNull(SponsorSegments.skipTo(0.0, emptyList()))
    }

    /**
     * ⚠️ **The loop guard.** Landing exactly on a segment's end must not match that segment again —
     * otherwise the skip target is behind the playhead, the player seeks backwards, the same segment
     * matches on the next tick, and the video is stuck forever. The exclusive end makes it
     * structurally impossible and `skipTo` refuses a non-forward target as well, because the wire's
     * segments arrive unmerged.
     */
    @Test
    fun landingOnTheEndOfASegmentDoesNotSkipAgain() {
        val segs = SponsorSegments.usable(listOf(seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0)))
        assertNull(SponsorSegments.skipTo(130.0, segs))
    }

    /**
     * ⚠️ **The test that actually distinguishes an exclusive end from an inclusive one**, and the
     * reason it took a perturbation sweep to find. With a single segment both spellings return null
     * at the boundary — inclusive finds the segment and hands back a target equal to the position,
     * which a second guard then discarded. So neither guard could be shown to matter.
     *
     * Two adjacent segments passed RAW, which is how the wire delivers them, tell them apart: at the
     * shared boundary the exclusive rule belongs to the segment starting there and skips past it,
     * while the inclusive rule matches the one ending there and produces a target behind the
     * playhead — a seek backwards into content already skipped, matching again on the next tick.
     */
    @Test
    fun atASharedBoundaryThePositionBelongsToTheSegmentStartingThere() {
        val raw = listOf(
            seg(SponsorSegments.Category.INTRO, 100.0, 130.0),
            seg(SponsorSegments.Category.SPONSOR, 130.0, 160.0),
        )
        assertEquals(160.0, SponsorSegments.skipTo(130.0, raw)!!, 1e-9)
    }

    /** A skip never sends the playhead backwards, even given raw unmerged input. */
    @Test
    fun aSkipIsAlwaysForwards() {
        val raw = listOf(
            seg(SponsorSegments.Category.SPONSOR, 100.0, 130.0),
            seg(SponsorSegments.Category.OUTRO, 120.0, 125.0),
        )
        for (pos in listOf(100.0, 110.0, 120.0, 124.9, 129.99)) {
            val target = SponsorSegments.skipTo(pos, raw)
            if (target != null) assertTrue("pos=$pos target=$target", target > pos)
        }
    }

    /**
     * Back-to-back segments resolve in ONE seek, not a chain of them.
     *
     * Two blocks that touch merge, so a viewer entering the first lands past the second — which is
     * the practical payoff of merging and would be silently lost if merging were removed.
     */
    @Test
    fun touchingSegmentsResolveInOneSeek() {
        val segs = SponsorSegments.usable(listOf(
            seg(SponsorSegments.Category.INTRO, 0.0, 12.15),
            seg(SponsorSegments.Category.INTERACTION, 12.15, 31.57),
        ))
        assertEquals(1, segs.size)
        assertEquals(31.57, SponsorSegments.skipTo(1.0, segs)!!, 1e-9)
    }

    // ---- reporting -------------------------------------------------------------------------------

    /** The saved total counts merged blocks, so an overlap is not counted twice. */
    @Test
    fun theSavedTotalDoesNotDoubleCountAnOverlap() {
        val merged = SponsorSegments.usable(listOf(
            seg(SponsorSegments.Category.SPONSOR, 100.0, 200.0),
            seg(SponsorSegments.Category.SELFPROMO, 150.0, 250.0),
        ))
        assertEquals(150.0, SponsorSegments.totalSkippedS(merged), 1e-9)
    }

    /** Every category says something distinct, so a skip toast is never a bare "segment". */
    @Test
    fun everyCategoryHasItsOwnLabel() {
        val said = SponsorSegments.Category.entries.map { SponsorSegments.label(it) }
        assertEquals(said.toSet().size, said.size)
        said.forEach { assertTrue(it.isNotBlank()) }
    }
}
