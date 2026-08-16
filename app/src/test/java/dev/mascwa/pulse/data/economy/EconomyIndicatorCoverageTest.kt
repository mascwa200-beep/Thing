package dev.mascwa.pulse.data.economy

import dev.mascwa.pulse.core.telemetry.EconomyExplainers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the seam between the indicator list and the plain-English explanations.
 *
 * `EconomyExplainers.forIndicator` is a `when` over indicator ids with a generic `else`, which means
 * a new indicator compiles, renders, and looks finished while explaining nothing — the fallback
 * swallows it silently. This test is the reason that cannot happen twice: the fallback stays as a
 * safety net for a cached series whose id the enum no longer knows, but nothing shipped in the enum
 * may rely on it.
 *
 * It lives in the app module rather than beside the other explainer tests because
 * `EconomyIndicator` lives here, and enumerating it is the whole point — a hand-maintained list of
 * ids in the core test would drift from the enum the moment someone added a row.
 */
class EconomyIndicatorCoverageTest {

    /** The text the `else` branch produces, matched on a distinctive fragment rather than in full. */
    private val fallbackFragment = "A macroeconomic statistic from the World Bank"

    @Test
    fun everyIndicatorHasItsOwnExplanation() {
        val mute = EconomyIndicator.entries.filter { indicator ->
            EconomyExplainers.forIndicator(indicator.id, 1.0).detail.contains(fallbackFragment)
        }
        assertTrue(
            "These indicators fall through to the generic blurb and would ship saying nothing: " +
                mute.joinToString { "${it.name} (${it.id})" },
            mute.isEmpty(),
        )
    }

    /** The safety net itself still has to work, for an id that reaches the app from an old cache. */
    @Test
    fun anUnknownIdStillGetsSomething() {
        val e = EconomyExplainers.forIndicator("XX.MADE.UP.ID", 1.0)
        assertTrue(e.detail.contains(fallbackFragment))
        assertFalse(e.headline.isBlank())
    }

    /** A duplicate id would silently hide one indicator behind another in every lookup. */
    @Test
    fun indicatorIdsAreUnique() {
        val ids = EconomyIndicator.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    /**
     * Every indicator declares a group, so the grouped list cannot quietly drop one.
     *
     * Enum non-nullability already guarantees this at compile time; the case worth asserting is that
     * no group is left with nothing in it, which would render an empty header — and that the two
     * groups a reader looks at first are not empty by accident.
     */
    @Test
    fun everyGroupHasAtLeastOneIndicator() {
        val used = EconomyIndicator.entries.map { it.group }.toSet()
        val empty = EconomyGroup.entries.filterNot { it in used }
        assertTrue("groups with no indicators would render a bare header: $empty", empty.isEmpty())
    }

    /**
     * The neutral direction is used, and used sparingly.
     *
     * If every indicator went neutral the colour would stop carrying information; if none did, the
     * app would be asserting that more military spending is straightforwardly good or bad. Both are
     * failures, so this pins that the middle ground is actually occupied.
     */
    @Test
    fun theNeutralDirectionIsUsedForTheGenuinelyTwoSidedOnes() {
        val neutral = EconomyIndicator.entries.filter { it.higherIsBetter == null }.map { it.name }
        assertTrue("expected some indicators to have no good direction", neutral.isNotEmpty())
        assertTrue(
            "most indicators should still have a direction; neutral = $neutral",
            neutral.size < EconomyIndicator.entries.size / 2,
        )
        assertTrue("military spending must not be coloured good or bad", "MILITARY_SPEND" in neutral)
    }
}
