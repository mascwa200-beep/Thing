// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/ChannelLineupTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import dev.mascwa.pulse.desktop.telemetry.ChannelLineup.Band
import dev.mascwa.pulse.desktop.telemetry.ChannelLineup.Entry
import dev.mascwa.pulse.desktop.telemetry.ChannelLineup.Tune
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.LiveChannel
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.Provenance
import dev.mascwa.pulse.desktop.telemetry.LiveChannels.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLineupTest {

    private fun ch(
        id: String,
        name: String = id,
        verification: Verification = Verification.SEGMENT,
        provenance: Provenance = Provenance.OFFICIAL,
    ) = LiveChannel(
        id = id, name = name, url = "https://example.test/$id/master.m3u8",
        language = "en", region = "Testland", provenance = provenance, verification = verification,
    )

    // ── the coupling to LiveChannels.CURATED ────────────────────────────────────────────────────

    @Test
    fun everyBandAnchorIsARealChannel() {
        val ids = LiveChannels.CURATED.map { it.id }.toSet()
        val missing = ChannelLineup.ANCHORS.filterNot { it.second in ids }
        assertTrue("anchors naming no channel: $missing", missing.isEmpty())
    }

    /**
     * ⚠️ The load-bearing guard on the ANCHORS list. Inserting a new section into CURATED without
     * recording its anchor here, or reordering the sections, silently mis-numbers the lineup —
     * every channel after the change lands in the wrong band and the viewer's memory of it is
     * wrong. Asserting the anchors appear in declaration order catches both.
     */
    @Test
    fun anchorsAppearInTheListsOwnDeclarationOrder() {
        val positions = ChannelLineup.ANCHORS.map { (band, id) ->
            band to LiveChannels.CURATED.indexOfFirst { it.id == id }
        }
        assertTrue("an anchor is not in the list: $positions", positions.all { it.second >= 0 })
        val sorted = positions.sortedBy { it.second }.map { it.first }
        assertEquals("anchors are out of order against CURATED", sorted, positions.map { it.first })
    }

    @Test
    fun noBandRunsIntoTheNext() {
        assertEquals(emptyList<Band>(), ChannelLineup.overflows(ChannelLineup.lineup()))
    }

    @Test
    fun everyRealChannelGetsItsOwnNumber() {
        val numbers = ChannelLineup.lineup().map { it.number }
        assertEquals(numbers.size, numbers.toSet().size)
        assertTrue("lineup is empty", numbers.isNotEmpty())
        assertTrue("numbering starts below the first band", numbers.min() >= Band.NETWORKS.first)
    }

    @Test
    fun theFirstChannelOfEachBandSitsOnTheBandsOwnFirstNumber() {
        val line = ChannelLineup.lineup()
        for ((band, id) in ChannelLineup.ANCHORS) {
            val slot = line.firstOrNull { it.channel.id == id } ?: continue
            assertEquals("$id should open ${band.name}", band.first, slot.number)
        }
    }

    // ── the premise: a number does not move ─────────────────────────────────────────────────────

    /**
     * ⚠️ THE reason numbering is taken from declaration order rather than [LiveChannels.offer].
     * offer() sorts confirmed-first then alphabetically, so one channel failing would renumber
     * every channel after it. Here the expectation is DERIVED from the shipped function on the
     * unmodified list rather than written out, so the test says "unchanged" and cannot drift.
     */
    @Test
    fun aChannelGoingOffTheAirDoesNotRenumberTheOnesAfterIt() {
        val before = ChannelLineup.lineup().associate { it.channel.id to it.number }
        // Take a channel from the middle of the list off the air entirely.
        val victim = LiveChannels.CURATED[LiveChannels.CURATED.size / 2]
        val degraded = LiveChannels.CURATED.map {
            if (it.id == victim.id) it.copy(verification = Verification.FAILED) else it
        }
        val after = ChannelLineup.lineup(degraded).associate { it.channel.id to it.number }

        assertNull("the dead channel should be dropped", after[victim.id])
        for ((id, number) in before) {
            if (id == victim.id) continue
            assertEquals("channel $id moved", number, after[id])
        }
    }

    @Test
    fun aDroppedChannelLeavesAGapRatherThanClosingUp() {
        val line = ChannelLineup.lineup(listOf(ch("a"), ch("b", verification = Verification.FAILED), ch("c")))
        assertEquals(listOf(2, 4), line.map { it.number })
        assertNull(ChannelLineup.at(line, 3))
    }

    // ── the community directory ─────────────────────────────────────────────────────────────────

    @Test
    fun communityChannelsAreNumberedFromTheirOwnBandAlphabetically() {
        val community = listOf(
            ch("z", name = "Zed News", provenance = Provenance.COMMUNITY, verification = Verification.UNVERIFIED),
            ch("a", name = "Alpha News", provenance = Provenance.COMMUNITY, verification = Verification.UNVERIFIED),
        )
        val line = ChannelLineup.lineup(listOf(ch("cur")), community)
        val alpha = line.first { it.channel.id == "a" }
        val zed = line.first { it.channel.id == "z" }
        assertEquals(Band.COMMUNITY.first, alpha.number)
        assertEquals(Band.COMMUNITY.first + 1, zed.number)
        // and the curated channel keeps its own number regardless of the directory beside it
        assertEquals(Band.NETWORKS.first, line.first { it.channel.id == "cur" }.number)
    }

    // ── the remote ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun channelUpAndDownWrapAroundTheEnds() {
        val line = ChannelLineup.lineup(listOf(ch("a"), ch("b"), ch("c")))
        val lowest = line.first().number
        val highest = line.last().number
        assertEquals(lowest + 1, ChannelLineup.next(line, lowest)?.number)
        assertEquals(lowest, ChannelLineup.next(line, highest)?.number)
        assertEquals(highest, ChannelLineup.previous(line, lowest)?.number)
        assertEquals(highest - 1, ChannelLineup.previous(line, highest)?.number)
    }

    @Test
    fun channelUpFromAGapFindsTheNextRealChannel() {
        val line = ChannelLineup.lineup(listOf(ch("a"), ch("b", verification = Verification.FAILED), ch("c")))
        // 2 and 4 exist, 3 is a gap
        assertEquals(4, ChannelLineup.next(line, 3)?.number)
        assertEquals(2, ChannelLineup.previous(line, 3)?.number)
    }

    @Test
    fun anEmptyLineupTunesToNothingRatherThanThrowing() {
        assertNull(ChannelLineup.next(emptyList(), 5))
        assertNull(ChannelLineup.previous(emptyList(), 5))
        assertNull(ChannelLineup.at(emptyList(), 5))
    }

    // ── the keypad ──────────────────────────────────────────────────────────────────────────────

    /**
     * ⚠️ The behaviour that makes it feel like a box rather than a text field. Against a lineup
     * whose numbers are 2..4 nothing begins with 9, so keying 9 must resolve at once instead of
     * sitting through two seconds of nothing.
     */
    @Test
    fun aDigitThatCanNeverBeExtendedTunesImmediately() {
        val line = ChannelLineup.lineup(listOf(ch("a"), ch("b"), ch("c")))   // 2, 3, 4
        assertEquals(Tune.Tuned(line[1]), ChannelLineup.key(Entry(), 3, 0L, line))
        assertTrue(ChannelLineup.key(Entry(), 9, 0L, line) is Tune.NoChannel)
    }

    @Test
    fun aDigitThatCouldStillReachSomethingWaitsForTheNext() {
        // 12 channels puts numbers 2..13 in play, so keying 1 could still become 10..13
        val many = (1..12).map { ch("c$it") }
        val line = ChannelLineup.lineup(many)
        val typing = ChannelLineup.key(Entry(), 1, 0L, line)
        assertTrue("keying 1 should wait, got $typing", typing is Tune.Typing)

        val second = ChannelLineup.key((typing as Tune.Typing).entry, 2, 100L, line)
        assertEquals(12, (second as Tune.Tuned).slot.number)
    }

    @Test
    fun anEntryLeftTooLongStartsOverRatherThanConcatenating() {
        val many = (1..12).map { ch("c$it") }
        val line = ChannelLineup.lineup(many)
        val first = ChannelLineup.key(Entry(), 1, 0L, line) as Tune.Typing
        assertTrue(ChannelLineup.expired(first.entry, ChannelLineup.ENTRY_TIMEOUT_MS))

        // keying 3 a full timeout later must mean channel 3, never channel 13
        val late = ChannelLineup.key(first.entry, 3, ChannelLineup.ENTRY_TIMEOUT_MS, line)
        assertEquals(3, (late as Tune.Tuned).slot.number)
    }

    @Test
    fun aPendingEntryCommitsToWhatWasKeyed() {
        val many = (1..12).map { ch("c$it") }
        val line = ChannelLineup.lineup(many)
        val typing = ChannelLineup.key(Entry(), 1, 0L, line) as Tune.Typing
        assertEquals(Tune.NoChannel(1), ChannelLineup.commit(typing.entry, line))
    }

    @Test
    fun keyingAChannelThatIsAGapSaysSoRatherThanTuningNearby() {
        val line = ChannelLineup.lineup(listOf(ch("a"), ch("b", verification = Verification.FAILED), ch("c")))
        assertEquals(Tune.NoChannel(3), ChannelLineup.key(Entry(), 3, 0L, line))
    }

    /**
     * ⚠️ A box writes channel 2 as **02**, so keying the padded form has to work. Found by running
     * the real lineup through the keypad: the first rule matched digit STRINGS as prefixes, no
     * channel number begins with "0", so keying 0 answered "no channel 0" and 0→2 could never
     * reach BBC News.
     */
    @Test
    fun aLeadingZeroWaitsAndThenReachesTheChannelItPads() {
        val line = ChannelLineup.lineup()
        val zero = ChannelLineup.key(Entry(), 0, 0L, line)
        assertTrue("keying 0 should wait, got $zero", zero is Tune.Typing)

        val tuned = ChannelLineup.key((zero as Tune.Typing).entry, 2, 50L, line)
        assertEquals(2, (tuned as Tune.Tuned).slot.number)
    }

    /**
     * ⚠️ The other half of the same defect: a one-digit-lookahead rule commits a `9` that could
     * still have grown into `900`. Only visible with a three-digit lineup, which is what switching
     * the community directory on produces.
     */
    @Test
    fun aDigitIsNotCommittedWhileAThreeDigitChannelCouldStillReachIt() {
        val community = (1..12).map {
            ch("com$it", name = "Community $it", provenance = Provenance.COMMUNITY,
                verification = Verification.UNVERIFIED)
        }
        val line = ChannelLineup.lineup(listOf(ch("a")), community)   // 2, then 100..111
        assertTrue(line.any { it.number >= 100 })
        val one = ChannelLineup.key(Entry(), 1, 0L, line)
        assertTrue("1 could still become 100, got $one", one is Tune.Typing)
        val ten = ChannelLineup.key((one as Tune.Typing).entry, 0, 10L, line)
        assertTrue("10 could still become 100, got $ten", ten is Tune.Typing)
        val hundred = ChannelLineup.key((ten as Tune.Typing).entry, 0, 20L, line)
        assertEquals(100, (hundred as Tune.Tuned).slot.number)
    }

    @Test
    fun aNonDigitIsIgnoredRatherThanClearingTheEntry() {
        val line = ChannelLineup.lineup(listOf(ch("a")))
        val entry = Entry("1", 0L)
        assertEquals(Tune.Typing(entry), ChannelLineup.key(entry, -1, 0L, line))
    }

    @Test
    fun numbersAreShownTheWayABoxShowsThem() {
        assertEquals("02", ChannelLineup.display(2))
        assertEquals("07", ChannelLineup.display(7))
        assertEquals("41", ChannelLineup.display(41))
        assertEquals("100", ChannelLineup.display(100))
    }

    @Test
    fun theGuideCanBeReadBandByBand() {
        val grouped = ChannelLineup.bands(ChannelLineup.lineup())
        assertTrue("no bands", grouped.isNotEmpty())
        assertEquals("bands out of order", grouped.map { it.first.first }.sorted(), grouped.map { it.first.first })
        assertNotNull(grouped.firstOrNull { it.first == Band.NETWORKS })
        // every channel is in exactly one band
        assertEquals(ChannelLineup.lineup().size, grouped.sumOf { it.second.size })
    }
}
