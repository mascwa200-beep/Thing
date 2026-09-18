package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutTest {

    private val page = listOf("target", "macros", "log", "water")

    // ------------------------------------------------------------------------------- the default

    @Test
    fun `a page nobody has arranged comes out as it was declared`() {
        assertEquals(page, DashboardLayout.arrange(page))
    }

    @Test
    fun `an arrangement is honoured`() {
        assertEquals(
            listOf("log", "water", "target", "macros"),
            DashboardLayout.arrange(page, saved = listOf("log", "water", "target", "macros")),
        )
    }

    // ------------------------------------------------------------- the card the arrangement missed

    @Test
    fun `a card added after the arrangement was made still appears`() {
        // ⚠️ The whole reason this core exists as more than a sort. An arrangement is written once
        // and outlives every release after it, so a card shipped later is absent from it. Showing
        // the saved order and nothing else makes that card permanently invisible on exactly the
        // devices that have been used longest, and it renders perfectly, so nobody can report it.
        val saved = listOf("log", "target")
        val out = DashboardLayout.arrange(page, saved)
        assertEquals(listOf("log", "target", "macros", "water"), out)
    }

    @Test
    fun `cards the arrangement missed keep the order the page declares them in`() {
        // Appended, not interleaved: once the order has been permuted there is no honest place to
        // put a newcomer "back", so the rule is the simple one and the page's own order breaks the
        // tie among the newcomers.
        val out = DashboardLayout.arrange(page, saved = listOf("water"))
        assertEquals(listOf("water", "target", "macros", "log"), out)
    }

    // --------------------------------------------------------------------------- a card that went

    @Test
    fun `an arrangement naming a card that no longer exists still draws the rest`() {
        val out = DashboardLayout.arrange(page, saved = listOf("weather", "log", "target"))
        assertEquals(listOf("log", "target", "macros", "water"), out)
    }

    @Test
    fun `an arrangement keeps a card the page cannot currently draw`() {
        // ⚠️ The storage half of the rule above, and the one that would be easy to get wrong in the
        // other direction: a card can be absent for a release, behind a capability or a permission,
        // and pruning it the first time somebody nudges another card would quietly throw away an
        // arrangement they made on purpose.
        val saved = listOf("weather", "log", "target", "macros", "water")
        val visible = DashboardLayout.arrange(page, saved)
        val stored = DashboardLayout.remember(visible, saved)
        assertTrue("the absent card was dropped from storage", stored.contains("weather"))
        assertEquals(visible, stored.take(visible.size))
    }

    @Test
    fun `an arrangement never grows without bound`() {
        val saved = (1..200).map { "gone$it" }
        val stored = DashboardLayout.remember(page, saved)
        assertEquals(DashboardLayout.MAX_REMEMBERED, stored.size)
        assertEquals(page, stored.take(page.size))
    }

    @Test
    fun `a duplicated id is collapsed`() {
        assertEquals(
            listOf("log", "target", "macros", "water"),
            DashboardLayout.arrange(page, saved = listOf("log", "log", "target")),
        )
    }

    // -------------------------------------------------------------------------------- putting away

    @Test
    fun `a card put away is not drawn`() {
        val out = DashboardLayout.arrange(page, hidden = setOf("water"))
        assertEquals(listOf("target", "macros", "log"), out)
    }

    @Test
    fun `the editing list shows a card that is put away`() {
        // Otherwise there is no way to bring it back, which is the commonest way a hide control
        // becomes a delete control by accident.
        assertEquals(page, DashboardLayout.editable(page, saved = emptyList()))
    }

    @Test
    fun `putting away something that is not there changes nothing`() {
        assertEquals(setOf("water"), DashboardLayout.hide(setOf("water"), "water"))
        assertEquals(setOf("water"), DashboardLayout.show(setOf("water"), "nonsense"))
    }

    // -------------------------------------------------------------------------------------- moving

    @Test
    fun `a card moves`() {
        assertEquals(
            listOf("macros", "target", "log", "water"),
            DashboardLayout.move(page, "macros", -1),
        )
        assertEquals(
            listOf("macros", "log", "target", "water"),
            DashboardLayout.move(page, "target", 2),
        )
    }

    @Test
    fun `a move off either end is the list unchanged rather than an error`() {
        // ⚠️ A reorder control is held down and repeated, so the last press of every arrangement is
        // one that cannot be honoured. Refusing it loudly would end every arrangement in an error
        // nobody caused.
        assertEquals(page, DashboardLayout.move(page, "target", -1))
        assertEquals(page, DashboardLayout.move(page, "water", 1))
        assertEquals(page, DashboardLayout.move(page, "target", -99))
        assertEquals(page, DashboardLayout.move(page, "water", 99))
    }

    @Test
    fun `moving a card that is not there changes nothing`() {
        assertEquals(page, DashboardLayout.move(page, "nonsense", 1))
        assertEquals(page, DashboardLayout.move(page, "target", 0))
    }

    // ------------------------------------------------------------------------- is it arranged at all

    @Test
    fun `an untouched page is the default`() {
        assertTrue(DashboardLayout.isDefault(page, emptyList(), emptySet()))
    }

    @Test
    fun `an arrangement that happens to match the default is still the default`() {
        // ⚠️ Compared against what the page would SHOW, not against an empty saved list. Otherwise
        // a reset control appears offering to change nothing, which teaches people it does nothing.
        assertTrue(DashboardLayout.isDefault(page, page, emptySet()))
    }

    @Test
    fun `a real arrangement is not the default`() {
        assertFalse(DashboardLayout.isDefault(page, listOf("log", "target"), emptySet()))
    }

    @Test
    fun `putting a card away is an arrangement even when the order is untouched`() {
        assertFalse(DashboardLayout.isDefault(page, emptyList(), setOf("water")))
    }

    @Test
    fun `a card put away that the page cannot draw anyway is not an arrangement`() {
        // Hiding is remembered by id, so an id left over from a card that has gone must not make a
        // page look arranged for ever after.
        assertTrue(DashboardLayout.isDefault(page, emptyList(), setOf("weather")))
    }

    // ------------------------------------------------------------------------------------ the words

    @Test
    fun `the sentence says the page is as it came`() {
        assertEquals("Arranged as it came.", DashboardLayout.describe(page, emptyList(), emptySet()))
    }

    @Test
    fun `the sentence counts what is showing`() {
        assertEquals(
            "4 cards, arranged.",
            DashboardLayout.describe(page, listOf("log", "target"), emptySet()),
        )
    }

    @Test
    fun `the sentence counts what was put away, and never counts to zero`() {
        val s = DashboardLayout.describe(page, emptyList(), setOf("water"))
        assertEquals("3 cards, arranged · 1 put away.", s)
        assertFalse("a zero was rendered", DashboardLayout.describe(page, page.reversed(), emptySet()).contains("0"))
    }

    @Test
    fun `one card is one card`() {
        assertEquals(
            "1 card, arranged · 3 put away.",
            DashboardLayout.describe(page, emptyList(), setOf("macros", "log", "water")),
        )
    }
}
